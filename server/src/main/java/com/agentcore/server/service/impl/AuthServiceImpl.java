package com.agentcore.server.service.impl;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.dao.UserDao;
import com.agentcore.server.domain.User;
import com.agentcore.server.service.AuthService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthServiceImpl implements AuthService {

    private final UserDao userDao;
    private final PasswordEncoder passwordEncoder;

    public AuthServiceImpl(UserDao userDao, PasswordEncoder passwordEncoder) {
        this.userDao = userDao;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public long register(String email, String password) {
        // Fast path for the common duplicate; the unique index stays the
        // race-safe authority via the DuplicateKeyException below.
        User existing = userDao.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getEmail, email));
        if (existing != null) {
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
}
