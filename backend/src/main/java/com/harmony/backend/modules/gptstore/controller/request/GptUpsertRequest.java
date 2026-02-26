package com.harmony.backend.modules.gptstore.controller.request;

import lombok.Data;

@Data
public class GptUpsertRequest {
    private String name;
    private String description;
    private String instructions;
    private String model;
    private String avatarUrl;
    private String category;
    private Boolean requestPublic;
}
