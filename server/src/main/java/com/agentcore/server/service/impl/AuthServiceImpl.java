package com.agentcore.server.service.impl;

import com.agentcore.server.common.auth.JwtService;
import com.agentcore.server.common.auth.RefreshTokens;
import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.config.AuthProperties;
import com.agentcore.server.dao.RefreshTokenDao;
import com.agentcore.server.dao.UserDao;
import com.agentcore.server.domain.AccessToken;
import com.agentcore.server.domain.AuthResult;
import com.agentcore.server.domain.RefreshToken;
import com.agentcore.server.domain.User;
import com.agentcore.server.domain.UserProfile;
import com.agentcore.server.service.AuthService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthServiceImpl implements AuthService {

    /** Valid BCrypt hash used to equalize timing when the account is unknown. */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final UserDao userDao;
    private final RefreshTokenDao refreshTokenDao;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthProperties authProperties;

    public AuthServiceImpl(
            UserDao userDao,
            RefreshTokenDao refreshTokenDao,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            AuthProperties authProperties) {
        this.userDao = userDao;
        this.refreshTokenDao = refreshTokenDao;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.authProperties = authProperties;
    }

    @Override
    public long register(String email, String password) {
        // Fast path for the common duplicate; the unique index stays the
        // race-safe authority via the DuplicateKeyException below.
        if (findByEmail(email) != null) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_EXISTS, "email is already registered");
        }

        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(password));
        try {
            userDao.insert(user);
        } catch (DuplicateKeyException ex) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_EXISTS, "email is already registered");
        }
        return user.getId();
    }

    @Override
    public AuthResult login(String email, String password) {
        User user = findByEmail(email);
        String hash = user == null ? DUMMY_HASH : user.getPasswordHash();
        boolean matches = passwordEncoder.matches(password, hash);
        if (user == null || !matches) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "invalid email or password");
        }

        String refreshToken = RefreshTokens.newToken();
        insertRefreshToken(user.getId(), UUID.randomUUID().toString(), refreshToken);
        return resultFor(user.getId(), refreshToken);
    }

    /**
     * The row lock serializes concurrent rotations of the same token; the
     * family revoke must still commit although a 401 is thrown afterwards,
     * hence {@code noRollbackFor}.
     */
    @Override
    @Transactional(noRollbackFor = ApiException.class)
    public AuthResult refresh(String presentedToken) {
        RefreshToken stored = refreshTokenDao.findByTokenHashForUpdate(RefreshTokens.hash(presentedToken));
        if (stored == null) {
            throw invalidRefreshToken();
        }
        if (stored.getRevokedAt() != null) {
            // A rotated token came back: treat the whole lineage as compromised.
            refreshTokenDao.revokeFamily(stored.getFamilyId(), nowUtc());
            throw invalidRefreshToken();
        }
        if (!stored.getExpiresAt().isAfter(nowUtc())) {
            throw invalidRefreshToken();
        }

        revoke(stored);

        String newToken = RefreshTokens.newToken();
        insertRefreshToken(stored.getUserId(), stored.getFamilyId(), newToken);
        return resultFor(stored.getUserId(), newToken);
    }

    @Override
    public void logout(String presentedToken) {
        if (presentedToken == null || presentedToken.isBlank()) {
            return;
        }
        RefreshToken stored = refreshTokenDao.selectOne(new LambdaQueryWrapper<RefreshToken>()
                .eq(RefreshToken::getTokenHash, RefreshTokens.hash(presentedToken)));
        if (stored != null && stored.getRevokedAt() == null) {
            revoke(stored);
        }
    }

    @Override
    public UserProfile profile(long userId) {
        User user = userDao.selectById(userId);
        if (user == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "account not found");
        }
        return new UserProfile(user.getId(), user.getEmail());
    }

    @Override
    @Transactional
    public void changePassword(long userId, String currentPassword, String newPassword) {
        User user = userDao.selectById(userId);
        if (user == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "account not found");
        }
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "current password is incorrect");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userDao.updateById(user);
        // Same transaction: a leaked refresh token must die with the old password.
        refreshTokenDao.revokeAllForUser(userId, nowUtc());
    }

    @Override
    @Transactional
    public void logoutAll(long userId) {
        refreshTokenDao.revokeAllForUser(userId, nowUtc());
    }

    private AuthResult resultFor(long userId, String refreshToken) {
        AccessToken accessToken =
                new AccessToken(jwtService.issue(userId), jwtService.accessTtlSeconds());
        return new AuthResult(accessToken, refreshToken);
    }

    private void insertRefreshToken(long userId, String familyId, String rawToken) {
        RefreshToken row = new RefreshToken();
        row.setUserId(userId);
        row.setTokenHash(RefreshTokens.hash(rawToken));
        row.setFamilyId(familyId);
        row.setExpiresAt(nowUtc().plus(authProperties.refreshTtl()));
        refreshTokenDao.insert(row);
    }

    private void revoke(RefreshToken token) {
        token.setRevokedAt(nowUtc());
        refreshTokenDao.updateById(token);
    }

    private static ApiException invalidRefreshToken() {
        return new ApiException(ErrorCode.UNAUTHORIZED, "invalid refresh token");
    }

    private User findByEmail(String email) {
        return userDao.selectOne(new LambdaQueryWrapper<User>().eq(User::getEmail, email));
    }

    private static LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
