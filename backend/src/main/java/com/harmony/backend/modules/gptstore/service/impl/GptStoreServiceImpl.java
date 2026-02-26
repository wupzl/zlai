package com.harmony.backend.modules.gptstore.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.harmony.backend.common.entity.Gpt;
import com.harmony.backend.common.exception.BusinessException;
import com.harmony.backend.common.mapper.GPTMapper;
import com.harmony.backend.common.response.PageResult;
import com.harmony.backend.common.util.PageResultUtils;
import com.harmony.backend.modules.gptstore.controller.request.GptUpsertRequest;
import com.harmony.backend.modules.gptstore.service.GptModerationService;
import com.harmony.backend.modules.gptstore.service.GptStoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GptStoreServiceImpl extends ServiceImpl<GPTMapper, Gpt> implements GptStoreService {

    private final GptModerationService moderationService;

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
        } else {
            gpt.setIsPublic(requestPublic && moderationService.shouldAutoApprove(gpt));
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
        boolean updated = applyUpdates(gpt, request);

        boolean requestPublic = Boolean.TRUE.equals(request.getRequestPublic());
        if (isAdmin) {
            if (request.getRequestPublic() != null) {
                gpt.setIsPublic(requestPublic);
            }
        } else if (updated || request.getRequestPublic() != null) {
            gpt.setIsPublic(requestPublic && moderationService.shouldAutoApprove(gpt));
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
        Gpt update = new Gpt();
        update.setId(gpt.getId());
        update.setIsDeleted(true);
        return baseMapper.updateById(update) > 0;
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
