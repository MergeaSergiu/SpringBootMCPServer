# Local Nginx Reverse Proxy — step-by-step guide

Goal: run the MCP server behind an **nginx reverse proxy with HTTPS**, entirely on
your own machine with Docker Compose. When you finish, traffic flows:

```
client ──HTTPS :443──▶ nginx ──HTTP :8080 (internal only)──▶ Spring Boot app
```

The app is no longer reachable directly — nginx is the only public door, and it
terminates TLS. This is the local, zero-cost version of "Phase 2" in `DEPLOY.md`.

> **Cost:** $0. nginx, the self-signed certificate, and Docker Desktop (personal
> use) are all free, running on your own hardware.

---

## 0. Prerequisites

- **Docker Desktop** installed and running (`docker --version` works).
- A terminal. Commands below use **Git Bash** (comes with Git for Windows) because
  it ships `openssl`. If you prefer, there's a Docker-only cert command in Step 2
  that needs no local openssl.
- Run every command from the project root: `C:\SpringBoot_AI\demo`.

Verify Docker is up:

```bash
docker version
docker compose version
```

---

## 1. Understand the final layout

You will create **three new files** (nothing existing is modified):

```
demo/
├── docker-compose.yml        # NEW — orchestrates app + nginx
├── nginx/
│   ├── nginx.conf            # NEW — reverse proxy config
│   └── certs/
│       ├── cert.pem          # NEW — self-signed TLS cert (generated)
│       └── key.pem           # NEW — its private key (generated)
├── Dockerfile                # unchanged
└── src/ ...                  # unchanged
```

Create the folders first:

```bash
mkdir -p nginx/certs
```

---

## 2. Generate a self-signed TLS certificate

This certificate is what lets nginx serve HTTPS. It's self-signed, so browsers and
curl will warn that it's untrusted — that's expected locally. On a real server you'd
swap this for a free Let's Encrypt cert (see the note at the end).

**Option A — Git Bash (has openssl):**

```bash
openssl req -x509 -newkey rsa:2048 -nodes \
  -keyout nginx/certs/key.pem \
  -out nginx/certs/cert.pem \
  -days 365 \
  -subj "/CN=localhost" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"
```

**Option B — Docker only (no local openssl needed):**

```bash
docker run --rm -v "$(pwd)/nginx/certs:/certs" alpine/openssl \
  req -x509 -newkey rsa:2048 -nodes \
  -keyout /certs/key.pem -out /certs/cert.pem \
  -days 365 -subj "/CN=localhost" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"
```

Confirm both files exist:

```bash
ls -l nginx/certs
# cert.pem  key.pem
```

**Impact:** HTTPS is now possible. The API key you send will be encrypted in
transit instead of traveling in plaintext as it does on bare port 8080 today.

---

## 3. Create the nginx config

Create `nginx/nginx.conf` with exactly this content:

```nginx
worker_processes auto;
events { worker_connections 1024; }

http {
    # Basic rate limit for the MCP endpoint: 10 req/s per client IP.
    limit_req_zone $binary_remote_addr zone=mcp:10m rate=10r/s;

    # The app service, resolved by its Docker Compose service name.
    upstream app {
        server app:8080;
    }

    # Redirect plain HTTP to HTTPS.
    server {
        listen 80;
        server_name localhost;
        return 301 https://$host$request_uri;
    }

    server {
        listen 443 ssl;
        server_name localhost;

        ssl_certificate     /etc/nginx/certs/cert.pem;
        ssl_certificate_key /etc/nginx/certs/key.pem;

        # The MCP endpoint. proxy_buffering off is ESSENTIAL — the MCP
        # "streamable" protocol uses long-lived streaming responses that a
        # buffering proxy would break.
        location /mcp {
            limit_req zone=mcp burst=20 nodelay;
            proxy_pass http://app;
            proxy_http_version 1.1;
            proxy_set_header Connection "";
            proxy_buffering off;
            proxy_read_timeout 3600s;
            proxy_set_header Host              $host;
            proxy_set_header X-Real-IP         $remote_addr;
            proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;
        }

        # Keep the health check reachable (used by orchestrators / your own checks).
        location = /actuator/health {
            proxy_pass http://app;
        }
    }
}
```

**Impact:** nginx now owns TLS and forwards `/mcp` and the health endpoint to the
app. `proxy_buffering off` + the long read timeout keep MCP streaming working.
The rate limit gives you cheap protection on a public endpoint.

---

## 4. Create the Docker Compose file

Create `docker-compose.yml` in the project root with this content:

```yaml
services:
  app:
    build: .                     # uses the existing Dockerfile
    environment:
      MCP_API_KEY: "dev-local-key"   # change to any secret you like
    expose:
      - "8080"                   # visible to nginx only — NOT published to host
    networks:
      - mcpnet
    restart: unless-stopped

  nginx:
    image: nginx:1.27-alpine
    ports:
      - "80:80"                  # redirects to 443
      - "443:443"                # the only public door
    volumes:
      - ./nginx/nginx.conf:/etc/nginx/nginx.conf:ro
      - ./nginx/certs:/etc/nginx/certs:ro
    depends_on:
      - app
    networks:
      - mcpnet
    restart: unless-stopped

networks:
  mcpnet:
```

**Impact — the key security win:** the `app` service uses `expose` (internal to the
Docker network) instead of `ports` (published to your host). The app becomes
unreachable directly; every request must go through nginx and its TLS. nginx
reaches it as `app:8080` because Compose resolves service names on `mcpnet`.

---

## 5. Build and run

```bash
docker compose up --build
```

First run builds the app image (Maven downloads dependencies — a few minutes).
When it settles you'll see both `app` and `nginx` running. Leave this terminal
open to watch logs, or add `-d` to run detached.

---

## 6. Verify it works

Open a **second** terminal (project root).

**a) Health through nginx over HTTPS** (`-k` accepts the self-signed cert):

```bash
curl -k https://localhost/actuator/health
# {"status":"UP"}
```

**b) HTTP redirects to HTTPS:**

```bash
curl -kI http://localhost/actuator/health
# HTTP/1.1 301 Moved Permanently   → Location: https://localhost/...
```

**c) MCP endpoint rejects a request with no API key** (proves the filter still
runs behind the proxy):

```bash
curl -k -i https://localhost/mcp
# HTTP/1.1 401 Unauthorized ... {"error":"unauthorized"}
```

**d) MCP endpoint accepts the key** (the key now travels encrypted):

```bash
curl -k -i https://localhost/mcp -H "X-API-Key: dev-local-key"
# 401 is gone — you get an MCP protocol response instead.
# (A full MCP session needs an MCP client; this just proves auth passes.)
```

**e) Confirm the app is NOT reachable directly** — this should now FAIL:

```bash
curl http://localhost:8080/actuator/health
# connection refused — exactly what you want. Only nginx is exposed.
```

If (a)–(d) behave and (e) is refused, the reverse proxy is doing its job.

---

## 7. Stop / tear down

```bash
docker compose down          # stop and remove containers
docker compose down -v       # also remove the network/volumes
```

Your files and certs stay on disk; `docker compose up` brings it back.

---

## Troubleshooting

| Symptom | Likely cause / fix |
|---------|--------------------|
| `bind: address already in use` on 443/80 | Another process (or a previous run) holds the port. `docker compose down`, or change the host port (e.g. `8443:443`) and test `https://localhost:8443`. |
| `502 Bad Gateway` from nginx | App isn't up yet or crashed. Check `docker compose logs app`. nginx starts faster than the app on first build — retry after the app logs "Started". |
| MCP stream hangs or truncates | `proxy_buffering off` missing or misplaced in `nginx.conf`. |
| curl `SSL certificate problem` | Expected with a self-signed cert — use `-k`. |
| nginx exits immediately | Config typo. `docker compose logs nginx` shows the failing line. |

---

## What this changed, in one paragraph

You added a TLS-terminating reverse proxy in front of the app and stopped exposing
the app directly. The API key is now encrypted in transit, the public surface is a
single hardened endpoint with rate limiting, and the app process is only reachable
through nginx. The app's own API-key filter is unchanged and still authenticates —
nginx complements it, it doesn't replace it.

---

## Later: promoting this to EC2 (real HTTPS)

The only differences on a real server:

1. Point a **domain** at the instance.
2. Replace the self-signed cert with a free **Let's Encrypt** cert (`certbot`),
   which auto-renews and removes the browser warning.
3. Close port **8080** in the security group so only 443 is public.

Everything else — the `nginx.conf` structure and the "app internal only" principle —
carries over unchanged. See `DEPLOY.md` Phase 2.
