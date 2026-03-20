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
@TableName("agent_skill")
public class AgentSkill {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("skill_key")
    private String skillKey;

    @TableField("name")
    private String name;

    @TableField("description")
    private String description;

    @TableField("tool_keys")
    private String toolKeys;

    @TableField("execution_mode")
    private String executionMode;

    @TableField("input_schema")
    private String inputSchema;

    @TableField("step_config")
    private String stepConfig;

    @TableField("enabled")
    private Boolean enabled;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableField("is_deleted")
    @TableLogic
    private Boolean isDeleted;
}
