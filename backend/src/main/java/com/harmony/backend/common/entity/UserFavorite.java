package com.harmony.backend.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
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
@TableName("user_favorite")
public class UserFavorite {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("gpt_id")
    private String gptId;

    @TableField("created_at")
    private LocalDateTime createdAt;

    public static UserFavorite of(Long userId, String gptId) {
        return UserFavorite.builder()
                .userId(userId)
                .gptId(gptId)
                .createdAt(LocalDateTime.now())
                .build();
    }
}