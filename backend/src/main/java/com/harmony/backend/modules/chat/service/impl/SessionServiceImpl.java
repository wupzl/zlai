package com.harmony.backend.modules.chat.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.harmony.backend.common.entity.Session;
import com.harmony.backend.common.mapper.SessionMapper;
import com.harmony.backend.modules.chat.controller.response.ChatSessionVO;
import com.harmony.backend.modules.chat.service.SessionService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class SessionServiceImpl extends ServiceImpl<SessionMapper, Session> implements SessionService {
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public boolean checkChatBelong(Long userId, String chatId) {
        if (userId == null || chatId == null || chatId.isEmpty()) {
            return false;
        }
        return lambdaQuery()
                .eq(Session::getUserId, userId)
                .eq(Session::getChatId, chatId)
                .eq(Session::getIsDeleted, false)
                .count() > 0;
    }

    @Override
    public List<ChatSessionVO> getChatSessionsByUserId(Long userId, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(size, 1);
        int offset = (safePage - 1) * safeSize;

        List<Session> sessions = lambdaQuery()
                .eq(Session::getUserId, userId)
                .eq(Session::getIsDeleted, false)
                .orderByDesc(Session::getLastActiveTime)
                .last("LIMIT " + safeSize + " OFFSET " + offset)
                .list();

        return sessions.stream()
                .map(this::toVo)
                .collect(Collectors.toList());
    }

    @Override
    public long countChatSessionsByUserId(Long userId) {
        if (userId == null) {
            return 0L;
        }
        return lambdaQuery()
                .eq(Session::getUserId, userId)
                .eq(Session::getIsDeleted, false)
                .count();
    }

    @Override
    public ChatSessionVO createSession(Long userId, String title, String model, String toolModel) {
        return createSessionInternal(userId, title, model, toolModel, false);
    }

    @Override
    public ChatSessionVO createRagSession(Long userId, String title, String model, String toolModel) {
        return createSessionInternal(userId, title, model, toolModel, true);
    }

    @Override
    public boolean renameSession(Long userId, String chatId, String title) {
        if (userId == null || chatId == null || chatId.isBlank()) {
            return false;
        }
        String finalTitle = title == null ? "" : title.trim();
        if (finalTitle.isBlank()) {
            return false;
        }
        return lambdaUpdate()
                .eq(Session::getUserId, userId)
                .eq(Session::getChatId, chatId)
                .eq(Session::getIsDeleted, false)
                .set(Session::getTitle, finalTitle)
                .set(Session::getUpdatedAt, LocalDateTime.now())
                .update();
    }

    @Override
    public boolean deleteSession(Long userId, String chatId) {
        if (userId == null || chatId == null || chatId.isBlank()) {
            return false;
        }
        return lambdaUpdate()
                .eq(Session::getUserId, userId)
                .eq(Session::getChatId, chatId)
                .eq(Session::getIsDeleted, false)
                .set(Session::getIsDeleted, true)
                .set(Session::getUpdatedAt, LocalDateTime.now())
                .update();
    }

    private ChatSessionVO toVo(Session session) {
        ChatSessionVO vo = new ChatSessionVO();
        vo.setChatId(session.getChatId());
        vo.setTitle(session.getTitle());
        vo.setModel(session.getModel());
        vo.setToolModel(session.getToolModel());
        vo.setMessageCount(session.getMessageCount());
        vo.setLastActiveTime(session.getLastActiveTime() == null
                ? null
                : session.getLastActiveTime().format(DATE_TIME_FORMATTER));
        vo.setRagEnabled(session.getRagEnabled());
        vo.setGptId(session.getGptId());
        vo.setAgentId(session.getAgentId());
        return vo;
    }

    private ChatSessionVO createSessionInternal(Long userId, String title, String model, String toolModel, boolean ragEnabled) {
        String defaultTitle = ragEnabled ? "RAG Chat" : "New Chat";
        String finalTitle = (title == null || title.isBlank()) ? defaultTitle : title;
        String finalModel = (model == null || model.isBlank()) ? "deepseek-chat" : model;

        Session session = Session.builder()
                .chatId(UUID.randomUUID().toString())
                .userId(userId)
                .title(finalTitle)
                .model(finalModel)
                .toolModel(trimOrNull(toolModel))
                .ragEnabled(ragEnabled)
                .messageCount(0)
                .totalTokens(0)
                .lastActiveTime(LocalDateTime.now())
                .build();

        save(session);
        return toVo(session);
    }

    private String trimOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
