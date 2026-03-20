package com.harmony.backend.ai.skill;

import com.harmony.backend.ai.skill.service.AgentSkillCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Component
@RequiredArgsConstructor
public class AgentSkillRegistry {

    private final AgentSkillCatalogService agentSkillCatalogService;

    public List<AgentSkillDefinition> listAll() {
        return agentSkillCatalogService.listEnabledDefinitions();
    }

    public AgentSkillDefinition get(String key) {
        if (!StringUtils.hasText(key)) {
            return null;
        }
        return agentSkillCatalogService.getEnabledDefinition(key.trim());
    }

    public boolean isValidKey(String key) {
        return get(key) != null;
    }

    public List<String> recommendSkillsForTools(List<String> tools) {
        return agentSkillCatalogService.recommendSkillsForTools(tools);
    }

    public List<String> expandToolsForSkills(List<String> skills) {
        return agentSkillCatalogService.expandToolsForSkills(skills);
    }

    public String findSkillForTool(String toolKey, List<String> allowedSkills) {
        return agentSkillCatalogService.findSkillForTool(toolKey, allowedSkills);
    }
}
