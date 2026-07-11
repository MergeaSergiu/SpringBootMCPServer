# Sergiu-AI MCP Server

A Spring Boot **Model Context Protocol (MCP)** server that exposes a set of
SailPoint identity-governance knowledge tools to any MCP-compatible AI client
(Claude Desktop, IDE assistants, custom agents, etc.).

The server speaks the MCP **streamable HTTP** protocol over a single `/mcp`
endpoint, protects it with an API-key filter, and runs in production on **AWS
EC2** behind an **nginx HTTPS reverse proxy** — with the API key stored in **AWS
Secrets Manager** and logs shipped to **AWS CloudWatch**.

---

## Table of contents

- [What this project does](#what-this-project-does)
- [Architecture](#architecture)
- [The MCP tools](#the-mcp-tools)
- [Tech stack](#tech-stack)
- [Configuration](#configuration)
- [Security](#security)
- [AWS cloud integration](#aws-cloud-integration)
  - [Secrets — AWS Secrets Manager](#secrets--aws-secrets-manager)
  - [Logging — AWS CloudWatch](#logging--aws-cloudwatch)
- [Continuous integration & delivery (GitHub Actions)](#continuous-integration--delivery-github-actions)
- [Testing & rollout phases](#testing--rollout-phases)
  - [Phase 1 — Plain HTTP (app only)](#phase-1--plain-http-app-only)
  - [Phase 2 — HTTPS via nginx reverse proxy](#phase-2--https-via-nginx-reverse-proxy)
  - [Phase 3 — Production on AWS EC2](#phase-3--production-on-aws-ec2)
- [Monitoring (Actuator)](#monitoring-actuator)
- [Project layout](#project-layout)
- [Stack summary](#stack-summary)
- [Further deployment](#further-deployment)

---

## What this project does

The application is an **MCP server**. MCP is an open protocol that lets AI
assistants call external "tools" (functions) to fetch data or perform actions.
This server registers a handful of read-only tools that return curated knowledge
about **SailPoint** (identity security / IGA), its **IdentityIQ certification
types**, and its **workflow types**. An AI client connected to the server can call
these tools to answer user questions accurately instead of relying on the model's
memory.

Everything the tools return is static, curated text held in memory — there is no
database and no outbound call to SailPoint. The value is in exposing that
knowledge through the MCP contract so an assistant can retrieve it on demand.

---

## Architecture

```
                     Production (AWS EC2, HTTPS)

   MCP client ──HTTPS :443──▶ nginx ──HTTP :8080 (internal)──▶ Spring Boot app
                              (TLS,                            ├─ ApiKeyFilter
                               rate limit,                     ├─ MCP /mcp endpoint
                               proxy)                          └─ Actuator
                                                                     │
                          ┌──────────────────────────────────────────┤
                          ▼                                          ▼
                 AWS Secrets Manager                          AWS CloudWatch
                 (API key, read at startup                    (OS + app logs via
                  via EC2 instance role)                       CloudWatch agent)
```

- **Spring Boot app** — hosts the MCP endpoint at `/mcp`, the tool components, the
  API-key filter, and Spring Boot Actuator. Listens on port **8080**.
- **nginx** — terminates TLS on **443**, redirects **80 → 443**, rate-limits
  `/mcp`, and proxies to the app. The app is **not** published publicly; nginx is
  the only public door.
- **AWS Secrets Manager** — holds the API key; the app fetches it at startup using
  the EC2 instance's IAM role (no static credentials anywhere).
- **AWS CloudWatch** — the CloudWatch agent ships OS logs and the app's structured
  JSON logs off the instance for querying/alerting.

---

## The MCP tools

Three `@Component` classes register the tools via Spring AI's `@McpTool` annotation.

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

### Workflow tools (`WorkflowTool`)

| Tool name | Purpose |
|-----------|---------|
| `workflows-overview` | Lists SailPoint's key workflow types (e.g. `Policy Violation`, `LCM Provisioning`, `Identity Lifecycle`), one-line each. Optional `limit` parameter. |

Each tool logs its invocation, so calls are visible in the app logs (and, in
production, queryable in CloudWatch Logs Insights).

---

## Tech stack

- **Java 21**
- **Spring Boot 4.1.0** (`spring-boot-starter-webmvc`, `spring-boot-starter-actuator`)
- **Spring AI 2.0.0** — `spring-ai-starter-mcp-server-webmvc` (MCP server, streamable protocol)
- **AWS SDK for Java 2.x** — `software.amazon.awssdk:secretsmanager`
- **Maven** (wrapper included: `mvnw` / `mvnw.cmd`)
- **Docker** (multi-stage build) + **Docker Compose**
- **nginx 1.27-alpine** (reverse proxy)
- **AWS** — EC2 (Amazon Linux 2023), Secrets Manager, CloudWatch, IAM instance role
- **GitHub Actions** — CI (build, test) + Docker image publish to GHCR

---

## Configuration

Key settings live in `src/main/resources/application.yaml`:

```yaml
spring:
  ai:
    mcp:
      server:
        name: Sergiu-AI MCP Server
        version: 0.1.1
        protocol: streamable   # MCP streamable HTTP
        stdio: false           # HTTP transport, not stdio
        type: sync
mcp:
  security:
    api-key: ${MCP_API_KEY:dev-local-key}   # local/CI fallback key
    secret-name: ${MCP_SECRET_NAME:}         # set on EC2 -> fetch key from Secrets Manager
    secret-region: ${MCP_SECRET_REGION:}     # optional; else AWS_REGION / instance metadata
logging:
  structured:
    format:
      file: ecs                              # ECS JSON when a log file is configured
management:
  endpoints:
    web:
      exposure:
        include: health, info, metrics, env, loggers, mappings, beans
  endpoint:
    health:
      show-details: always
  info:
    env:
      enabled: true
```

The jar is **environment-agnostic** — no environment-specific values are baked in.
Each environment supplies what it needs through env vars:

| Env var | Meaning |
|---------|---------|
| `MCP_API_KEY` | Fallback API key when Secrets Manager is not used. Defaults to `dev-local-key` for local/CI. |
| `MCP_SECRET_NAME` | Name of the Secrets Manager secret holding the key (e.g. `mcp/api-key`). **When set, the app fetches the key from Secrets Manager** and ignores `MCP_API_KEY`. |
| `MCP_SECRET_REGION` | Region of the secret. Optional — falls back to `AWS_REGION` / `~/.aws` / EC2 instance metadata. |
| `LOGGING_FILE_NAME` | Path of the app log file (e.g. `/var/log/mcp/app.log`). When set, Spring Boot writes ECS-format JSON there; unset means console-only. |

---

## Security

- **API key via AWS Secrets Manager** — in production the key is never stored in a
  file. `SecretsManagerConfig` fetches it at startup from Secrets Manager using the
  EC2 instance's IAM role, so there are **no static AWS credentials** in code or
  config. Resolution path: `SecretsManagerConfig` → `ApiKeyHolder` → `ApiKeyFilter`.
- **API-key filter (`ApiKeyFilter`)** — a `OncePerRequestFilter` that guards only
  `/mcp`. It accepts the key via `X-API-Key:` or `Authorization: Bearer <key>` and
  compares it with a constant-time check (`MessageDigest.isEqual`). Missing/wrong
  key → `401 {"error":"unauthorized"}`. `/actuator/health` is intentionally left
  open for health checks.
- **TLS termination** — nginx encrypts everything in transit, so the API key never
  travels in plaintext.
- **Rate limiting** — nginx limits `/mcp` to 10 req/s per client IP (burst 20).
- **No direct app exposure** — the app is only reachable through nginx; port 8080
  is not public.

---

## AWS cloud integration

### Secrets — AWS Secrets Manager

On EC2 the MCP API key lives in a Secrets Manager secret (`mcp/api-key`). At
startup, `SecretsManagerConfig` reads `MCP_SECRET_NAME`; if it is set, it calls
`GetSecretValue` and hands the value to `ApiKeyHolder`, which `ApiKeyFilter`
uses. Authentication to AWS is handled by the **EC2 instance role**
(`mcp-ec2-role`, granted `secretsmanager:GetSecretValue` on that secret) via the
default credential chain — nothing is stored on disk.

Locally and in CI, `MCP_SECRET_NAME` is unset, so no AWS call is made and the app
falls back to `MCP_API_KEY` / `dev-local-key`.

> **Rotation note:** the key is fetched once at startup, so rotating the secret
> requires a service restart to pick up the new value.

### Logging — AWS CloudWatch

When `LOGGING_FILE_NAME` is set (via the systemd unit on EC2), Spring Boot writes
its logs as **ECS-format JSON** to that file, while still logging plain text to the
console/journald. The **CloudWatch agent** tails the log files and ships them to
CloudWatch log groups:

| File | Log group |
|------|-----------|
| `/var/log/messages` | `/ec2/mcp/messages` |
| `/var/log/secure` | `/ec2/mcp/secure` |
| `/var/log/cloud-init-output.log` | `/ec2/mcp/cloud-init` |
| `/var/log/mcp/app.log` (ECS JSON) | `/ec2/mcp/app` |

Because the app log is JSON, tool usage is queryable in **CloudWatch Logs
Insights**, e.g.:

```
fields message
| filter message like /Tool invoked/
| parse message "Tool invoked: *" as tool
| stats count(*) as calls by tool
| sort calls desc
```

Full agent setup (IAM policy, install, config, AL2023 `rsyslog` note) is in
[`deploy/CLOUDWATCH_LOGS.md`](deploy/CLOUDWATCH_LOGS.md).

---

## Continuous integration & delivery (GitHub Actions)

[`.github/workflows/build.yml`](.github/workflows/build.yml) has two jobs:

**`build`** — checks out the code, sets up **JDK 21** (Temurin) with a Maven
cache, runs `./mvnw clean package` (**builds the jar and runs the tests**), and
uploads the jar as a downloadable artifact (`demo-jar`). No AWS is needed — with
`MCP_SECRET_NAME` unset the Spring context loads using the `dev-local-key`
fallback.

**`docker`** — runs after `build` succeeds. Builds the container image from the
multi-stage `Dockerfile` and, on the default branch or a version tag, pushes it to
**GitHub Container Registry (GHCR)**, tagged with the commit SHA, the git tag (on
`v*` pushes), and `latest` (on the default branch):

```bash
docker pull ghcr.io/mergeasergiu/springbootmcpserver:latest
```

**When it runs:**

| Event | Build + test | Image build | Publish to GHCR |
|-------|:---:|:---:|:---:|
| Pull request | ✅ | ✅ | ❌ |
| Push to `master` | ✅ | ✅ | ✅ |
| Push tag `v*` | ✅ | ✅ | ✅ |
| Docs-only change (`**.md`, `docs/**`) | ❌ | ❌ | ❌ |

Feature branches are validated through their PR; only `master` and version tags
publish an image.

---

## Testing & rollout phases

The project was built up in three stages. Run local commands from the project root
(`demo/`).

### Phase 1 — Plain HTTP (app only)

Prove the app and its MCP tools work with **no TLS and no proxy** — the app listens
directly on port 8080.

```bash
./mvnw clean package
java -jar target/demo-0.0.1-SNAPSHOT.jar        # http://localhost:8080
```

Verify:

```bash
curl http://localhost:8080/actuator/health                       # {"status":"UP"}
curl -i http://localhost:8080/mcp                                # 401 unauthorized
curl -i http://localhost:8080/mcp -H "X-API-Key: dev-local-key"  # passes the filter
```

Then point an MCP client at `http://localhost:8080/mcp` with the key and call a
tool (e.g. `sailpoint-overview`).

> Traffic is unencrypted and the port is public — fine for local dev, not for a
> network. Phase 2 fixes that.

### Phase 2 — HTTPS via nginx reverse proxy

Put an **nginx reverse proxy with HTTPS** in front of the app using Docker Compose:

```
client ──HTTPS :443──▶ nginx ──HTTP :8080 (internal only)──▶ Spring Boot app
```

Generate a self-signed cert, then start the stack:

```bash
mkdir -p nginx/certs
openssl req -x509 -newkey rsa:2048 -nodes \
  -keyout nginx/certs/key.pem -out nginx/certs/cert.pem \
  -days 365 -subj "/CN=localhost" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"

docker compose up --build      # add -d to run detached
```

Verify (`-k` accepts the self-signed cert):

```bash
curl -k https://localhost/actuator/health              # {"status":"UP"}
curl -kI http://localhost/actuator/health              # 301 -> https
curl -k -i https://localhost/mcp                       # 401 unauthorized
curl -k -i https://localhost/mcp -H "X-API-Key: dev-local-key"
curl http://localhost:8080/actuator/health             # FAILS (app not public) — good
```

Full walkthrough: [`deploy/LOCAL_NGINX.md`](deploy/LOCAL_NGINX.md).

#### Why the nginx config looks the way it does

- **`proxy_buffering off;` + `proxy_read_timeout 3600s;`** on `/mcp` — the MCP
  streamable protocol uses long-lived streaming responses; a buffering proxy or a
  short timeout would break streaming.
- **`limit_req` on `/mcp`** — cheap protection for the public endpoint.
- **`X-Forwarded-*` headers** — pass the real client IP and scheme to the app.

### Phase 3 — Production on AWS EC2

The app runs on an EC2 instance (Amazon Linux 2023, region `us-east-1`) as a
**systemd service** (`/opt/mcp/app.jar`, unit at
[`deploy/mcp.service`](deploy/mcp.service)) behind nginx on 443. In this phase:

- The API key comes from **Secrets Manager** (`MCP_SECRET_NAME=mcp/api-key`) via the
  instance role — see [AWS cloud integration](#aws-cloud-integration).
- Logs flow to **CloudWatch** (`LOGGING_FILE_NAME=/var/log/mcp/app.log` + the agent).

**Redeploy the jar:**

```bash
./mvnw clean package
scp -i <key.pem> target/demo-0.0.1-SNAPSHOT.jar ec2-user@<host>:/home/ec2-user/
ssh -i <key.pem> ec2-user@<host>
sudo mv /home/ec2-user/demo-0.0.1-SNAPSHOT.jar /opt/mcp/app.jar
sudo systemctl restart mcp
sudo systemctl status mcp --no-pager
```

The EC2 systemd + port setup is documented in
[`deploy/DEPLOY.md`](deploy/DEPLOY.md); the CloudWatch agent in
[`deploy/CLOUDWATCH_LOGS.md`](deploy/CLOUDWATCH_LOGS.md).

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
> list if you don't need them. In production this data is also visible in the app
> logs shipped to CloudWatch, so treat both accordingly.

---

## Project layout

```
demo/
├── .github/workflows/build.yml                 # CI: build + test, upload jar artifact
├── src/main/java/dev/sergiu/demo/
│   ├── DemoApplication.java                     # Spring Boot entrypoint
│   ├── SailPointComponent/SailPointTool.java    # SailPoint MCP tools
│   ├── CertificationsType/CertificationTool.java# IdentityIQ certification MCP tools
│   ├── Workflows/WorkflowTool.java              # SailPoint workflow MCP tools
│   └── security/
│       ├── ApiKeyFilter.java                    # API-key filter guarding /mcp
│       ├── ApiKeyHolder.java                    # holds the resolved API key
│       └── SecretsManagerConfig.java            # fetches the key from AWS Secrets Manager
├── src/main/resources/application.yaml          # app + MCP + actuator + logging config
├── Dockerfile                                   # multi-stage build (Maven -> JRE 21)
├── docker-compose.yml                           # app + nginx orchestration
├── nginx/
│   ├── nginx.conf                               # reverse proxy config
│   └── certs/                                   # cert.pem + key.pem (generated)
├── deploy/
│   ├── DEPLOY.md                                # EC2: run the JAR as a systemd service
│   ├── LOCAL_NGINX.md                           # Local HTTPS via nginx (Docker Compose)
│   ├── CLOUDWATCH_LOGS.md                        # CloudWatch agent setup (OS + app logs)
│   └── mcp.service                              # systemd unit (Secrets Manager + logging env)
└── README.md
```

---

## Stack summary

A wrap-up of everything used to build, secure, deploy, and operate this project,
grouped by layer.

| Layer | Technology | Role in this project |
|-------|------------|----------------------|
| **Language & build** | Java 21, Maven (wrapper) | Application language; reproducible builds via `mvnw` |
| **Framework** | Spring Boot 4.1, Spring Boot Actuator | Web app, dependency injection, health/metrics endpoints |
| **AI / protocol** | Spring AI 2.0, Model Context Protocol (streamable HTTP) | Registers and serves the MCP tools over `/mcp` |
| **Secrets** | AWS Secrets Manager + AWS SDK for Java 2.x | Stores the API key; fetched at startup, no static credentials |
| **Compute** | AWS EC2 (Amazon Linux 2023), systemd | Runs the jar as a managed service |
| **Identity** | AWS IAM instance role (`mcp-ec2-role`) | Grants the instance read access to the secret |
| **Reverse proxy / TLS** | nginx 1.27 | Terminates HTTPS, rate-limits, proxies to the app |
| **Containers** | Docker (multi-stage), Docker Compose | Local HTTPS stack and reproducible image builds |
| **Logging / monitoring** | AWS CloudWatch + CloudWatch agent, ECS-JSON structured logging | Ships OS and app logs off-box; queryable in Logs Insights |
| **CI / CD** | GitHub Actions + GHCR | Builds & tests on push/PR; publishes the Docker image on `master`/tags |
| **Security** | API-key filter (constant-time compare), TLS, nginx rate limiting | Defense in depth around the public endpoint |

**In one sentence:** a Java 21 / Spring Boot + Spring AI MCP server, containerized
with Docker, deployed to AWS EC2 behind nginx HTTPS, with its secret in AWS Secrets
Manager (read via an IAM instance role), logs in AWS CloudWatch, and CI/CD on
GitHub Actions that publishes a Docker image to GHCR.

---

## Further deployment

- **[`deploy/DEPLOY.md`](deploy/DEPLOY.md)** — run the JAR as a systemd service on
  EC2 (Amazon Linux 2023).
- **[`deploy/LOCAL_NGINX.md`](deploy/LOCAL_NGINX.md)** — the full nginx + HTTPS
  reverse-proxy walkthrough with Docker Compose.
- **[`deploy/CLOUDWATCH_LOGS.md`](deploy/CLOUDWATCH_LOGS.md)** — ship OS and app
  logs to CloudWatch with the CloudWatch agent.

Promoting the local nginx setup to a real server means pointing a domain at the
instance, replacing the self-signed cert with a Let's Encrypt cert (auto-renewing,
removes the browser warning), and closing port 8080 so only 443 is public. The
`nginx.conf` structure carries over unchanged.

### Possible next steps

- Externalize the tool knowledge from hardcoded Java into data files.
- Add unit/integration tests for the tools and wire them into CI.
- Extend CI into CI/CD — auto-deploy the built jar to EC2 (e.g. via SSM).
- Real HTTPS via ACM + ALB, or Let's Encrypt on nginx.
```
