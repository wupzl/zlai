package com.harmony.backend.modules.chat.adapter;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LlmMessage {
    private String role;
    private String content;
}
