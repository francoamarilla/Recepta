# Setup and configuration

[Versión en español](../setup.md) · [README](../../README.md)

## Docker Compose (recommended)

Prerequisites: Docker with Compose. For AI, provide an Anthropic API key. Use development values only locally; never commit real secrets.

```bash
docker compose up -d --build
```

| Service | Port | Purpose |
|---|---|---|
| postgres | 5433 → 5432 | PostgreSQL 16 |
| backend | 8080 | Spring Boot REST API |
| ai | Internal network only | FastAPI agent |
| frontend | 8081 → 80 | nginx serving the frontend and proxying /api |

Open http://localhost:8081. To enable AI, set `ANTHROPIC_API_KEY` in your shell before starting Compose. The public chat URL is shown on the Assistant dashboard screen. Without a key, the chat falls back to buttons. Stop with `docker compose down`; rebuild after code changes with the startup command above.

## Local development

Start only PostgreSQL with `docker compose up -d postgres`. Run backend and frontend in separate terminals:

```bash
cd backend
SPRING_DATASOURCE_PASSWORD=turnos_app JWT_SECRET=$(openssl rand -base64 32) ./mvnw spring-boot:run
```

```bash
cd frontend
npm install
npm run dev
```

On PowerShell, set environment variables separately before running the Maven wrapper. Vite proxies /api to port 8080; the development frontend runs on 5173.

## First tenant

There is no public self-registration. Set `SUPERADMIN_EMAIL` and `SUPERADMIN_PASSWORD` before starting the backend to seed a super admin if the email does not exist. Sign in and create the tenant, first branch and initial admin through `POST /api/admin/tenants` or the minimal admin screen at /.

## Configuration reference

| Variables | Purpose |
|---|---|
| SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME, SPRING_DATASOURCE_PASSWORD | Database connection; application role must not be a superuser. Password required outside Compose. |
| JWT_SECRET, JWT_EXPIRATION_SECONDS | Base64 HMAC key of at least 32 bytes; default expiry 86400 seconds. Secret required outside Compose. |
| SUPERADMIN_EMAIL, SUPERADMIN_PASSWORD | Optional initial super admin. |
| PORT, CORS_ALLOWED_ORIGINS | Backend port (8080 by default), allowed origins; empty for same-origin serving. |
| AI_SERVICE_URL, AI_SERVICE_TOKEN | Agent endpoint and shared secret. Empty URL disables AI. |
| AI_DAILY_BUDGET_USD, CHAT_RATE_LIMIT_PER_MINUTE | Daily global LLM budget (0 = unlimited by default), public chat rate limit (20/min by default). |
| TURNSTILE_SECRET, VITE_TURNSTILE_SITE_KEY | Optional CAPTCHA configuration. |
| FORWARD_HEADERS_STRATEGY | Proxy header handling; use framework behind the deployed proxy as documented. |
| ANTHROPIC_API_KEY, AI_MODEL, AI_EFFORT, CORE_BASE_URL | Agent provider credentials, model (claude-haiku-5-5), effort (low), backend URL. |

The demo profile is enabled only with `SPRING_PROFILES_ACTIVE=demo`. Its seed and reset endpoint are absent otherwise. Demo-specific variables include `DEMO_ADMIN_EMAIL`, `DEMO_ADMIN_PASSWORD`, `DEMO_RESET_TOKEN`, `DEMO_SEED_ON_STARTUP` and `DB_POOL_MAX`. Frontend build variables include `VITE_API_BASE_URL`, `VITE_DEMO_EMAIL`, and `VITE_DEMO_PASSWORD`. Demo login credentials are intentionally public only in demo deployments.

For the complete variable defaults, Windows notes and setup instructions, see the [Spanish configuration reference](../setup.md). The [public demo deployment guide](../../DEPLOY.md) documents hosting and database connection requirements.
