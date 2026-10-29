package com.agentcore.server.service.impl;

import com.agentcore.server.common.auth.AdminGuard;
import com.agentcore.server.common.auth.EmailNormalizer;
import com.agentcore.server.common.auth.EmailPolicy;
import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.common.oauth.OAuthIntent;
import com.agentcore.server.common.oauth.OAuthState;
import com.agentcore.server.common.oauth.OAuthStateCodec;
import com.agentcore.server.common.oauth.OAuthStateInvalidException;
import com.agentcore.server.common.oauth.OidcProfile;
import com.agentcore.server.common.oauth.OidcProviderClient;
import com.agentcore.server.common.oauth.OidcTokenResponse;
import com.agentcore.server.config.AdminProperties;
import com.agentcore.server.config.OAuthProperties;
import com.agentcore.server.dao.UserDao;
import com.agentcore.server.dao.UserIdentityDao;
import com.agentcore.server.domain.AuthResult;
import com.agentcore.server.domain.OAuthStartResult;
import com.agentcore.server.domain.User;
import com.agentcore.server.domain.UserIdentity;
import com.agentcore.server.service.AuthService;
import com.agentcore.server.service.OAuthService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** OIDC login orchestration (ADR-0027). Provider calls stay behind the port. */
@Service
public class OAuthServiceImpl implements OAuthService {

    private static final Logger log = LoggerFactory.getLogger(OAuthServiceImpl.class);
    private static final int STATE_BYTES = 32;
    private static final int NONCE_BYTES = 16;
    private static final int VERIFIER_BYTES = 32;

    private final OAuthProperties properties;
    private final OidcProviderClient client;
    private final OAuthStateCodec stateCodec;
    private final UserDao userDao;
    private final UserIdentityDao identityDao;
    private final AuthService authService;
    private final AdminProperties adminProperties;
    private final TransactionTemplate transactionTemplate;
    private final SecureRandom random = new SecureRandom();

    public OAuthServiceImpl(
            OAuthProperties properties,
            OidcProviderClient client,
            OAuthStateCodec stateCodec,
            UserDao userDao,
            UserIdentityDao identityDao,
            AuthService authService,
            AdminProperties adminProperties,
            PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.client = client;
        this.stateCodec = stateCodec;
        this.userDao = userDao;
        this.identityDao = identityDao;
        this.authService = authService;
        this.adminProperties = adminProperties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public OAuthStartResult startLogin() {
        requireEnabled();
        String state = randomToken(STATE_BYTES);
        String nonce = randomToken(NONCE_BYTES);
        String verifier = randomToken(VERIFIER_BYTES);
        String url = client.buildAuthorizationUrl(state, nonce, codeChallenge(verifier));
        OAuthState stored = new OAuthState(
                OAuthState.CURRENT_VERSION, state, nonce, verifier, OAuthIntent.LOGIN, null,
                Instant.now().getEpochSecond());
        return new OAuthStartResult(url, stateCodec.encode(stored));
    }

    @Override
    public AuthResult completeLogin(String code, String state, String stateCookie) {
        requireEnabled();
        OAuthState stored;
        try {
            stored = stateCodec.decode(stateCookie);
        } catch (OAuthStateInvalidException ex) {
            throw new ApiException(ErrorCode.OAUTH_STATE_INVALID, ex.getMessage(), ex);
        }
        if (stored.intent() != OAuthIntent.LOGIN || !stored.state().equals(state)) {
            throw new ApiException(ErrorCode.OAUTH_STATE_INVALID, "OAuth state is invalid");
        }

        OidcTokenResponse tokens = client.exchangeCode(code, stored.codeVerifier());
        OidcProfile profile = client.verifyIdToken(tokens.idToken(), stored.nonce());

        UserIdentity identity =
                identityDao.findByIssuerAndSubject(profile.issuer(), profile.subject());
        if (identity != null) {
            touch(identity);
            return authService.issueSession(identity.getUserId());
        }

        String email = verifiedEmail(profile, tokens.accessToken());
        if (findByEmail(email) != null) {
            throw new ApiException(ErrorCode.OAUTH_ACCOUNT_EXISTS,
                    "email is already registered; log in with the existing method and link OAuth");
        }

        try {
            Long userId = transactionTemplate.execute(status -> createAccount(email, profile));
            if (userId == null) {
                throw new ApiException(ErrorCode.OAUTH_UNAVAILABLE,
                        "OAuth account creation did not return an id");
            }
            return authService.issueSession(userId);
        } catch (DuplicateKeyException ex) {
            UserIdentity raced =
                    identityDao.findByIssuerAndSubject(profile.issuer(), profile.subject());
            if (raced != null) {
                touch(raced);
                return authService.issueSession(raced.getUserId());
            }
            if (findByEmail(email) != null) {
                throw new ApiException(ErrorCode.OAUTH_ACCOUNT_EXISTS,
                        "email is already registered; log in with the existing method and link OAuth");
            }
            throw new ApiException(ErrorCode.OAUTH_UNAVAILABLE,
                    "OAuth account creation conflicted; retry later", ex);
        }
    }

    private String verifiedEmail(OidcProfile profile, String accessToken) {
        String email = profile.verifiedEmail().orElse(null);
        if (email == null) {
            OidcProfile userInfo = client.fetchUserInfo(accessToken, profile.subject());
            email = userInfo.verifiedEmail().orElse(null);
        }
        if (email == null) {
            throw new ApiException(ErrorCode.OAUTH_EMAIL_UNVERIFIED,
                    "OIDC provider did not return a verified email");
        }
        String normalized = EmailNormalizer.normalize(email);
        if (!EmailPolicy.isValid(normalized)) {
            throw new ApiException(ErrorCode.OAUTH_EMAIL_UNVERIFIED,
                    "OIDC provider returned an invalid email");
        }
        return normalized;
    }

    private long createAccount(String email, OidcProfile profile) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(null);
        user.setRole(adminProperties.isAdminEmail(email) ? AdminGuard.ROLE_ADMIN : "user");
        userDao.insert(user);

        UserIdentity identity = new UserIdentity();
        identity.setUserId(user.getId());
        identity.setIssuer(profile.issuer());
        identity.setSubject(profile.subject());
        identityDao.insert(identity);
        return user.getId();
    }

    private User findByEmail(String email) {
        return userDao.selectOne(new LambdaQueryWrapper<User>().eq(User::getEmail, email));
    }

    private void touch(UserIdentity identity) {
        try {
            identityDao.updateLastLoginAt(identity.getId(), LocalDateTime.now(ZoneOffset.UTC));
        } catch (RuntimeException ex) {
            log.warn("failed to update OIDC last login for user {}", identity.getUserId(), ex);
        }
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new ApiException(ErrorCode.OAUTH_DISABLED, "OAuth is not enabled");
        }
    }

    private String randomToken(int byteCount) {
        byte[] bytes = new byte[byteCount];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String codeChallenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
