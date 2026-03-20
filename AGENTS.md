# zlAI-v2 Development Guidelines

Auto-generated from all feature plans. Last updated: 2026-03-19

## Active Technologies

- Java 21, Spring Boot 3.5, Markdown specs + Spring Boot, Jackson, existing chat/agent modules, optional future compatibility with Spring AI / LangChain4j tool-style contracts (003-agent-skill-refactor)

## Project Structure

```text
src/
tests/
```

## Commands

# Add commands for Java 21, Spring Boot 3.5, Markdown specs

## Code Style

Java 21, Spring Boot 3.5, Markdown specs: Follow standard conventions

## Recent Changes

- 003-agent-skill-refactor: Added Java 21, Spring Boot 3.5, Markdown specs + Spring Boot, Jackson, existing chat/agent modules, optional future compatibility with Spring AI / LangChain4j tool-style contracts

## Skill-Layer Rules

- Treat skill-layer work as contract-first: define type, trigger, input/output, fallback, and traceability before implementation.
- Keep skill registry, routing, execution, and fallback as separate responsibilities.
- Do not bypass auth, quota, tenant boundaries, or audit requirements through skill execution.
- Prefer explicit verification for skill match, skill miss, skill conflict, and fallback behavior.

<!-- MANUAL ADDITIONS START -->
<!-- MANUAL ADDITIONS END -->
