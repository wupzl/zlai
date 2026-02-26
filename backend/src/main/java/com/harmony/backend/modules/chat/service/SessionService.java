package com.harmony.backend.modules.chat.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.harmony.backend.common.entity.Session;
import com.harmony.backend.modules.chat.controller.response.ChatSessionVO;

import java.util.List;

public interface SessionService extends IService<Session> {
    boolean checkChatBelong(Long userId, String chatId);

    List<ChatSessionVO> getChatSessionsByUserId(Long userId, int page, int size);

    long countChatSessionsByUserId(Long userId);

    ChatSessionVO createSession(Long userId, String title, String model, String toolModel);

    ChatSessionVO createRagSession(Long userId, String title, String model, String toolModel);

    boolean renameSession(Long userId, String chatId, String title);

    boolean deleteSession(Long userId, String chatId);
}
