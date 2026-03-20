package com.harmony.backend.ai.tool;

import com.harmony.backend.ai.tool.impl.BasicToolHandler;
import com.harmony.backend.ai.tool.impl.CalculatorTool;
import com.harmony.backend.ai.tool.impl.DateTimeTool;
import com.harmony.backend.ai.tool.impl.LlmTextToolHandler;
import com.harmony.backend.ai.tool.impl.SummarizeTool;
import com.harmony.backend.ai.tool.impl.TranslationTool;
import com.harmony.backend.ai.tool.impl.WebSearchTool;
import com.harmony.backend.ai.tool.impl.WebSearchToolHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BuiltinExecutableToolTest {

    @Test
    void calculatorTool_should_delegate_execution_to_basic_handler_with_its_own_key() {
        BasicToolHandler handler = mock(BasicToolHandler.class);
        CalculatorTool tool = new CalculatorTool(handler);
        when(handler.execute(org.mockito.ArgumentMatchers.any())).thenReturn(ToolExecutionResult.ok("21"));

        ToolExecutionResult result = tool.execute(new ToolExecutionRequest("wrong_key", "3*7", "model-a"));
        ArgumentCaptor<ToolExecutionRequest> captor = ArgumentCaptor.forClass(ToolExecutionRequest.class);
        verify(handler).execute(captor.capture());

        assertEquals("21", result.getOutput());
        assertEquals("calculator", captor.getValue().getToolKey());
        assertEquals("3*7", captor.getValue().getInput());
        assertEquals("model-a", captor.getValue().getModel());
        assertTrue(tool instanceof ExecutableAgentTool);
        assertFalse(tool.getParametersSchema().isEmpty());
    }

    @Test
    void dateTimeTool_should_delegate_execution_to_basic_handler_with_its_own_key() {
        BasicToolHandler handler = mock(BasicToolHandler.class);
        DateTimeTool tool = new DateTimeTool(handler);
        when(handler.execute(org.mockito.ArgumentMatchers.any())).thenReturn(ToolExecutionResult.ok("2026-03-19T12:00:00"));

        ToolExecutionResult result = tool.execute(new ToolExecutionRequest("wrong_key", "Asia/Shanghai", null));
        ArgumentCaptor<ToolExecutionRequest> captor = ArgumentCaptor.forClass(ToolExecutionRequest.class);
        verify(handler).execute(captor.capture());

        assertEquals("2026-03-19T12:00:00", result.getOutput());
        assertEquals("datetime", captor.getValue().getToolKey());
    }

    @Test
    void summarizeTool_should_delegate_execution_to_llm_text_handler_with_its_own_key() {
        LlmTextToolHandler handler = mock(LlmTextToolHandler.class);
        SummarizeTool tool = new SummarizeTool(handler);
        when(handler.execute(org.mockito.ArgumentMatchers.any())).thenReturn(ToolExecutionResult.ok("summary"));

        ToolExecutionResult result = tool.execute(new ToolExecutionRequest("wrong_key", "long text", "tool-model"));
        ArgumentCaptor<ToolExecutionRequest> captor = ArgumentCaptor.forClass(ToolExecutionRequest.class);
        verify(handler).execute(captor.capture());

        assertEquals("summary", result.getOutput());
        assertEquals("summarize", captor.getValue().getToolKey());
        assertNotNull(tool.getParametersSchema().get("required"));
    }

    @Test
    void translationTool_should_delegate_execution_to_llm_text_handler_with_its_own_key() {
        LlmTextToolHandler handler = mock(LlmTextToolHandler.class);
        TranslationTool tool = new TranslationTool(handler);
        when(handler.execute(org.mockito.ArgumentMatchers.any())).thenReturn(ToolExecutionResult.ok("你好"));

        ToolExecutionResult result = tool.execute(new ToolExecutionRequest("wrong_key", "hello", "tool-model"));
        ArgumentCaptor<ToolExecutionRequest> captor = ArgumentCaptor.forClass(ToolExecutionRequest.class);
        verify(handler).execute(captor.capture());

        assertEquals("你好", result.getOutput());
        assertEquals("translate", captor.getValue().getToolKey());
    }

    @Test
    void webSearchTool_should_delegate_execution_to_search_handler_with_its_own_key() {
        WebSearchToolHandler handler = mock(WebSearchToolHandler.class);
        WebSearchTool tool = new WebSearchTool(handler);
        when(handler.execute(org.mockito.ArgumentMatchers.any())).thenReturn(ToolExecutionResult.ok("search result"));

        ToolExecutionResult result = tool.execute(new ToolExecutionRequest("wrong_key", "latest redis docs", null));
        ArgumentCaptor<ToolExecutionRequest> captor = ArgumentCaptor.forClass(ToolExecutionRequest.class);
        verify(handler).execute(captor.capture());

        assertEquals("search result", result.getOutput());
        assertEquals("web_search", captor.getValue().getToolKey());
    }
}
