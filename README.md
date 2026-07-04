# Sergiu-AI MCP Server

A Spring Boot **Model Context Protocol (MCP)** server that exposes a set of
SailPoint identity-governance knowledge tools to any MCP-compatible AI client
(Claude Desktop, IDE assistants, custom agents, etc.).

The server speaks the MCP **streamable HTTP** protocol over a single `/mcp`
endpoint, protects it with an API-key filter, and is packaged to run either as a
plain HTTP service or behind an **nginx reverse proxy with HTTPS**.

---

## Table of contents

- [What this project does](#what-this-project-does)
- [Architecture](#architecture)
- [The MCP tools](#the-mcp-tools)
- [Tech stack](#tech-stack)
- [Configuration](#configuration)
- [Security](#security)
- [Testing & rollout phases](#testing--rollout-phases)
  - [Phase 1 — Plain HTTP (app only)](#phase-1--plain-http-app-only)
  - [Phase 2 — HTTPS via nginx reverse proxy](#phase-2--https-via-nginx-reverse-proxy)
- [Monitoring (Actuator)](#monitoring-actuator)
- [Project layout](#project-layout)
- [Further deployment](#further-deployment)

---

## What this project does

The application is an **MCP server**. MCP is an open protocol that lets AI
assistants call external "tools" (functions) to fetch data or perform actions.
This server registers a handful of read-only tools that return curated knowledge
about **SailPoint** (identity security / IGA) and its **IdentityIQ certification
types**. An AI client connected to the server can call these tools to answer user
questions accurately instead of relying on the model's memory.

Everything the tools return is static, curated text held in memory — there is no
database and no outbound call to SailPoint. The value is in exposing that
knowledge through the MCP contract so an assistant can retrieve it on demand.

---

## Architecture

```
                         Phase 2 (HTTPS)
   MCP client ──HTTPS :443──▶ nginx ──HTTP :8080 (internal)──▶ Spring Boot app
                              (TLS,                             ├─ ApiKeyFilter
                               rate limit,                      ├─ MCP /mcp endpoint
                               proxy)                           └─ Actuator
```

- **Spring Boot app** — hosts the MCP endpoint at `/mcp`, the tool components, the
  API-key filter, and Spring Boot Actuator. Listens on port **8080**.
- **nginx** (Phase 2 only) — terminates TLS on **443**, redirects **80 → 443**,
  rate-limits `/mcp`, and proxies to the app. In this setup the app is **not**
  published to the host; nginx is the only public door.

---

## The MCP tools

Two `@Component` classes register the tools via Spring AI's `@McpTool` annotation.

### SailPoint tools (`SailPointTool`)

| Tool name | Purpose |
|-----------|---------|
| `sailpoint-overview` | High-level overview of SailPoint and its main products (Identity Security Cloud, IdentityIQ). |
| `sailpoint-features` | Lists SailPoint's key features, one-line each. Optional `limit` parameter. |
| `sailpoint-feature-details` | Full description of one feature by id (e.g. `access-certifications`). |
| `sailpoint-connectors` | Categories of systems SailPoint integrates with and example connectors. |

### IdentityIQ certification tools (`CertificationTool`)

| Tool name | Purpose |
|-----------|---------|
| `identityiq-certification-types` | Lists IdentityIQ certification types, one-line each. Optional `limit` parameter. |
| `identityiq-certification-details` | Full description of one certification type by id (e.g. `manager`, `targeted`). |

Each tool logs its invocation, so you can see calls in the app logs.

---

## Tech stack

- **Java 21**
- **Spring Boot 4.1.0** (`spring-boot-starter-webmvc`, `spring-boot-starter-actuator`)
- **Spring AI 2.0.0** — `spring-ai-starter-mcp-server-webmvc` (MCP server, streamable protocol)
- **Maven** (wrapper included: `mvnw` / `mvnw.cmd`)
- **Docker** (multi-stage build) + **Docker Compose**
- **nginx 1.27-alpine** (reverse proxy, Phase 2)

---

## Configuration

Key settings live in `src/main/resources/application.yaml`:

```yaml
spring:
  ai:
    mcp:
      server:
        name: Sergiu-AI MCP Server
        protocol: streamable   # MCP streamable HTTP
        stdio: false           # HTTP transport, not stdio
        type: sync
mcp:
  security:
    api-key: ${MCP_API_KEY:dev-local-key}   # override via env var
management:
  endpoints:
    web:
      exposure:
        include: health, info, metrics, env, loggers, mappings, beans
```

| Setting | Meaning |
|---------|---------|
| `MCP_API_KEY` | The secret required to call `/mcp`. Defaults to `dev-local-key` for local dev; **set a strong value in production.** |
| `protocol: streamable` | The MCP transport. Streaming responses are long-lived — important for the nginx config (see below). |
| `management...exposure.include` | Which Actuator endpoints are reachable. Some (`env`, `beans`, `mappings`) are sensitive — keep them off the public internet. |

---

## Security

- **API-key filter (`ApiKeyFilter`)** — a `OncePerRequestFilter` that guards only
  `/mcp`. It accepts the key via `X-API-Key:` or `Authorization: Bearer <key>` and
  compares it with a constant-time check (`MessageDigest.isEqual`). Missing/wrong
  key → `401 {"error":"unauthorized"}`. `/actuator/health` is intentionally left
  open for health checks.
- **TLS termination (Phase 2)** — nginx encrypts everything in transit, so the API
  key no longer travels in plaintext.
- **Rate limiting (Phase 2)** — nginx limits `/mcp` to 10 req/s per client IP
  (burst 20).
- **No direct app exposure (Phase 2)** — Docker Compose `expose`s the app to the
  internal network only; it is not published to the host.

---

## Testing & rollout phases

The project was validated in two stages. Run every command from the project root
(`C:\SpringBoot_AI\demo`).

### Phase 1 — Plain HTTP (app only)

The goal of Phase 1 is to prove the application and its MCP tools work on their
own, with **no TLS and no proxy** — the app listens directly on port 8080.

**1. Build and run the app**

```bash
./mvnw clean package -DskipTests
java -jar target/demo-0.0.1-SNAPSHOT.jar
```

(or run it in your IDE). The server starts on `http://localhost:8080`.

**2. Verify the app is up**

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP", ...}
```

**3. Verify the API-key filter**

```bash
# No key -> rejected
curl -i http://localhost:8080/mcp
# HTTP/1.1 401 Unauthorized  {"error":"unauthorized"}

# With the key -> passes the filter (MCP protocol response)
curl -i http://localhost:8080/mcp -H "X-API-Key: dev-local-key"
```

**4. Connect an MCP client** (optional, full test)

Point an MCP-capable client at `http://localhost:8080/mcp` with the API key, then
call the tools (e.g. `sailpoint-overview`, `identityiq-certification-types`) and
confirm the responses.

> At this point traffic is unencrypted and the app port is public — fine for local
> development, **not** for exposure to a network. That is what Phase 2 fixes.

The EC2 equivalent of this phase (running the JAR as a systemd service, port 8080
open only to your IP) is documented in [`deploy/DEPLOY.md`](deploy/DEPLOY.md).

### Phase 2 — HTTPS via nginx reverse proxy

Phase 2 puts an **nginx reverse proxy with HTTPS** in front of the app using Docker
Compose. Traffic becomes:

```
client ──HTTPS :443──▶ nginx ──HTTP :8080 (internal only)──▶ Spring Boot app
```

The app is no longer reachable directly; nginx terminates TLS and is the only
public door. Full step-by-step guide: [`deploy/LOCAL_NGINX.md`](deploy/LOCAL_NGINX.md).

**1. Generate a self-signed TLS certificate** (one time)

```bash
mkdir -p nginx/certs
# Git Bash (has openssl):
openssl req -x509 -newkey rsa:2048 -nodes \
  -keyout nginx/certs/key.pem -out nginx/certs/cert.pem \
  -days 365 -subj "/CN=localhost" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"
```

A self-signed cert is untrusted, so clients must pass `-k` (curl) or accept the
browser warning — expected locally. On a real server, swap it for a Let's Encrypt
cert.

**2. Build and start the stack**

```bash
docker compose up --build      # add -d to run detached
```

This builds the app image and starts both `app` and `nginx` containers on a shared
Docker network (`mcpnet`).

**3. Verify HTTPS and routing** (`-k` accepts the self-signed cert)

```bash
# a) Health through nginx over HTTPS
curl -k https://localhost/actuator/health          # {"status":"UP"}

# b) HTTP redirects to HTTPS
curl -kI http://localhost/actuator/health          # 301 -> https://localhost/...

# c) MCP rejects a request with no API key (filter still runs behind the proxy)
curl -k -i https://localhost/mcp                    # 401 unauthorized

# d) MCP accepts the key (now travelling encrypted)
curl -k -i https://localhost/mcp -H "X-API-Key: dev-local-key"

# e) App is NOT reachable directly -> should FAIL (connection refused)
curl http://localhost:8080/actuator/health
```

If (a)–(d) behave and (e) is refused, the reverse proxy is doing its job.

**4. Reloading nginx after a config change**

The config is mounted read-only, so no rebuild is needed — validate and reload:

```bash
docker compose exec nginx nginx -t          # validate syntax
docker compose exec nginx nginx -s reload   # graceful reload
```

**5. Tear down**

```bash
docker compose down       # stop and remove containers
docker compose down -v    # also remove network/volumes
```

#### Why the nginx config looks the way it does

- **`proxy_buffering off;` + `proxy_read_timeout 3600s;`** on `/mcp` — the MCP
  streamable protocol uses long-lived streaming responses. A buffering proxy or a
  short timeout would break streaming.
- **`limit_req` on `/mcp`** — cheap protection for the public endpoint (10 r/s,
  burst 20).
- **`X-Forwarded-*` headers** — pass the real client IP and scheme through to the
  app.

---

## Monitoring (Actuator)

Spring Boot Actuator is enabled. Exposed endpoints are controlled by
`management.endpoints.web.exposure.include` in `application.yaml`
(`health, info, metrics, env, loggers, mappings, beans`).

Through nginx, actuator access is governed by the `location /actuator/` block. To
reach endpoints beyond health, that block must be a **prefix** match
(`location /actuator/`, not the exact-match `location = /actuator/health`), and it
is restricted to trusted networks:

```nginx
location /actuator/ {
    allow 172.16.0.0/12;   # Docker internal network
    allow 127.0.0.1;       # localhost
    deny  all;             # block everyone else
    proxy_pass http://app;
}
```

> **Security note:** `env`, `beans`, and `mappings` can leak configuration
> internals. Keep them behind the allow-list, or drop them from the `include:`
> list if you don't need them.

Quick check:

```bash
curl -k https://localhost/actuator/health
curl -k https://localhost/actuator/metrics
```

---

## Project layout

```
demo/
├── src/main/java/dev/sergiu/demo/
│   ├── DemoApplication.java                     # Spring Boot entrypoint
│   ├── SailPointComponent/SailPointTool.java    # SailPoint MCP tools
│   ├── CertificationsType/CertificationTool.java# IdentityIQ certification MCP tools
│   └── security/ApiKeyFilter.java               # API-key filter guarding /mcp
├── src/main/resources/application.yaml          # app + MCP + actuator config
├── Dockerfile                                   # multi-stage build (Maven -> JRE 21)
├── docker-compose.yml                           # app + nginx orchestration (Phase 2)
├── nginx/
│   ├── nginx.conf                               # reverse proxy config
│   └── certs/                                   # cert.pem + key.pem (generated)
├── deploy/
│   ├── DEPLOY.md                                # EC2 Phase 1 (HTTP, systemd)
│   └── LOCAL_NGINX.md                           # Local Phase 2 (HTTPS via nginx)
└── README.md
```

---

## Further deployment

- **[`deploy/DEPLOY.md`](deploy/DEPLOY.md)** — Phase 1 on EC2: run the JAR as a
  systemd service on Amazon Linux 2023, port 8080 open to your IP only.
- **[`deploy/LOCAL_NGINX.md`](deploy/LOCAL_NGINX.md)** — Phase 2 locally: the full
  nginx + HTTPS reverse-proxy walkthrough with Docker Compose.

Promoting Phase 2 to a real server means pointing a domain at the instance,
replacing the self-signed cert with a Let's Encrypt cert (auto-renewing, removes
the browser warning), and closing port 8080 so only 443 is public. The
`nginx.conf` structure carries over unchanged.
