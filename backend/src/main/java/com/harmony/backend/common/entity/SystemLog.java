package com.harmony.backend.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("system_log")
public class SystemLog {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("operation")
    private String operation;

    @TableField("module")
    private String module;

    @TableField("request_ip")
    private String requestIp;

    @TableField("status")
    private String status;

    @TableField("created_at")
    private LocalDateTime createdAt;

    public enum Status {
        SUCCESS("SUCCESS"),
        FAILED("FAILED");

        private final String value;

        Status(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    public static class Operation {
        public static final String LOGIN = "LOGIN";
        public static final String LOGOUT = "LOGOUT";
        public static final String CREATE_SESSION = "CREATE_SESSION";
        public static final String SEND_MESSAGE = "SEND_MESSAGE";
        public static final String CREATE_GPT = "CREATE_GPT";
        public static final String UPDATE_GPT = "UPDATE_GPT";
        public static final String DELETE_GPT = "DELETE_GPT";
        public static final String ADD_FAVORITE = "ADD_FAVORITE";
        public static final String REMOVE_FAVORITE = "REMOVE_FAVORITE";
        public static final String CONSUME_TOKENS = "CONSUME_TOKENS";
        public static final String RECHARGE_TOKENS = "RECHARGE_TOKENS";
    }

    public static class Module {
        public static final String AUTH = "AUTH";
        public static final String CHAT = "CHAT";
        public static final String GPT = "GPT";
        public static final String FAVORITE = "FAVORITE";
        public static final String BALANCE = "BALANCE";
        public static final String SYSTEM = "SYSTEM";
    }
}