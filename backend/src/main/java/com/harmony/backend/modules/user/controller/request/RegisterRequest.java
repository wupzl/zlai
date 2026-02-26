package com.harmony.backend.modules.user.controller.request;

import lombok.Data;

@Data
public class RegisterRequest {
    private String username;
    private String password;
    private String nickname;
    private String avatarUrl;
}
