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
@TableName("message")
public class Message {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("message_id")
    private String messageId;

    @TableField("chat_id")
    private String chatId;

    @TableField("parent_message_id")
    private String parentMessageId;

    @TableField("role")
    private String role;

    @TableField("content")
    private String content;

    @TableField("tokens")
    private Integer tokens;

    @TableField("model")
    private String model;

    @TableField("status")
    private String status;

    @TableField("is_deleted")
    @TableLogic
    private Boolean isDeleted;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
