package com.harmony.backend.common.entity;


import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("login_log")
public class LoginLog {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("username")
    private String username;

    @TableField("ip_address")
    private String ipAddress;

    @TableField("user_agent")
    private String userAgent;

    @TableField("location")
    private String location;

    @TableField("success")
    private Boolean success;

    @TableField("fail_reason")
    private String failReason;

    @TableField(value = "login_time", fill = FieldFill.INSERT)
    private LocalDateTime loginTime;
}
