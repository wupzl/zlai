# [PROJECT NAME] Development Guidelines

Auto-generated from all feature plans. Last updated: [DATE]

## Active Technologies

[EXTRACTED FROM ALL PLAN.MD FILES]

## Project Structure

```text
[ACTUAL STRUCTURE FROM PLANS]
```

## Commands

[ONLY COMMANDS FOR ACTIVE TECHNOLOGIES]

## Code Style

[LANGUAGE-SPECIFIC, ONLY FOR LANGUAGES IN USE]

## Recent Changes

[LAST 3 FEATURES AND WHAT THEY ADDED]

## Skill-Layer Rules

- Treat skill-layer work as contract-first: define type, trigger, input/output, fallback, and traceability before implementation.
- Keep skill registry, routing, execution, and fallback as separate responsibilities.
- Do not bypass auth, quota, tenant boundaries, or audit requirements through skill execution.
- Prefer explicit verification for skill match, skill miss, skill conflict, and fallback behavior.

<!-- MANUAL ADDITIONS START -->
<!-- MANUAL ADDITIONS END -->
