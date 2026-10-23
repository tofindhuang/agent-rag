package com.agentcore.server.service.impl;

import com.agentcore.server.common.auth.JwtService;
import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.dao.UserDao;
import com.agentcore.server.domain.AccessToken;
import com.agentcore.server.domain.User;
import com.agentcore.server.domain.UserProfile;
import com.agentcore.server.service.AuthService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthServiceImpl implements AuthService {

    /** Valid BCrypt hash used to equalize timing when the account is unknown. */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final UserDao userDao;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthServiceImpl(UserDao userDao, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userDao = userDao;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
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
    public AccessToken login(String email, String password) {
        User user = findByEmail(email);
        String hash = user == null ? DUMMY_HASH : user.getPasswordHash();
        boolean matches = passwordEncoder.matches(password, hash);
        if (user == null || !matches) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "invalid email or password");
        }
        return new AccessToken(jwtService.issue(user.getId()), jwtService.accessTtlSeconds());
    }

    @Override
    public UserProfile profile(long userId) {
        User user = userDao.selectById(userId);
        if (user == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "account not found");
        }
        return new UserProfile(user.getId(), user.getEmail());
    }

    private User findByEmail(String email) {
        return userDao.selectOne(new LambdaQueryWrapper<User>().eq(User::getEmail, email));
    }
}
