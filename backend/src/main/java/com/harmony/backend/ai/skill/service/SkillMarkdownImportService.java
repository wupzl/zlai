package com.harmony.backend.ai.skill.service;

import com.harmony.backend.ai.skill.model.SkillMarkdownImportRequest;
import com.harmony.backend.ai.skill.model.SkillVO;

public interface SkillMarkdownImportService {
    SkillVO importMarkdown(SkillMarkdownImportRequest request, Long adminId);
}
