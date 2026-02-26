package com.harmony.backend.common.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("account")
public class User {
    private Long id;

    @TableField("username")
    private String username;

    @TableField("password")
    private String password;

    @TableField("nickname")
    private String nickname;

    @TableField("avatar_url")
    private String avatarUrl;

    @TableField("role")
    private String role;

    @TableField("status")
    private String status;

    @TableField("last_password_change")
    private LocalDateTime lastPasswordChange;

    @TableField("last_logout_time")
    private LocalDateTime lastLogoutTime;

    @TableField("token_balance")
    private Integer tokenBalance;

    @TableField("ocr_balance")
    private Integer ocrBalance;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableField("is_deleted")
    private Boolean deleted;

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    public boolean isLocked() {
        return "LOCKED".equals(status);
    }

    public void deductTokens(int amount) {
        if (amount <= 0) {
            return;
        }
        if (this.tokenBalance == null) {
            this.tokenBalance = 0;
        }
        if (this.tokenBalance < amount) {
            throw new IllegalStateException("Insufficient token balance");
        }
        this.tokenBalance -= amount;
    }

    public boolean canLogin() {
        return isActive() && !isLocked();
    }

    public void addTokens(int amount) {
        if (amount <= 0) {
            return;
        }
        if (this.tokenBalance == null) {
            this.tokenBalance = 0;
        }
        this.tokenBalance += amount;
    }

    public boolean isNormal() {
        return "ACTIVE".equals(status) && (deleted == null || !deleted);
    }

    public boolean isDisabled() {
        return deleted != null && deleted;
    }
}
