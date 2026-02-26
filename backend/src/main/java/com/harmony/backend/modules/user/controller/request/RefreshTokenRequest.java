package com.harmony.backend.modules.user.controller.request;

import lombok.Data;

@Data
public class RefreshTokenRequest {
    private String refreshToken;
}
