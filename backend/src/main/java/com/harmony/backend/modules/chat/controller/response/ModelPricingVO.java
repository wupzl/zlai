package com.harmony.backend.modules.chat.controller.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ModelPricingVO {
    private String model;
    private Double multiplier;
    private String updatedAt;
}
