# Instalación y configuración

[English version](en/setup.md) · [README](../README.md)

Los valores de desarrollo de Compose no deben utilizarse en producción. No publiques secretos reales.

## Cómo levantar el proyecto

### Con Docker Compose (recomendado — stack completo)

```bash
docker compose up -d --build
```

Esto levanta los 4 servicios:

| Servicio | Puerto | Descripción |
|---|---|---|
| `postgres` | 5433 → 5432 | Postgres 16, con healthcheck |
| `backend` | 8080 | API REST Spring Boot |
| `ai` | — (solo red interna) | Agente de IA. Sin `ANTHROPIC_API_KEY` en tu shell, el chat cae al menú de botones |
| `frontend` | 8081 → 80 | nginx sirviendo el build estático; `/api/*` se reenvía al backend |

Para el asistente: `ANTHROPIC_API_KEY=... docker compose up -d --build` y abrir `http://localhost:8081/chat/<tenantId>` (el link exacto aparece en la pantalla **Asistente** del dashboard).

Abrir `http://localhost:8081` — el dashboard queda ahí, autenticado contra la API real vía el proxy de nginx.

`docker compose down` para bajar todo. `docker compose up -d --build` de nuevo cuando cambie código del backend o del frontend (el build ocurre dentro de la imagen, no hace falta compilar antes a mano).

### En desarrollo local (hot reload)

Backend y frontend corren cada uno por su cuenta; solo Postgres se levanta con Docker:

```bash
docker compose up -d postgres
cd backend && SPRING_DATASOURCE_PASSWORD=turnos_app JWT_SECRET=$(openssl rand -base64 32) ./mvnw spring-boot:run
cd frontend && npm install && npm run dev
```

En PowerShell las variables van aparte: `$env:JWT_SECRET = "..."` y `$env:SPRING_DATASOURCE_PASSWORD = "turnos_app"` antes del `./mvnw`. Son las dos únicas obligatorias (ver tabla abajo); Compose las fija solas, pero corriendo el backend a mano hay que pasarlas.

El frontend en modo dev (`npm run dev`, puerto 5173) tiene un proxy propio (`vite.config.ts`) que reenvía `/api` a `http://localhost:8080`, así que no hace falta configurar CORS para desarrollar así.

### Alta del primer tenant

No hay autoregistro público (spec sección 2). El alta de un tenant es manual, vía un Super Admin:

1. Seedear un Super Admin al levantar el backend con las variables `SUPERADMIN_EMAIL` / `SUPERADMIN_PASSWORD` (ver tabla de variables de entorno abajo) — solo siembra la cuenta si no existe ya una con ese email.
2. Loguearse como ese Super Admin y usar `POST /api/admin/tenants` (o la pantalla mínima `/`) para crear el tenant, su primera sucursal y el primer usuario Admin, todo en un solo paso.

## Variables de entorno

`JWT_SECRET` y `SPRING_DATASOURCE_PASSWORD` son obligatorias: sin ellas el backend no arranca. Compose fija valores de dev para las dos, así que `docker compose up` funciona tal cual; correr el backend por fuera de Compose las pide. El resto tiene un default razonable para desarrollo local.

| Variable | Default | Uso |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/turnos` (Compose la fija a `postgres:5432`) | Conexión a Postgres |
| `SPRING_DATASOURCE_USERNAME` | `turnos_app` | Rol de aplicación — nunca el superusuario, RLS depende de eso |
| `SPRING_DATASOURCE_PASSWORD` | **sin default** (Compose fija el de dev) | Contraseña de ese rol. En dev la fija `postgres-init/init-app-role.sql`; en producción la ponés vos |
| `JWT_SECRET` | **sin default** (Compose fija uno de dev) | Clave HMAC para firmar los JWT de sesión, en base64 de al menos 32 bytes: `openssl rand -base64 32`. El valor de Compose es público y sirve solo para desarrollo. |
| `JWT_EXPIRATION_SECONDS` | `86400` (24h) | Vencimiento del JWT — no hay refresh token (spec sección 11) |
| `SUPERADMIN_EMAIL` / `SUPERADMIN_PASSWORD` | vacío (no siembra nada) | Si ambas están seteadas y no existe ya una cuenta con ese email, se crea un Super Admin al arrancar |
| `PORT` | `8080` | Puerto en el que escucha el backend. Lo inyectan solas las plataformas tipo Render |
| `CORS_ALLOWED_ORIGINS` | vacío (sin headers CORS) | Orígenes permitidos, separados por coma. Vacío es lo correcto cuando el frontend y la API comparten origen, como acá: nginx en Compose y el proxy de Vite en dev |

### Asistente de IA

| Variable | Dónde | Default | Uso |
|---|---|---|---|
| `AI_SERVICE_URL` | backend | vacío (IA apagada: el chat usa el menú de botones) | URL de `ai-service` |
| `AI_SERVICE_TOKEN` | backend y ai | **sin default** en ai (rechaza todo) | Secreto compartido de las llamadas backend → ai |
| `AI_DAILY_BUDGET_USD` | backend | `0` (sin tope) | Tope global diario de gasto en LLM, todos los tenants |
| `CHAT_RATE_LIMIT_PER_MINUTE` | backend | `20` | Mensajes por minuto por IP y por sesión en el chat público |
| `TURNSTILE_SECRET` | backend | vacío (sin captcha) | Cloudflare Turnstile en sesiones nuevas del chat. El frontend necesita `VITE_TURNSTILE_SITE_KEY` |
| `FORWARD_HEADERS_STRATEGY` | backend | `none` | `framework` detrás de un proxy (Render), para que el rate limit vea la IP real |
| `ANTHROPIC_API_KEY` | ai | — | Clave del proveedor |
| `AI_MODEL` / `AI_EFFORT` | ai | `claude-haiku-5-5` / `low` | Modelo y nivel de effort del agente |
| `CORE_BASE_URL` | ai | `http://localhost:8080` | Dónde está el backend (para `/internal/agent/*`) |

### Solo del perfil `demo`

El perfil `demo` (la demo pública: ver [`DEPLOY.md`](../DEPLOY.md)) se activa **únicamente**
con `SPRING_PROFILES_ACTIVE=demo`. Sin esa variable, las clases de seed y el
endpoint de reseteo no se cargan y `/api/demo/reset` responde 404 — hay un
test que lo verifica (`DemoProfileAbsentTest`).

| Variable | Default | Uso |
|---|---|---|
| `DEMO_ADMIN_EMAIL` / `DEMO_ADMIN_PASSWORD` | vacío (no siembra nada) | Credenciales del admin del tenant de demo |
| `DEMO_RESET_TOKEN` | vacío (endpoint apagado, 404) | Token de `POST /api/demo/reset`, en el header `X-Demo-Reset-Token` |
| `DEMO_SEED_ON_STARTUP` | `true` | `false` no regenera los datos al arrancar |
| `DB_POOL_MAX` | `5` | Tamaño máximo del pool Hikari |

En el frontend, `VITE_API_BASE_URL` apunta la SPA a otra URL de API (por
default `/api`, mismo origen), y `VITE_DEMO_EMAIL` / `VITE_DEMO_PASSWORD`
hacen aparecer el cartel con las credenciales en el login. Sin definirlas,
no hay cartel.
