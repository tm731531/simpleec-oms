# Quick Start (30-Minute Setup)

This guide takes you from zero to a fully running SimpleEC OMS stack on a local machine.

**Prerequisites:** Ensure you have met all requirements in [prerequisites.md](prerequisites.md)
before proceeding.

---

## Step 1 — Clone the Repository

```bash
git clone git@github.com:tm731531/simpleec-oms.git
cd simpleec-oms

# Initialize submodules (user-app frontend is a git submodule)
git submodule update --init --recursive
```

After this step you should see the `user-app/` directory populated with source files.

---

## Step 2 — Configure Environment Variables

```bash
cp .env.example .env
```

Open `.env` in your editor and set at minimum:

```dotenv
# Required for all environments
DB_PASSWORD=<your-database-password>

# Required for production — generate with: openssl rand -base64 64
JWT_SECRET=<256-bit-or-longer-random-string>

# Update to match your actual frontend domain(s)
CORS_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:8084
```

The `.env.example` file contains every available key with documentation comments and safe
development defaults. Values marked `# CHANGE IN PRODUCTION` must be replaced before deploying
to any internet-accessible environment.

> **Security:** `.env` is listed in `.gitignore` and must never be committed to version control.

---

## Step 3 — Build All Modules

```bash
./gradlew clean build -x test
```

This compiles all 11 Gradle modules and produces the JAR files that Docker will package.
The `-x test` flag skips the test phase (the project currently has 0 test files and this
avoids any test infrastructure dependencies).

**Expected output (final lines):**

```
BUILD SUCCESSFUL in Xm Xs
```

If the build fails, see the [Troubleshooting](#troubleshooting) section at the bottom of this
page.

---

## Step 4 — Start All Services

```bash
docker compose up -d --build
```

This builds Docker images from the freshly compiled JARs and starts all 26 containers. On
first run, Docker will also pull base images (PostgreSQL, Redis, Kafka, Grafana, etc.),
which may take a few minutes depending on your connection speed.

**Wait 2–3 minutes** after the command returns. Services initialize in dependency order:
PostgreSQL and Redis start first, then Kafka, then the Spring Boot applications.

---

## Step 5 — Verify the Stack

Run these checks to confirm everything is healthy:

```bash
# All 26 containers should show "Up" status
docker compose ps

# API health check — should return {"status":"UP"} or similar
curl http://localhost:8082/api/health
```

Open these URLs in your browser:

| URL | What you should see |
|---|---|
| http://localhost:8082/api/health | JSON health response |
| http://localhost:8088 | Kafka UI — topic list and consumer groups |
| http://localhost:3000 | Grafana login (default: admin/admin) |
| http://localhost:8090 | Merchant frontend (user-app) |
| http://localhost:8089 | Admin frontend (admin-app) |

---

## Step 6 — First Login

### Admin Portal (admin-app)

```
POST http://localhost:8082/api/admin/auth/login
Content-Type: application/json

{
  "username": "admin",
  "password": "<from seed data>"
}
```

### Merchant Portal (user-app)

```
POST http://localhost:8082/api/auth/login
Content-Type: application/json

{
  "email": "admin@a00000.com",
  "password": "pass123456"
}
```

The seed data creates merchant `a00000` with the email above. The admin password is set in
the database initialization scripts under `docker/init-db/`.

Both endpoints return a JWT token. Include it in subsequent requests as:

```
Authorization: Bearer <token>
```

---

## Development Workflow: Quick Redeploy

After making code changes, you do not need to restart the entire stack. Use the quick-redeploy
script to rebuild and restart only the changed service in 30–60 seconds:

```bash
# Rebuild and restart a single service
./quick-redeploy.sh simpleec-api

# Rebuild and restart multiple services at once
./quick-redeploy.sh simpleec-channel-job simpleec-order-job

# See all available service names
./quick-redeploy.sh --list
```

Always run `./gradlew clean build -x test` first to recompile before redeploying.

---

## Troubleshooting

### "Cannot connect to Kafka" in application logs

Kafka initialization takes longer than other services. Wait an additional 30 seconds and check:

```bash
docker compose logs simpleec-kafka | tail -20
```

Look for `Kafka Server started` in the output. If Kafka is still starting, the other services
will retry automatically once it is ready.

### BUILD FAILED during `./gradlew clean build`

Verify you are running Java 17:

```bash
java -version
# Must show: openjdk version "17.x.x"
```

If you have multiple JDK versions installed, set `JAVA_HOME` explicitly:

```bash
export JAVA_HOME=/path/to/jdk-17
./gradlew clean build -x test
```

### A container keeps restarting

Check the container logs for the root cause:

```bash
docker compose logs <service-name> --tail=50
```

Common causes:
- **Database connection refused**: PostgreSQL hasn't finished initializing yet. Wait 30 seconds and try `docker compose restart <service-name>`.
- **Missing environment variable**: An env var in `.env` is empty or misspelled. Check the variable reference in [environment.md](environment.md).
- **Port already in use**: Another process is bound to the same port. Run `lsof -i :<port>` to identify and stop it.

### Frontend shows blank page or API errors

Verify CORS is configured correctly in `.env`:

```dotenv
CORS_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:8084,http://localhost:8090,http://localhost:8089
```

Then redeploy the API service:

```bash
./quick-redeploy.sh simpleec-api
```

### Container exits immediately with "out of memory"

Increase Docker's memory allocation in Docker Desktop settings to at least 12 GB, then
restart the stack:

```bash
docker compose down
docker compose up -d --build
```

---

## What's Next?

- Read the [Architecture Overview](../02-architecture/overview.md) to understand how the
  components interact.
- See [Environment Variables](environment.md) for a complete reference of all configuration
  options.
- See [HANDLER_REGISTRY.md](../../3-EVENT-FLOW/HANDLER_REGISTRY.md) to understand what
  TaskTypes are available and which handlers process them.
- See [CHANNEL_IMPLEMENTATION_GUIDE.md](../../7-IMPLEMENTATION/CHANNEL_IMPLEMENTATION_GUIDE.md)
  to add support for a new e-commerce platform.
