package com.harmony.backend.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("model_pricing")
public class ModelPricing {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("model")
    private String model;

    @TableField("multiplier")
    private Double multiplier;

    @TableField("updated_by")
    private Long updatedBy;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
