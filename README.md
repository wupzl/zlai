# zlAI Platform - Setup Guide

This guide explains how to initialize and run the system locally and via Docker.

## 1. Prerequisites (Local Dev)
- JDK 21
- Node.js 18+ (npm)
- MySQL 5.7.26+ (8.x also supported)
- Redis 6+
- PostgreSQL 14+ with `pgvector`
- (Optional) Tesseract OCR installed for image OCR

## 2. Database Initialization (Local Dev)
### MySQL
1. Create database:
```
CREATE DATABASE `zl-ai2` DEFAULT CHARACTER SET utf8mb4;
```
2. Initialize schema and seed data:
```
mysql -u root -p<your_password> < backend/db/zlai.sql
mysql -u root -p<your_password> < backend/db/seed.sql
```

### PostgreSQL (RAG)
1. Create database:
```
CREATE DATABASE zl_ai_rag;
CREATE EXTENSION IF NOT EXISTS vector;
```
2. Run RAG schema SQL:
```
psql -U postgres -d zl_ai_rag -f backend/db/zlai_pg.sql
```

## 3. Configuration (Local Dev)
Use `backend/.env.properties` for local Spring Boot startup.
`application.yaml` uses `${...}` placeholders and resolves from that properties file (or system env).

### Core keys
- `APP_AI_DEEPSEEK_KEY`
- `APP_AI_OPENAI_KEY`
- `APP_AI_CLAUDE_KEY`
- `APP_JWT_ACCESS_SECRET`
- `APP_JWT_REFRESH_SECRET`
- `SPRING_DATASOURCE_*`
- `SPRING_DATA_REDIS_*`
- `APP_RAG_DATASOURCE_*`
- `APP_RAG_EMBEDDING_*`
- `APP_TOOLS_SEARCH_*` (Bocha/Baidu/Wikipedia)

### OCR (optional)
```
APP_RAG_OCR_ENABLED=true
APP_RAG_OCR_TESSDATA_PATH=<your_tesseract_tessdata_path>
APP_RAG_OCR_LANGUAGE=eng+chi_sim
```

### Mock LLM (for load testing)
```
APP_LLM_MOCK_ENABLED=true
```

## 4. Run Backend (Local Dev)
```
cd backend
mvn -DskipTests=true spring-boot:run
```

## 5. Run Frontend (Local Dev)
```
cd frontend
npm install
npm run dev
```
Default frontend URL: `http://localhost:5173`

## 6. Default Accounts
From `backend/db/seed.sql` (change passwords before production):
- Admin: `admin01 / 123456`
- User: `test01 / 123456`
- User: `test02 / 123456`
- User: `test03 / 123456`
- User: `wupzl / 123456`

## 7. Docker (Recommended for Cross-Platform)
Copy `.env.example` to `.env` and fill your secrets. `.env` is ignored by Git.

Important:
- Local backend (`mvn spring-boot:run`): reads `backend/.env.properties`
- Docker Compose (`docker compose up`): reads project root `.env`

```
docker compose up -d --build
```

Services:
- Frontend: `http://localhost:5173`
- Backend: `http://localhost:8080`
- MySQL: `localhost:3306`
- Redis: `localhost:6380`
- PostgreSQL: `localhost:5455`

### 7.1 Docker Proxy Notes (Windows)
The compose build uses proxy settings to download dependencies and OS packages.
If you are on Windows + Clash:
- Use `host.docker.internal:PORT` for build-time proxy.
- Update the build args in `docker-compose.yml`:
  - `HTTP_PROXY`, `HTTPS_PROXY`, `NO_PROXY`
- If you do not need a proxy, set:
  - `HTTP_PROXY: ""`
  - `HTTPS_PROXY: ""`

### 7.2 Docker in Other Environments
If you deploy on Linux or another host:
- Replace `host.docker.internal` with the actual host IP.
- Or remove the proxy build args entirely if not needed.

## 8. Admin OCR Limits
Admins can configure OCR limits in:
`Admin Console → Settings → OCR Limits`

Normal users are rate-limited and size-limited. Admins bypass limits.

## 9. Core Endpoints
- Stream chat: `POST /api/chat/stream`
- Sync chat: `POST /api/chat/message`
- Sessions: `GET /api/chat/sessions`
- Chat history: `GET /api/chat/{chatId}`

RAG:
- Upload file: `POST /api/rag/ingest/file-upload`
- Query: `POST /api/rag/query`

Admin:
- `/api/admin/*` (admin login required)

## 10. Load Testing (k6)
Files under `test/`:
- `k6-load.js` - core chat
- `k6-mix.js` - mixed routes
- `k6-rag.js` - RAG ingest/query

Example:
```
k6 run test/k6-load.js -e MODEL=mock-chat
k6 run test/k6-mix.js -e MODEL=mock-chat
k6 run test/k6-rag.js
```

Logs are saved in:
- `test/load.log`
- `test/mix.log`
- `test/rag.log`

## 11. Notes
- If web search is blocked, enable Bocha and disable Wikipedia in admin settings.
- For real LLM performance tests, disable mock and set proper API keys.
- Ensure Redis and PostgreSQL are running before backend startup.
