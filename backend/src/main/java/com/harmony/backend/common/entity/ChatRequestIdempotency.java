package com.harmony.backend.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
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
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("chat_request_idempotency")
public class ChatRequestIdempotency {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("request_id")
    private String requestId;

    @TableField("request_type")
    private String requestType;

    @TableField("chat_id")
    private String chatId;

    @TableField("message_id")
    private String messageId;

    @TableField("request_hash")
    private String requestHash;

    @TableField("status")
    private String status;

    @TableField("response_message_id")
    private String responseMessageId;

    @TableField("response_content")
    private String responseContent;

    @TableField("error_message")
    private String errorMessage;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
