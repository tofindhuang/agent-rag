package com.agentcore.server.common.auth;

import com.agentcore.server.config.AdminProperties;
import com.agentcore.server.dao.UserDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Promotes existing accounts listed in {@code ADMIN_EMAILS} at startup. New
 * registrations are handled by {@code AuthService}; this class intentionally
 * never demotes anyone.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserDao userDao;
    private final AdminProperties properties;

    public AdminBootstrap(UserDao userDao, AdminProperties properties) {
        this.userDao = userDao;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (String email : properties.emails()) {
            int promoted = userDao.promoteToAdmin(email);
            if (promoted > 0) {
                log.info("promoted existing account to admin: {}", email);
            }
        }
    }
}
