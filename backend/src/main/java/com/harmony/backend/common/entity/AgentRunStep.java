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
@TableName("agent_run_step")
public class AgentRunStep {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("execution_id")
    private String executionId;

    @TableField("run_id")
    private Long runId;

    @TableField("step_order")
    private Integer stepOrder;

    @TableField("step_key")
    private String stepKey;

    @TableField("agent_id")
    private String agentId;

    @TableField("status")
    private String status;

    @TableField("input_summary")
    private String inputSummary;

    @TableField("output_summary")
    private String outputSummary;

    @TableField("error_message")
    private String errorMessage;

    @TableField("artifacts_json")
    private String artifactsJson;

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
