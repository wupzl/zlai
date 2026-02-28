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
@TableName("agent")
public class Agent {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("agent_id")
    private String agentId;

    @TableField("name")
    private String name;

    @TableField("description")
    private String description;

    @TableField("instructions")
    private String instructions;

    @TableField("model")
    private String model;

    @TableField("tool_model")
    private String toolModel;

    @TableField("user_id")
    private Long userId;

    @TableField("tools")
    private String tools;

    @TableField("multi_agent")
    private Boolean multiAgent;

    @TableField("team_agent_ids")
    private String teamAgentIds;

    @TableField("team_config")
    private String teamConfig;

    @TableField("is_public")
    private Boolean isPublic;

    @TableField("request_public")
    private Boolean requestPublic;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableField("is_deleted")
    @TableLogic
    private Boolean isDeleted;
}
