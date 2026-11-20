package com.agentcore.server.common.auth;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.dao.UserDao;
import com.agentcore.server.domain.User;
import org.springframework.stereotype.Component;

/**
 * Per-request admin check (ADR-0023). The role is read from the database so a
 * revocation takes effect on the next request instead of waiting for a token
 * to expire.
 */
@Component
public class AdminGuard {

    public static final String ROLE_ADMIN = "admin";

    private final UserDao userDao;

    public AdminGuard(UserDao userDao) {
        this.userDao = userDao;
    }

    public void requireAdmin(long userId) {
        User user = userDao.selectById(userId);
        if (user == null || !ROLE_ADMIN.equals(user.getRole())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "admin role required");
        }
    }
}
