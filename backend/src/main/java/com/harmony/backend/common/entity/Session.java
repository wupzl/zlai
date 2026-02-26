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
@TableName("chat_session")
public class Session {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("chat_id")
    private String chatId;

    @TableField("user_id")
    private Long userId;

    @TableField("gpt_id")
    private String gptId;

    @TableField("agent_id")
    private String agentId;

    @TableField("title")
    private String title;

    @TableField("model")
    private String model;

    @TableField("tool_model")
    private String toolModel;

    @TableField("system_prompt")
    private String systemPrompt;

    @TableField("rag_enabled")
    private Boolean ragEnabled;

    @TableField("current_message_id")
    private String currentMessageId;

    @TableField("message_count")
    private Integer messageCount;

    @TableField("total_tokens")
    private Integer totalTokens;

    @TableField("last_active_time")
    private LocalDateTime lastActiveTime;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableField("is_deleted")
    @TableLogic
    private Boolean isDeleted;
}
