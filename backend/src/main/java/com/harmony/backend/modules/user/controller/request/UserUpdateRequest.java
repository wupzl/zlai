package com.harmony.backend.modules.user.controller.request;

import lombok.Data;

@Data
public class UserUpdateRequest {
    private String nickname;
    private String avatarUrl;
}
