package com.harmony.backend.modules.admin.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.harmony.backend.common.entity.User;
import com.harmony.backend.common.response.PageResult;

public interface AdminUserService extends IService<User> {
    PageResult<User> listUsers(int page, int size, String keyword);

    boolean updateStatus(Long id, String status);

    boolean updateBalance(Long id, int delta);

    boolean resetPassword(Long id, String newPassword);

    boolean deleteUser(Long id);

    User getDetail(Long id);

    boolean updateUser(Long id, com.harmony.backend.modules.admin.controller.request.AdminUserUpdateRequest request);
}
