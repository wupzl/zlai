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
@TableName("token_consumption")
public class TokenConsumption {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("chat_id")
    private String chatId;

    @TableField("message_id")
    private String messageId;

    @TableField("model")
    private String model;

    @TableField("prompt_tokens")
    private Integer promptTokens;

    @TableField("completion_tokens")
    private Integer completionTokens;

    @TableField("total_tokens")
    private Integer totalTokens;

    @TableField("created_at")
    private LocalDateTime createdAt;

    public void calculateTotalTokens() {
        if (promptTokens == null) promptTokens = 0;
        if (completionTokens == null) completionTokens = 0;
        this.totalTokens = this.promptTokens + this.completionTokens;
    }
}