package com.harmony.backend.modules.user.controller.request;

import lombok.Data;

@Data
public class LoginRequest {
    private String username;
    private String password;
    private String deviceId;
}
