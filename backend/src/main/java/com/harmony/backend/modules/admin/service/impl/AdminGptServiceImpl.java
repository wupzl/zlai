package com.harmony.backend.modules.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.harmony.backend.common.entity.Gpt;
import com.harmony.backend.common.mapper.GPTMapper;
import com.harmony.backend.common.response.PageResult;
import com.harmony.backend.common.util.PageResultUtils;
import com.harmony.backend.modules.admin.service.AdminGptService;
import com.harmony.backend.modules.gptstore.controller.request.GptUpsertRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AdminGptServiceImpl extends ServiceImpl<GPTMapper, Gpt> implements AdminGptService {

    @Override
    public PageResult<Gpt> listGpts(int page, int size, String keyword) {
        Page<Gpt> pageResult = new Page<>(page, size);
        LambdaQueryWrapper<Gpt> query = new LambdaQueryWrapper<>();
        query.eq(Gpt::getIsDeleted, false);
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
        return baseMapper.updateById(gpt) > 0;
    }

    @Override
    public boolean deleteGpt(Long id) {
        if (id == null) {
            return false;
        }
        Gpt gpt = new Gpt();
        gpt.setId(id);
        gpt.setIsDeleted(true);
        return baseMapper.updateById(gpt) > 0;
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
        }
        return baseMapper.updateById(gpt) > 0;
    }

    
}
