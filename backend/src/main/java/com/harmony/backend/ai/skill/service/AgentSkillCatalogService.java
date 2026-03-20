package com.harmony.backend.ai.skill.service;

import com.harmony.backend.ai.skill.AgentSkillDefinition;
import com.harmony.backend.ai.skill.model.SkillUpsertRequest;
import com.harmony.backend.ai.skill.model.SkillVO;

import java.util.List;

public interface AgentSkillCatalogService {
    List<AgentSkillDefinition> listEnabledDefinitions();

    List<SkillVO> listManagedSkills();

    AgentSkillDefinition getEnabledDefinition(String key);

    List<String> expandToolsForSkills(List<String> skills);

    List<String> recommendSkillsForTools(List<String> tools);

    String findSkillForTool(String toolKey, List<String> allowedSkills);

    SkillVO create(SkillUpsertRequest request, Long adminId);

    SkillVO update(String skillKey, SkillUpsertRequest request, Long adminId);

    boolean delete(String skillKey);
}
