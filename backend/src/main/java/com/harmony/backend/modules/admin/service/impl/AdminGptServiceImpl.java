package com.harmony.backend.modules.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.harmony.backend.common.entity.Gpt;
import com.harmony.backend.common.entity.Message;
import com.harmony.backend.common.entity.Session;
import com.harmony.backend.common.mapper.GPTMapper;
import com.harmony.backend.common.mapper.MessageMapper;
import com.harmony.backend.common.mapper.SessionMapper;
import com.harmony.backend.common.response.PageResult;
import com.harmony.backend.common.util.PageResultUtils;
import com.harmony.backend.modules.admin.service.AdminGptService;
import com.harmony.backend.modules.gptstore.controller.request.GptUpsertRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
@Service
@RequiredArgsConstructor
public class AdminGptServiceImpl extends ServiceImpl<GPTMapper, Gpt> implements AdminGptService {

    private final SessionMapper sessionMapper;
    private final MessageMapper messageMapper;

    @Override
    public PageResult<Gpt> listGpts(int page, int size, String keyword, Boolean requestPublic) {
        Page<Gpt> pageResult = new Page<>(page, size);
        LambdaQueryWrapper<Gpt> query = new LambdaQueryWrapper<>();
        query.eq(Gpt::getIsDeleted, false);
        if (requestPublic != null) {
            query.eq(Gpt::getRequestPublic, requestPublic);
        }
        if (StringUtils.hasText(keyword)) {
            query.and(q -> q.like(Gpt::getName, keyword)
                    .or()
                    .like(Gpt::getCategory, keyword));
        }
        query.orderByDesc(Gpt::getCreatedAt);
        Page<Gpt> result = baseMapper.selectPage(pageResult, query);
        long total = baseMapper.selectCount(query);
        result.setTotal(total);
        return PageResultUtils.from(result);
    }

    @Override
    public boolean updatePublic(Long id, boolean isPublic) {
        if (id == null) {
            return false;
        }
        Gpt gpt = new Gpt();
        gpt.setId(id);
        gpt.setIsPublic(isPublic);
        gpt.setRequestPublic(false);
        return baseMapper.updateById(gpt) > 0;
    }

    @Override
    public boolean deleteGpt(Long id) {
        if (id == null) {
            return false;
        }
        Gpt gpt = baseMapper.selectById(id);
        if (gpt == null) {
            return false;
        }
        int rows = baseMapper.deleteById(id);
        boolean ok = rows > 0;
        if (ok) {
            baseMapper.update(
                    null,
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Gpt>()
                            .eq(Gpt::getId, id)
                            .set(Gpt::getIsPublic, false)
                            .set(Gpt::getRequestPublic, false)
                            .set(Gpt::getUpdatedAt, LocalDateTime.now())
            );
        }
        if (ok && StringUtils.hasText(gpt.getGptId())) {
            cleanupGptSessions(gpt.getGptId());
        }
        return ok;
    }

    private void cleanupGptSessions(String gptId) {
        List<Session> sessions = sessionMapper.selectList(new LambdaQueryWrapper<Session>()
                .eq(Session::getGptId, gptId)
                .eq(Session::getIsDeleted, false));
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        List<String> chatIds = sessions.stream()
                .map(Session::getChatId)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        sessionMapper.update(
                null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Session>()
                        .eq(Session::getGptId, gptId)
                        .eq(Session::getIsDeleted, false)
                        .set(Session::getIsDeleted, true)
                        .set(Session::getUpdatedAt, LocalDateTime.now())
        );
        if (!chatIds.isEmpty()) {
            messageMapper.update(
                    null,
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Message>()
                            .in(Message::getChatId, chatIds)
                            .set(Message::getIsDeleted, true)
                            .set(Message::getUpdatedAt, LocalDateTime.now())
            );
        }
    }

    @Override
    public Gpt getDetail(Long id) {
        if (id == null) {
            return null;
        }
        return baseMapper.selectById(id);
    }

    @Override
    public boolean updateGpt(Long id, GptUpsertRequest request) {
        if (id == null || request == null) {
            return false;
        }
        Gpt gpt = new Gpt();
        gpt.setId(id);
        if (StringUtils.hasText(request.getName())) {
            gpt.setName(request.getName());
        }
        if (StringUtils.hasText(request.getDescription())) {
            gpt.setDescription(request.getDescription());
        }
        if (StringUtils.hasText(request.getInstructions())) {
            gpt.setInstructions(request.getInstructions());
        }
        if (StringUtils.hasText(request.getModel())) {
            gpt.setModel(request.getModel());
        }
        if (StringUtils.hasText(request.getAvatarUrl())) {
            gpt.setAvatarUrl(request.getAvatarUrl());
        }
        if (StringUtils.hasText(request.getCategory())) {
            gpt.setCategory(request.getCategory());
        }
        if (request.getRequestPublic() != null) {
            gpt.setIsPublic(request.getRequestPublic());
            gpt.setRequestPublic(false);
        }
        return baseMapper.updateById(gpt) > 0;
    }

    
}
