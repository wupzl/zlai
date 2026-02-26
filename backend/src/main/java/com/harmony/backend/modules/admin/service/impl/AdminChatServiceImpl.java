package com.harmony.backend.modules.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.harmony.backend.common.entity.Message;
import com.harmony.backend.common.entity.Session;
import com.harmony.backend.common.mapper.MessageMapper;
import com.harmony.backend.common.mapper.SessionMapper;
import com.harmony.backend.common.response.PageResult;
import com.harmony.backend.common.util.PageResultUtils;
import com.harmony.backend.modules.admin.service.AdminChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminChatServiceImpl extends ServiceImpl<SessionMapper, Session> implements AdminChatService {

    private final MessageMapper messageMapper;

    @Override
    public PageResult<Session> listSessions(Long userId, String keyword, int page, int size) {
        Page<Session> pageResult = new Page<>(page, size);
        LambdaQueryWrapper<Session> query = new LambdaQueryWrapper<>();
        if (userId != null) {
            query.eq(Session::getUserId, userId);
        }
        if (keyword != null && !keyword.isBlank()) {
            query.and(q -> q.like(Session::getTitle, keyword)
                    .or()
                    .like(Session::getChatId, keyword));
        }
        query.eq(Session::getIsDeleted, false)
                .orderByDesc(Session::getLastActiveTime);
        Page<Session> result = baseMapper.selectPage(pageResult, query);
        long total = baseMapper.selectCount(query);
        result.setTotal(total);
        return PageResultUtils.from(result);
    }

    @Override
    public PageResult<Message> listMessages(String chatId, int page, int size) {
        Page<Message> pageResult = new Page<>(page, size);
        LambdaQueryWrapper<Message> query = new LambdaQueryWrapper<>();
        query.eq(Message::getChatId, chatId)
                .orderByAsc(Message::getCreatedAt);
        Page<Message> result = messageMapper.selectPage(pageResult, query);
        long total = messageMapper.selectCount(query);
        result.setTotal(total);
        return PageResultUtils.from(result);
    }

    @Override
    public Session getSessionDetail(String chatId) {
        if (chatId == null || chatId.isBlank()) {
            return null;
        }
        return baseMapper.selectOne(new LambdaQueryWrapper<Session>()
                .eq(Session::getChatId, chatId)
                .eq(Session::getIsDeleted, false));
    }

    @Override
    public Message getMessageDetail(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return null;
        }
        return messageMapper.selectOne(new LambdaQueryWrapper<Message>()
                .eq(Message::getMessageId, messageId));
    }
}
