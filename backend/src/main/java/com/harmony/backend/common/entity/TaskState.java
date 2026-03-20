package com.harmony.backend.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
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
@TableName("task_state")
public class TaskState {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("chat_id")
    private String chatId;

    @TableField("user_id")
    private Long userId;

    @TableField("task_key")
    private String taskKey;

    @TableField("goal")
    private String goal;

    @TableField("current_skill")
    private String currentSkill;

    @TableField("current_step")
    private String currentStep;

    @TableField("artifacts_json")
    private String artifactsJson;

    @TableField("status")
    private String status;

    @TableField("last_message_id")
    private String lastMessageId;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableField("is_deleted")
    @TableLogic
    private Boolean isDeleted;
}