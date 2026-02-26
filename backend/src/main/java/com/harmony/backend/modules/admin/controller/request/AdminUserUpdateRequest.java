package com.harmony.backend.modules.admin.controller.request;

import lombok.Data;

@Data
public class AdminUserUpdateRequest {
    private String nickname;
    private String avatarUrl;
    private String role;
    private String status;
}
