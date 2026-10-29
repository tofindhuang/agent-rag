package com.agentcore.server.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentcore.server.domain.UserIdentity;
import com.agentcore.server.support.AbstractIntegrationTest;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Persistence seam for {@link UserIdentity}: uniqueness, ordering and ownership. */
@Transactional
class UserIdentityDaoIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private UserIdentityDao identityDao;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void insertsAndFindsByIssuerAndSubject() {
        UserIdentity identity = identity(insertUser(), "keycloak", "user-1");
        identityDao.insert(identity);

        UserIdentity stored = identityDao.findByIssuerAndSubject(identity.getIssuer(), identity.getSubject());

        assertThat(stored).isNotNull();
        assertThat(stored.getUserId()).isEqualTo(identity.getUserId());
        assertThat(stored.getIssuer()).isEqualTo("keycloak");
        assertThat(stored.getSubject()).isEqualTo("user-1");
        assertThat(stored.getCreatedAt()).isNotNull();
        assertThat(stored.getLastLoginAt()).isNull();
    }

    @Test
    void listsIdentityRowsByUserInInsertionOrder() {
        long userId = insertUser();
        UserIdentity first = identity(userId, "keycloak", "first");
        UserIdentity second = identity(userId, "github", "second");
        identityDao.insert(first);
        identityDao.insert(second);

        List<UserIdentity> identities = identityDao.listByUserId(userId);

        assertThat(identities).extracting(UserIdentity::getId)
                .containsExactly(first.getId(), second.getId());
    }

    @Test
    void updatesOnlyTheLastLoginTimestamp() {
        UserIdentity identity = identity(insertUser(), "keycloak", "user-1");
        identityDao.insert(identity);
        LocalDateTime loggedIn = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);

        int updated = identityDao.updateLastLoginAt(identity.getId(), loggedIn);

        assertThat(updated).isEqualTo(1);
        assertThat(identityDao.findByIssuerAndSubject(identity.getIssuer(), identity.getSubject()).getLastLoginAt())
                .isEqualTo(loggedIn);
    }

    @Test
    void deletesOnlyIdentityOwnedByTheCallingUser() {
        UserIdentity mine = identity(insertUser(), "keycloak", "user-1");
        UserIdentity other = identity(insertUser(), "keycloak", "other");
        identityDao.insert(mine);
        identityDao.insert(other);

        assertThat(identityDao.deleteByIdAndUserId(mine.getId(), mine.getUserId())).isEqualTo(1);
        assertThat(identityDao.findByIssuerAndSubject(mine.getIssuer(), mine.getSubject())).isNull();
        assertThat(identityDao.deleteByIdAndUserId(other.getId(), other.getUserId() + 1)).isZero();
        assertThat(identityDao.findByIssuerAndSubject(other.getIssuer(), other.getSubject())).isNotNull();
    }

    @Test
    void rejectsADuplicateIssuerAndSubject() {
        UserIdentity first = identity(insertUser(), "keycloak", "duplicate");
        identityDao.insert(first);

        assertThatThrownBy(() -> identityDao.insert(identity(insertUser(), first.getIssuer(), first.getSubject())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsASecondIdentityForTheSameUserAndIssuer() {
        long userId = insertUser();
        UserIdentity first = identity(userId, "keycloak", "first");
        identityDao.insert(first);

        assertThatThrownBy(() -> identityDao.insert(identity(userId, first.getIssuer(), "second")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void treatsIssuerAndSubjectCaseSensitively() {
        UserIdentity identity = identity(insertUser(), "Keycloak", "Subject");
        identityDao.insert(identity);

        assertThat(identityDao.findByIssuerAndSubject(identity.getIssuer(), identity.getSubject())).isNotNull();
        assertThat(identityDao.findByIssuerAndSubject(identity.getIssuer().toLowerCase(), identity.getSubject().toLowerCase())).isNull();
    }

    private static UserIdentity identity(long userId) {
        return identity(userId, "keycloak", "user-" + UUID.randomUUID());
    }

    private static UserIdentity identity(long userId, String issuer, String subject) {
        UserIdentity identity = new UserIdentity();
        identity.setUserId(userId);
        identity.setIssuer(issuer);
        identity.setSubject(subject);
        return identity;
    }

    private long insertUser() {
        String email = "identity-" + UUID.randomUUID() + "@example.com";
        jdbc.update("INSERT INTO users (email, password_hash, role, created_at, updated_at)"
                + " VALUES (?, ?, 'user', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                email, "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy");
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }
}
