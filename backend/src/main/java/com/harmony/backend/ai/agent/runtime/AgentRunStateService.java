package com.harmony.backend.ai.agent.runtime;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.harmony.backend.common.entity.AgentRun;
import com.harmony.backend.common.entity.AgentRunStep;
import com.harmony.backend.common.mapper.AgentRunMapper;
import com.harmony.backend.common.mapper.AgentRunStepMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class AgentRunStateService {

    private final AgentRunMapper agentRunMapper;
    private final AgentRunStepMapper agentRunStepMapper;

    public AgentRunStateService(AgentRunMapper agentRunMapper, AgentRunStepMapper agentRunStepMapper) {
        this.agentRunMapper = agentRunMapper;
        this.agentRunStepMapper = agentRunStepMapper;
    }

    public AgentRun createPlannedRun(Long userId,
                                     String chatId,
                                     String assistantMessageId,
                                     String managerAgentId,
                                     String model,
                                     String goal) {
        AgentRun run = AgentRun.builder()
                .executionId(UUID.randomUUID().toString())
                .userId(userId)
                .chatId(chatId)
                .assistantMessageId(assistantMessageId)
                .managerAgentId(managerAgentId)
                .goal(goal)
                .status(AgentRunStatus.PLANNED)
                .currentStep("planned")
                .model(model)
                .stepCount(0)
                .toolCallCount(0)
                .cancelRequested(false)
                .startedAt(LocalDateTime.now())
                .build();
        agentRunMapper.insert(run);
        return run;
    }

    public AgentRun findByExecutionId(String executionId) {
        if (executionId == null || executionId.isBlank()) {
            return null;
        }
        return agentRunMapper.selectOne(Wrappers.<AgentRun>lambdaQuery().eq(AgentRun::getExecutionId, executionId).last("LIMIT 1"));
    }

    public boolean isCancellationRequested(String executionId) {
        AgentRun run = findByExecutionId(executionId);
        return run != null && Boolean.TRUE.equals(run.getCancelRequested());
    }

    public void requestCancellation(String executionId) {
        AgentRun run = findByExecutionId(executionId);
        if (run == null || run.getId() == null) {
            return;
        }
        run.setCancelRequested(true);
        agentRunMapper.updateById(run);
    }

    public void markRunRunning(AgentRun run,
                               String currentStep,
                               String planSummaryJson,
                               String checkpointJson,
                               int stepCount,
                               int toolCallCount) {
        if (run == null || run.getId() == null) {
            return;
        }
        run.setStatus(AgentRunStatus.RUNNING);
        run.setCurrentStep(currentStep);
        run.setPlanSummaryJson(planSummaryJson);
        run.setCheckpointJson(checkpointJson);
        run.setWaitReason(null);
        run.setStepCount(stepCount);
        run.setToolCallCount(toolCallCount);
        agentRunMapper.updateById(run);
    }

    public void markRunWaiting(AgentRun run,
                               String currentStep,
                               String waitReason,
                               int stepCount,
                               int toolCallCount,
                               String checkpointJson,
                               String planSummaryJson) {
        if (run == null || run.getId() == null) {
            return;
        }
        run.setStatus(AgentRunStatus.WAITING);
        run.setCurrentStep(currentStep);
        run.setWaitReason(waitReason);
        run.setStepCount(stepCount);
        run.setToolCallCount(toolCallCount);
        run.setCheckpointJson(checkpointJson);
        run.setPlanSummaryJson(planSummaryJson);
        agentRunMapper.updateById(run);
    }

    public void markRunResumed(AgentRun run,
                               String currentStep,
                               int stepCount,
                               int toolCallCount,
                               String checkpointJson) {
        if (run == null || run.getId() == null) {
            return;
        }
        run.setStatus(AgentRunStatus.RESUMED);
        run.setCurrentStep(currentStep);
        run.setWaitReason(null);
        run.setStepCount(stepCount);
        run.setToolCallCount(toolCallCount);
        run.setCheckpointJson(checkpointJson);
        agentRunMapper.updateById(run);
    }

    public void markRunCompleted(AgentRun run,
                                 String currentStep,
                                 String finalOutput,
                                 int stepCount,
                                 int toolCallCount,
                                 String checkpointJson,
                                 String planSummaryJson) {
        if (run == null || run.getId() == null) {
            return;
        }
        run.setStatus(AgentRunStatus.COMPLETED);
        run.setCurrentStep(currentStep);
        run.setFinalOutput(finalOutput);
        run.setStepCount(stepCount);
        run.setToolCallCount(toolCallCount);
        run.setCheckpointJson(checkpointJson);
        run.setPlanSummaryJson(planSummaryJson);
        run.setWaitReason(null);
        run.setCompletedAt(LocalDateTime.now());
        agentRunMapper.updateById(run);
    }

    public void markRunFailed(AgentRun run,
                              String currentStep,
                              String errorMessage,
                              int stepCount,
                              int toolCallCount,
                              String checkpointJson,
                              String planSummaryJson) {
        if (run == null || run.getId() == null) {
            return;
        }
        run.setStatus(AgentRunStatus.FAILED);
        run.setCurrentStep(currentStep);
        run.setErrorMessage(errorMessage);
        run.setStepCount(stepCount);
        run.setToolCallCount(toolCallCount);
        run.setCheckpointJson(checkpointJson);
        run.setPlanSummaryJson(planSummaryJson);
        run.setCompletedAt(LocalDateTime.now());
        agentRunMapper.updateById(run);
    }

    public void markRunCancelled(AgentRun run,
                                 String currentStep,
                                 String errorMessage,
                                 int stepCount,
                                 int toolCallCount,
                                 String checkpointJson,
                                 String planSummaryJson) {
        if (run == null || run.getId() == null) {
            return;
        }
        run.setStatus(AgentRunStatus.CANCELLED);
        run.setCurrentStep(currentStep);
        run.setErrorMessage(errorMessage);
        run.setStepCount(stepCount);
        run.setToolCallCount(toolCallCount);
        run.setCheckpointJson(checkpointJson);
        run.setPlanSummaryJson(planSummaryJson);
        run.setCompletedAt(LocalDateTime.now());
        agentRunMapper.updateById(run);
    }

    public AgentRunStep createRunningStep(AgentRun run,
                                          int stepOrder,
                                          String stepKey,
                                          String agentId,
                                          String inputSummary,
                                          String artifactsJson) {
        AgentRunStep step = AgentRunStep.builder()
                .executionId(run != null ? run.getExecutionId() : null)
                .runId(run != null ? run.getId() : null)
                .stepOrder(stepOrder)
                .stepKey(stepKey)
                .agentId(agentId)
                .status(AgentRunStepStatus.RUNNING)
                .inputSummary(inputSummary)
                .artifactsJson(artifactsJson)
                .startedAt(LocalDateTime.now())
                .build();
        agentRunStepMapper.insert(step);
        return step;
    }

    public void markStepCompleted(AgentRunStep step, String outputSummary, String artifactsJson) {
        if (step == null || step.getId() == null) {
            return;
        }
        step.setStatus(AgentRunStepStatus.COMPLETED);
        step.setOutputSummary(outputSummary);
        step.setArtifactsJson(artifactsJson);
        step.setCompletedAt(LocalDateTime.now());
        agentRunStepMapper.updateById(step);
    }

    public void markStepFailed(AgentRunStep step, String errorMessage, String artifactsJson) {
        if (step == null || step.getId() == null) {
            return;
        }
        step.setStatus(AgentRunStepStatus.FAILED);
        step.setErrorMessage(errorMessage);
        step.setArtifactsJson(artifactsJson);
        step.setCompletedAt(LocalDateTime.now());
        agentRunStepMapper.updateById(step);
    }
}
