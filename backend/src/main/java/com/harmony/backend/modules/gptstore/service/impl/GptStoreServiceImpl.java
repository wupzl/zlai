package com.harmony.backend.modules.gptstore.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.harmony.backend.common.entity.Gpt;
import com.harmony.backend.common.entity.Message;
import com.harmony.backend.common.entity.Session;
import com.harmony.backend.common.exception.BusinessException;
import com.harmony.backend.common.mapper.GPTMapper;
import com.harmony.backend.common.mapper.MessageMapper;
import com.harmony.backend.common.mapper.SessionMapper;
import com.harmony.backend.common.response.PageResult;
import com.harmony.backend.common.util.PageResultUtils;
import com.harmony.backend.modules.gptstore.controller.request.GptUpsertRequest;
import com.harmony.backend.modules.gptstore.service.GptStoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GptStoreServiceImpl extends ServiceImpl<GPTMapper, Gpt> implements GptStoreService {

    private final SessionMapper sessionMapper;
    private final MessageMapper messageMapper;

    @Override
    public PageResult<Gpt> listPublic(int page, int size, String keyword, String category) {
        Page<Gpt> pageResult = new Page<>(page, size);
        LambdaQueryWrapper<Gpt> query = new LambdaQueryWrapper<>();
        query.eq(Gpt::getIsDeleted, false)
                .eq(Gpt::getIsPublic, true);
        if (StringUtils.hasText(keyword)) {
            query.and(q -> q.like(Gpt::getName, keyword)
                    .or()
                    .like(Gpt::getDescription, keyword)
                    .or()
                    .like(Gpt::getCategory, keyword));
        }
        if (StringUtils.hasText(category)) {
            query.eq(Gpt::getCategory, category);
        }
        query.orderByDesc(Gpt::getCreatedAt);
        Page<Gpt> result = baseMapper.selectPage(pageResult, query);
        long total = baseMapper.selectCount(query);
        result.setTotal(total);
        return PageResultUtils.from(result);
    }

    @Override
    public PageResult<Gpt> listMine(Long userId, int page, int size) {
        if (userId == null) {
            throw new BusinessException(401, "Unauthorized");
        }
        Page<Gpt> pageResult = new Page<>(page, size);
        LambdaQueryWrapper<Gpt> query = new LambdaQueryWrapper<>();
        query.eq(Gpt::getIsDeleted, false)
                .eq(Gpt::getUserId, userId)
                .orderByDesc(Gpt::getCreatedAt);
        Page<Gpt> result = baseMapper.selectPage(pageResult, query);
        long total = baseMapper.selectCount(query);
        result.setTotal(total);
        return PageResultUtils.from(result);
    }

    @Override
    public Gpt getPublicDetail(String gptId) {
        Gpt gpt = findByGptId(gptId);
        if (gpt == null || !Boolean.TRUE.equals(gpt.getIsPublic())) {
            throw new BusinessException(404, "GPT not found");
        }
        return gpt;
    }

    @Override
    public Gpt getDetail(String gptId, Long userId, boolean isAdmin) {
        Gpt gpt = findByGptId(gptId);
        if (gpt == null) {
            throw new BusinessException(404, "GPT not found");
        }
        if (Boolean.TRUE.equals(gpt.getIsPublic())) {
            return gpt;
        }
        if (isAdmin || (userId != null && userId.equals(gpt.getUserId()))) {
            return gpt;
        }
        throw new BusinessException(404, "GPT not found");
    }

    @Override
    public Gpt create(Long userId, boolean isAdmin, GptUpsertRequest request) {
        if (userId == null) {
            throw new BusinessException(401, "Unauthorized");
        }
        validateRequest(request);
        Gpt gpt = new Gpt();
        gpt.setGptId(UUID.randomUUID().toString());
        gpt.setName(request.getName().trim());
        gpt.setDescription(trimOrNull(request.getDescription()));
        gpt.setInstructions(trimOrNull(request.getInstructions()));
        gpt.setModel(resolveModel(request.getModel()));
        gpt.setAvatarUrl(trimOrNull(request.getAvatarUrl()));
        gpt.setCategory(resolveCategory(request.getCategory()));
        gpt.setUserId(userId);
        gpt.setUsageCount(0);

        boolean requestPublic = Boolean.TRUE.equals(request.getRequestPublic());
        if (isAdmin) {
            gpt.setIsPublic(requestPublic);
            gpt.setRequestPublic(false);
        } else {
            gpt.setIsPublic(false);
            gpt.setRequestPublic(requestPublic);
        }

        baseMapper.insert(gpt);
        return gpt;
    }

    @Override
    public Gpt update(String gptId, Long userId, boolean isAdmin, GptUpsertRequest request) {
        if (userId == null) {
            throw new BusinessException(401, "Unauthorized");
        }
        Gpt gpt = findByGptId(gptId);
        if (gpt == null) {
            throw new BusinessException(404, "GPT not found");
        }
        if (!isAdmin && !userId.equals(gpt.getUserId())) {
            throw new BusinessException(403, "Forbidden");
        }
        if (!isAdmin && Boolean.TRUE.equals(gpt.getIsPublic())) {
            throw new BusinessException(403, "Public GPT can only be updated by admin");
        }
        boolean updated = applyUpdates(gpt, request);

        boolean requestPublic = Boolean.TRUE.equals(request.getRequestPublic());
        if (isAdmin) {
            if (request.getRequestPublic() != null) {
                gpt.setIsPublic(requestPublic);
                gpt.setRequestPublic(false);
            }
        } else if (request.getRequestPublic() != null) {
            gpt.setIsPublic(false);
            gpt.setRequestPublic(requestPublic);
        }

        baseMapper.updateById(gpt);
        return gpt;
    }

    @Override
    public boolean delete(String gptId, Long userId, boolean isAdmin) {
        if (userId == null) {
            throw new BusinessException(401, "Unauthorized");
        }
        Gpt gpt = findByGptId(gptId);
        if (gpt == null) {
            throw new BusinessException(404, "GPT not found");
        }
        if (!isAdmin && !userId.equals(gpt.getUserId())) {
            throw new BusinessException(403, "Forbidden");
        }
        if (!isAdmin && Boolean.TRUE.equals(gpt.getIsPublic())) {
            throw new BusinessException(403, "Public GPT can only be deleted by admin");
        }
        int rows = baseMapper.delete(new LambdaQueryWrapper<Gpt>()
                .eq(Gpt::getGptId, gptId));
        boolean ok = rows > 0;
        if (ok) {
            baseMapper.update(
                    null,
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Gpt>()
                            .eq(Gpt::getGptId, gptId)
                            .set(Gpt::getIsPublic, false)
                            .set(Gpt::getRequestPublic, false)
                            .set(Gpt::getUpdatedAt, LocalDateTime.now())
            );
        }
        if (ok) {
            cleanupGptSessions(gptId);
        }
        return ok;
    }

    private void cleanupGptSessions(String gptId) {
        if (!StringUtils.hasText(gptId)) {
            return;
        }
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

    private boolean applyUpdates(Gpt gpt, GptUpsertRequest request) {
        boolean updated = false;
        if (StringUtils.hasText(request.getName())) {
            gpt.setName(request.getName().trim());
            updated = true;
        }
        if (request.getDescription() != null) {
            gpt.setDescription(trimOrNull(request.getDescription()));
            updated = true;
        }
        if (request.getInstructions() != null) {
            gpt.setInstructions(trimOrNull(request.getInstructions()));
            updated = true;
        }
        if (request.getModel() != null) {
            gpt.setModel(resolveModel(request.getModel()));
            updated = true;
        }
        if (request.getAvatarUrl() != null) {
            gpt.setAvatarUrl(trimOrNull(request.getAvatarUrl()));
            updated = true;
        }
        if (request.getCategory() != null) {
            gpt.setCategory(resolveCategory(request.getCategory()));
            updated = true;
        }
        return updated;
    }

    private void validateRequest(GptUpsertRequest request) {
        if (request == null || !StringUtils.hasText(request.getName())) {
            throw new BusinessException(400, "GPT name is required");
        }
        if (!StringUtils.hasText(request.getInstructions())) {
            throw new BusinessException(400, "GPT instructions are required");
        }
    }

    private String resolveModel(String model) {
        if (!StringUtils.hasText(model)) {
            return "deepseek-chat";
        }
        return model.trim();
    }

    private String resolveCategory(String category) {
        if (!StringUtils.hasText(category)) {
            return "general";
        }
        return category.trim();
    }

    private String trimOrNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private Gpt findByGptId(String gptId) {
        if (!StringUtils.hasText(gptId)) {
            return null;
        }
        return baseMapper.selectOne(new LambdaQueryWrapper<Gpt>()
                .eq(Gpt::getGptId, gptId)
                .eq(Gpt::getIsDeleted, false));
    }

    
}
