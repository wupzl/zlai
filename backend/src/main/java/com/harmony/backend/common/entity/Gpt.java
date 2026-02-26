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
@TableName("gpt")
public class Gpt {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("gpt_id")
    private String gptId;

    @TableField("name")
    private String name;

    @TableField("description")
    private String description;

    @TableField("instructions")
    private String instructions;

    @TableField("model")
    private String model;

    @TableField("user_id")
    private Long userId;

    @TableField("avatar_url")
    private String avatarUrl;

    @TableField("category")
    private String category;

    @TableField("is_public")
    private Boolean isPublic;

    @TableField("usage_count")
    private Integer usageCount;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableField("is_deleted")
    @TableLogic
    private Boolean isDeleted;
}