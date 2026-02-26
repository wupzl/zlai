# zlAI Platform - System Overview

## 1. What It Is
zlAI is a full-stack LLM application platform that supports:
- Multi-session, multi-branch chat
- GPT Store and Agents
- RAG knowledge base ingestion and retrieval
- Admin console with governance and monitoring
- Modular adapter-based model integration

It is designed to demonstrate production-grade architecture for enterprise interviews.

## 2. Core Modules
### Chat
- Multi-session and multi-branch conversations
- Streaming and non-streaming modes
- Configurable context window and token limits
- Model switching and tool model billing

### GPT Store
- User-created GPTs with system prompts
- Public/Private publish workflow
- Admin approval for public GPTs

### Agents
- Tool-enabled agents
- Multi-agent orchestration mode (manager + team agents)
- Per-agent tool selection and model usage

### RAG
- Document ingestion (PDF, DOCX, TXT, MD)
- OCR for image-based content
- Vector search with MMR and thresholding
- RAG chat sessions and retrieval-augmented answers

### Admin Console
- User management and token balance control
- GPT/Agent moderation and audit
- System logs and usage tracking
- Search tool configuration (Bocha, Baidu, Wikipedia)

## 3. Architecture Highlights
- MyBatis-Plus for data access
- Adapter pattern for LLM providers (DeepSeek, OpenAI, Claude, Mock)
- Event-driven logging (login, chat, system actions)
- Global rate limiting + per-route throttling
- Configurable model pricing and billing

## 4. Typical Workflow
1. User logs in
2. Creates session / selects GPT or Agent
3. Sends messages with streaming or sync mode
4. RAG ingestion enables knowledge-grounded answers
5. Admin monitors system health and governance

## 5. Tech Stack
- Backend: Spring Boot 3, MyBatis-Plus, Redis, MySQL, PostgreSQL (pgvector)
- Frontend: Vue 3 + Vite
- RAG: Vector search + OCR + chunking

## 6. Interview-Ready Highlights
- Multi-branch conversation tree with current branch selection
- Adapter-based model routing (DeepSeek / OpenAI / Claude / Mock)
- RAG ingestion pipeline with OCR and vector search strategy tuning
- Tool calling + multi-agent orchestration with admin controls
- Configurable billing + rate limiting for enterprise readiness

## 7. Extensibility
- Plug in new models via `LlmAdapter`
- Add new tools via `ToolExecutor` + tool registry
- Add new RAG retrievers or rerankers
- Extend admin governance for compliance and auditing

## 8. High-Level Architecture (Text Diagram)
```
┌──────────────┐        ┌──────────────────────────────┐
│   Frontend   │ <----> │   Backend (Spring Boot)       │
│  Vue + Vite  │        │  Controllers / Services       │
└──────────────┘        │  - Chat / GPT / Agent / RAG    │
                        │  - Admin / Auth / Billing     │
                        │  - Tool / Multi-Agent Orches. │
                        └───────────┬───────────────────┘
                                    │
                                    ▼
                         ┌──────────────────────┐
                         │  LLM Adapter Layer   │
                         │  DeepSeek/OpenAI/... │
                         └──────────────────────┘
                                    │
                                    ▼
                         ┌──────────────────────┐
                         │  External LLM APIs   │
                         └──────────────────────┘

┌──────────────┐   ┌──────────────┐   ┌────────────────────┐
│    MySQL     │   │    Redis     │   │   PostgreSQL +     │
│  Core Data   │   │  Cache/Rate  │   │   pgvector (RAG)   │
└──────────────┘   └──────────────┘   └────────────────────┘
```

## 9. Core Flow (Chat)
1. Client sends message (stream or sync)
2. Backend validates auth, rate limit, and balance
3. Builds context window + optional RAG context
4. Routes to adapter (LLM or Mock)
5. Streams tokens or returns sync response
6. Persists messages and updates session stats

## 10. Core Flow (RAG)
1. Ingest document → chunking → embeddings
2. Vector search with MMR + threshold
3. Inject retrieved context into prompt
4. LLM generates grounded answer

---

This document is a high-level overview. For setup instructions, see `readme.md`.
