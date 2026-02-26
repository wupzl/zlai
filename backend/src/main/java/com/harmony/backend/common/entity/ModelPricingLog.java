package com.harmony.backend.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("model_pricing_log")
public class ModelPricingLog {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("model")
    private String model;

    @TableField("old_multiplier")
    private Double oldMultiplier;

    @TableField("new_multiplier")
    private Double newMultiplier;

    @TableField("updated_by")
    private Long updatedBy;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
