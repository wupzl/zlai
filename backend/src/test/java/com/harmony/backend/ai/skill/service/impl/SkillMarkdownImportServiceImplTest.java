package com.harmony.backend.ai.skill.service.impl;

import com.harmony.backend.ai.skill.AgentSkillDefinition;
import com.harmony.backend.ai.skill.model.SkillMarkdownImportRequest;
import com.harmony.backend.ai.skill.model.SkillUpsertRequest;
import com.harmony.backend.ai.skill.model.SkillVO;
import com.harmony.backend.ai.skill.service.AgentSkillCatalogService;
import com.harmony.backend.ai.tool.AgentToolDefinition;
import com.harmony.backend.ai.tool.AgentToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillMarkdownImportServiceImplTest {

    private AgentSkillCatalogService catalogService;
    private AgentToolRegistry toolRegistry;
    private SkillMarkdownImportServiceImpl importer;

    @BeforeEach
    void setUp() {
        catalogService = mock(AgentSkillCatalogService.class);
        toolRegistry = mock(AgentToolRegistry.class);
        importer = new SkillMarkdownImportServiceImpl(catalogService, toolRegistry);
        when(toolRegistry.listAll()).thenReturn(List.of(
                new AgentToolDefinition("web_search", "Web Search", "Search the web", Map.of(), true),
                new AgentToolDefinition("summarize", "Summarize", "Summarize text", Map.of(), true),
                new AgentToolDefinition("translation", "Translation", "Translate", Map.of(), true)
        ));
        when(catalogService.listManagedSkills()).thenReturn(List.of());
    }

    @Test
    void importMarkdown_should_parse_structured_skill_markdown_and_create_skill() {
        SkillMarkdownImportRequest request = new SkillMarkdownImportRequest();
        request.setMarkdownContent("""
                ---
                key: kb_summary_import
                title: KB Summary Import
                description: Import a skill from markdown.
                tools: [web_search, summarize]
                execution_mode: pipeline
                ---
                # KB Summary Import
                
                ## Inputs
                | field | type | required | description |
                | --- | --- | --- | --- |
                | query | string | required | Search query |
                
                ## Steps
                1. tool: web_search - Search for relevant sources
                2. use `summarize` to condense the findings
                """);
        SkillVO created = new SkillVO();
        created.setKey("kb_summary_import");
        when(catalogService.getEnabledDefinition("kb_summary_import")).thenReturn(null);
        when(catalogService.create(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(created);

        SkillVO result = importer.importMarkdown(request, 100L);
        ArgumentCaptor<SkillUpsertRequest> captor = ArgumentCaptor.forClass(SkillUpsertRequest.class);
        verify(catalogService).create(captor.capture(), org.mockito.ArgumentMatchers.eq(100L));
        SkillUpsertRequest upsert = captor.getValue();

        assertEquals(created, result);
        assertEquals("kb_summary_import", upsert.getKey());
        assertEquals("KB Summary Import", upsert.getName());
        assertEquals("Import a skill from markdown.", upsert.getDescription());
        assertEquals(List.of("web_search", "summarize"), upsert.getToolKeys());
        assertEquals("pipeline", upsert.getExecutionMode());
        assertEquals(1, upsert.getInputSchema().size());
        assertEquals("query", upsert.getInputSchema().get(0).getKey());
        assertEquals(2, upsert.getStepConfig().size());
        verify(catalogService, never()).update(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void importMarkdown_should_use_default_tools_and_update_existing_when_overwrite_enabled() {
        SkillMarkdownImportRequest request = new SkillMarkdownImportRequest();
        request.setMarkdownContent("""
                # OpenAI Docs Helper
                
                This skill helps answer docs questions.
                
                ## Steps
                1. Use `summarize` to answer from prepared notes
                """);
        request.setSourceName("OpenAI Docs Helper SKILL.md");
        request.setDefaultToolKeys(List.of("summarize"));
        request.setOverwriteExisting(true);
        request.setToolAliases(Map.of("search", "web_search"));

        SkillVO existing = new SkillVO();
        existing.setKey("openai_docs_helper");
        when(catalogService.listManagedSkills()).thenReturn(List.of(existing));
        when(catalogService.update(org.mockito.ArgumentMatchers.eq("openai_docs_helper"), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(7L)))
                .thenReturn(existing);

        SkillVO result = importer.importMarkdown(request, 7L);
        ArgumentCaptor<SkillUpsertRequest> captor = ArgumentCaptor.forClass(SkillUpsertRequest.class);
        verify(catalogService).update(org.mockito.ArgumentMatchers.eq("openai_docs_helper"), captor.capture(), org.mockito.ArgumentMatchers.eq(7L));
        SkillUpsertRequest upsert = captor.getValue();

        assertEquals(existing, result);
        assertEquals("openai_docs_helper", upsert.getKey());
        assertEquals("OpenAI Docs Helper", upsert.getName());
        assertEquals(List.of("summarize"), upsert.getToolKeys());
        assertEquals("single_tool", upsert.getExecutionMode());
        assertNull(upsert.getEnabled());
    }

    @Test
    void importMarkdown_should_reject_built_in_key_conflicts() {
        SkillMarkdownImportRequest request = new SkillMarkdownImportRequest();
        request.setMarkdownContent("# Web Research\n\n## Tools\n- `web_search`");
        when(catalogService.getEnabledDefinition("web_research")).thenReturn(
                new AgentSkillDefinition("web_research", "Web Research", "Built-in", List.of("web_search"), "single_tool", List.of(), List.of())
        );

        try {
            importer.importMarkdown(request, 1L);
        } catch (Exception e) {
            assertEquals("Cannot import over built-in or enabled skill key: web_research", e.getMessage());
        }

        verify(catalogService, never()).create(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
