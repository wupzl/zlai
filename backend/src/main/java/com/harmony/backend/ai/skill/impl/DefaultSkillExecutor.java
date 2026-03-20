package com.harmony.backend.ai.skill.impl;

import com.harmony.backend.ai.skill.AgentSkillDefinition;
import com.harmony.backend.ai.skill.SkillInputField;
import com.harmony.backend.ai.skill.SkillStepDefinition;
import com.harmony.backend.ai.skill.AgentSkillRegistry;
import com.harmony.backend.ai.skill.SkillExecutionRequest;
import com.harmony.backend.ai.skill.SkillExecutionResult;
import com.harmony.backend.ai.skill.SkillExecutor;
import com.harmony.backend.ai.tool.ExecutableAgentTool;
import com.harmony.backend.ai.tool.AgentToolRegistry;
import com.harmony.backend.ai.tool.ToolExecutionRequest;
import com.harmony.backend.ai.tool.ToolExecutionResult;
import com.harmony.backend.ai.tool.ToolExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
@Slf4j
@RequiredArgsConstructor
public class DefaultSkillExecutor implements SkillExecutor {

    private final AgentSkillRegistry skillRegistry;
    private final AgentToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final ObjectMapper objectMapper;

    @Value("${app.skills.max-pipeline-steps:4}")
    private int maxPipelineSteps;

    @Override
    public SkillExecutionResult execute(SkillExecutionRequest request) {
        if (request == null || !StringUtils.hasText(request.getSkillKey())) {
            return SkillExecutionResult.fail("Skill key is required");
        }
        AgentSkillDefinition definition = skillRegistry.get(request.getSkillKey());
        if (definition == null) {
            return SkillExecutionResult.fail("Unknown skill: " + request.getSkillKey());
        }
        String validationError = validateInput(definition, request.getInput());
        if (validationError != null) {
            return SkillExecutionResult.fail(validationError);
        }
        if (definition.getToolKeys() == null || definition.getToolKeys().isEmpty()) {
            return SkillExecutionResult.fail("Skill execution not implemented");
        }
        List<SkillStepDefinition> steps = resolveSteps(definition);
        if (steps.size() > Math.max(1, maxPipelineSteps)) {
            return SkillExecutionResult.fail("Skill pipeline exceeds max steps");
        }
        log.info("Skill execution start: skillKey={}, stepCount={}", definition.getKey(), steps.size());
        if ("pipeline".equalsIgnoreCase(definition.getExecutionMode())) {
            return executePipeline(definition, request, steps);
        }
        return executeSingleTool(definition, request, steps);
    }

    private SkillExecutionResult executeSingleTool(AgentSkillDefinition definition,
                                                   SkillExecutionRequest request,
                                                   List<SkillStepDefinition> steps) {
        SkillStepDefinition step = steps.get(0);
        String toolKey = step.getToolKey();
        ToolExecutionResult toolResult = executeTool(toolKey, buildToolInput(step.getPrompt(), request.getInput()), request.getModelOverride());
        if (toolResult == null || !toolResult.isSuccess()) {
            return SkillExecutionResult.fail(toolResult != null ? toolResult.getError() : "Skill execution failed");
        }
        log.info("Skill execution success: skillKey={}, toolKey={}", definition.getKey(), toolKey);
        return SkillExecutionResult.ok(
                toolResult.getOutput(),
                toolResult.getModel(),
                toolResult.getPromptTokens(),
                toolResult.getCompletionTokens(),
                List.of(toolKey)
        );
    }

    private SkillExecutionResult executePipeline(AgentSkillDefinition definition,
                                                 SkillExecutionRequest request,
                                                 List<SkillStepDefinition> steps) {
        String currentInput = request.getInput();
        String lastModel = null;
        int promptTokens = 0;
        int completionTokens = 0;
        List<String> usedTools = new ArrayList<>();
        Set<String> visitedTools = new HashSet<>();
        for (SkillStepDefinition step : steps) {
            String toolKey = step.getToolKey();
            if (!visitedTools.add(toolKey)) {
                return SkillExecutionResult.fail("Skill pipeline attempted repeated tool execution: " + toolKey);
            }
            ToolExecutionResult toolResult = executeTool(toolKey, buildToolInput(step.getPrompt(), currentInput), request.getModelOverride());
            if (toolResult == null || !toolResult.isSuccess()) {
                return SkillExecutionResult.fail(toolResult != null ? toolResult.getError() : "Skill pipeline failed");
            }
            currentInput = toolResult.getOutput();
            lastModel = toolResult.getModel();
            promptTokens += toolResult.getPromptTokens() == null ? 0 : toolResult.getPromptTokens();
            completionTokens += toolResult.getCompletionTokens() == null ? 0 : toolResult.getCompletionTokens();
            usedTools.add(toolKey);
        }
        log.info("Skill pipeline success: skillKey={}, usedTools={}", definition.getKey(), usedTools);
        return SkillExecutionResult.ok(
                currentInput,
                lastModel,
                promptTokens,
                completionTokens,
                usedTools
        );
    }

    private List<SkillStepDefinition> resolveSteps(AgentSkillDefinition definition) {
        if (definition.getStepConfig() != null && !definition.getStepConfig().isEmpty()) {
            return definition.getStepConfig();
        }
        return definition.getToolKeys().stream()
                .map(toolKey -> new SkillStepDefinition(toolKey, null))
                .toList();
    }

    private String buildToolInput(String prompt, String input) {
        if (!StringUtils.hasText(prompt)) {
            return input;
        }
        if (!StringUtils.hasText(input)) {
            return prompt.trim();
        }
        return prompt.trim() + "\n\nInput:\n" + input;
    }

    private ToolExecutionResult executeTool(String toolKey, String input, String modelOverride) {
        ToolExecutionRequest toolRequest = new ToolExecutionRequest(toolKey, input, modelOverride);
        ExecutableAgentTool executableTool = toolRegistry.getExecutable(toolKey);
        if (executableTool != null) {
            return executableTool.execute(toolRequest);
        }
        return toolExecutor.execute(toolRequest);
    }

    private String validateInput(AgentSkillDefinition definition, String input) {
        List<SkillInputField> inputSchema = definition.getInputSchema();
        if (inputSchema == null || inputSchema.isEmpty()) {
            return null;
        }
        if (!StringUtils.hasText(input)) {
            boolean required = inputSchema.stream().anyMatch(field -> Boolean.TRUE.equals(field.getRequired()));
            return required ? "Skill input is required" : null;
        }
        try {
            JsonNode root = objectMapper.readTree(input);
            if (!root.isObject()) {
                return "Skill input must be a JSON object";
            }
            for (SkillInputField field : inputSchema) {
                if (field == null || !StringUtils.hasText(field.getKey())) {
                    continue;
                }
                JsonNode node = root.get(field.getKey());
                if ((node == null || node.isNull()) && Boolean.TRUE.equals(field.getRequired())) {
                    return "Missing required input field: " + field.getKey();
                }
            }
            return null;
        } catch (Exception e) {
            return "Skill input must be valid JSON matching inputSchema";
        }
    }
}
