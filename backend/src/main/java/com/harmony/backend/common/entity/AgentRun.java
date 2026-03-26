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
@TableName("agent_run")
public class AgentRun {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("execution_id")
    private String executionId;

    @TableField("user_id")
    private Long userId;

    @TableField("chat_id")
    private String chatId;

    @TableField("assistant_message_id")
    private String assistantMessageId;

    @TableField("manager_agent_id")
    private String managerAgentId;

    @TableField("goal")
    private String goal;

    @TableField("status")
    private String status;

    @TableField("current_step")
    private String currentStep;

    @TableField("model")
    private String model;

    @TableField("plan_summary_json")
    private String planSummaryJson;

    @TableField("checkpoint_json")
    private String checkpointJson;

    @TableField("wait_reason")
    private String waitReason;

    @TableField("final_output")
    private String finalOutput;

    @TableField("error_message")
    private String errorMessage;

    @TableField("step_count")
    private Integer stepCount;

    @TableField("tool_call_count")
    private Integer toolCallCount;

    @TableField("cancel_requested")
    private Boolean cancelRequested;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("completed_at")
    private LocalDateTime completedAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableField("is_deleted")
    @TableLogic
    private Boolean isDeleted;
}
