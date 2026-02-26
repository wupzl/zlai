package com.harmony.backend.modules.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.harmony.backend.common.entity.User;
import com.harmony.backend.common.mapper.UserMapper;
import com.harmony.backend.common.response.PageResult;
import com.harmony.backend.common.util.PageResultUtils;
import com.harmony.backend.modules.admin.service.AdminUserService;
import com.harmony.backend.modules.admin.controller.request.AdminUserUpdateRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Slf4j
@RequiredArgsConstructor
public class AdminUserServiceImpl extends ServiceImpl<UserMapper, User> implements AdminUserService {

    private final PasswordEncoder passwordEncoder;

    @Override
    public PageResult<User> listUsers(int page, int size, String keyword) {
        Page<User> pageResult = new Page<>(page, size);
        LambdaQueryWrapper<User> query = new LambdaQueryWrapper<>();
        query.eq(User::getDeleted, false);
        if (StringUtils.hasText(keyword)) {
            query.and(q -> q.like(User::getUsername, keyword)
                    .or()
                    .like(User::getNickname, keyword));
        }
        query.orderByDesc(User::getCreatedAt);
        Page<User> result = baseMapper.selectPage(pageResult, query);
        long total = baseMapper.selectCount(query);
        result.setTotal(total);
        return PageResultUtils.from(result);
    }

    @Override
    public boolean updateStatus(Long id, String status) {
        if (id == null || !StringUtils.hasText(status)) {
            return false;
        }
        User user = new User();
        user.setId(id);
        user.setStatus(status);
        return baseMapper.updateById(user) > 0;
    }

    @Override
    public boolean updateBalance(Long id, int delta) {
        if (id == null) {
            return false;
        }
        return baseMapper.updateTokenBalance(id, delta) > 0;
    }

    @Override
    public boolean resetPassword(Long id, String newPassword) {
        if (id == null || !StringUtils.hasText(newPassword)) {
            return false;
        }
        User user = new User();
        user.setId(id);
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setLastPasswordChange(java.time.LocalDateTime.now());
        return baseMapper.updateById(user) > 0;
    }

    @Override
    public boolean deleteUser(Long id) {
        if (id == null) {
            return false;
        }
        User user = new User();
        user.setId(id);
        user.setDeleted(true);
        return baseMapper.updateById(user) > 0;
    }

    @Override
    public User getDetail(Long id) {
        if (id == null) {
            return null;
        }
        return baseMapper.selectById(id);
    }

    @Override
    public boolean updateUser(Long id, AdminUserUpdateRequest request) {
        if (id == null || request == null) {
            return false;
        }
        User user = new User();
        user.setId(id);
        if (StringUtils.hasText(request.getNickname())) {
            user.setNickname(request.getNickname());
        }
        if (StringUtils.hasText(request.getAvatarUrl())) {
            user.setAvatarUrl(request.getAvatarUrl());
        }
        if (StringUtils.hasText(request.getRole())) {
            user.setRole(request.getRole());
        }
        if (StringUtils.hasText(request.getStatus())) {
            user.setStatus(request.getStatus());
        }
        return baseMapper.updateById(user) > 0;
    }

    
}
