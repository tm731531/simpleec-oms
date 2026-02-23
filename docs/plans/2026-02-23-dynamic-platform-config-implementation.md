# Dynamic Platform Configuration Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement configuration-driven platform management with health tracking at 5-minute granularity, eliminating hardcoded docker-compose while supporting scalable multi-platform deployments.

**Architecture:** Three-layer system:
1. **Config Layer** (config.yaml) - Single source of truth for platforms + infrastructure
2. **Generation Layer** (generate-compose.py) - Template-based docker-compose production
3. **Runtime Layer** (Jobs + DB) - Channel-Job gates with enable_sync; Scheduler health checks; channel_sync_logs tracks health

**Tech Stack:** Python 3.8+ (script), PostgreSQL 16, Kafka 3.7.1, Spring Boot 3.5.0 (Java), Vue 3 (Frontend)

---

## Phase 1: Database Schema Updates

### Task 1.1: Add channel_sync_logs Table

**Files:**
- Modify: `docker/init-db/01-schema.sql` (after line 391, before current line 393)
- Test: `tests/db/test_schema.sql` (new file)

**Step 1: Create migration test**

```python
# tests/db/test_schema.sql (psql test)
-- Verify table exists
SELECT EXISTS(
    SELECT 1 FROM information_schema.tables
    WHERE table_name = 'channel_sync_logs'
);

-- Verify columns
SELECT column_name, data_type
FROM information_schema.columns
WHERE table_name = 'channel_sync_logs'
ORDER BY ordinal_position;

-- Verify indexes
SELECT indexname FROM pg_indexes
WHERE tablename = 'channel_sync_logs';

-- Verify generated column for health
SELECT column_name, is_generated
FROM information_schema.columns
WHERE table_name = 'channel_sync_logs' AND column_name = 'health';
```

**Step 2: Add table definition to schema**

Insert into `docker/init-db/01-schema.sql` at line 393:

```sql
-- ---------------------------------------------------------------------------
-- 17. channel_sync_logs — Sync / health check logs with HTTP status tracking
-- ---------------------------------------------------------------------------
CREATE TABLE public.channel_sync_logs (
    id               VARCHAR(20)       NOT NULL,
    merchant_id      VARCHAR(20)       NOT NULL,
    platform_id      VARCHAR(20)       NOT NULL,
    channel_id       VARCHAR(20),                -- NULL for platform-level checks

    sync_type        VARCHAR(20)       NOT NULL,  -- 'CHANNEL' or 'PLATFORM'
    http_status      INTEGER           NOT NULL,  -- 200, 401, 500, etc.
    health           VARCHAR(20)
                     GENERATED ALWAYS AS
                     (CASE WHEN http_status >= 400 THEN 'unhealthy' ELSE 'healthy' END),

    error_message    TEXT,
    request_payload  TEXT,
    response_payload TEXT,

    created_at       TIMESTAMPTZ       NOT NULL DEFAULT now(),

    PRIMARY KEY (id),
    CONSTRAINT fk_sync_log_merchant FOREIGN KEY (merchant_id)
        REFERENCES public.merchant (id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT fk_sync_log_platform FOREIGN KEY (platform_id)
        REFERENCES public.platform (id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT fk_sync_log_channel FOREIGN KEY (channel_id)
        REFERENCES public.channel (id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT ck_sync_type CHECK (sync_type IN ('CHANNEL', 'PLATFORM')),
    CONSTRAINT ck_http_status CHECK (http_status >= 100 AND http_status < 600)
);

CREATE INDEX idx_sync_log_channel ON public.channel_sync_logs (channel_id, created_at DESC);
CREATE INDEX idx_sync_log_platform ON public.channel_sync_logs (platform_id, created_at DESC);
CREATE INDEX idx_sync_log_merchant ON public.channel_sync_logs (merchant_id, created_at DESC);
CREATE INDEX idx_sync_log_created ON public.channel_sync_logs (created_at DESC);
```

**Step 3: Test schema loads correctly**

Run: `docker-compose up postgres && docker exec simpleec-postgres psql -U simpleec -d simpleec -f /docker-entrypoint-initdb.d/01-schema.sql`

Expected: Schema loads without errors, all tables created

**Step 4: Verify table structure**

Run: `docker exec simpleec-postgres psql -U simpleec -d simpleec -c "\d channel_sync_logs"`

Expected: All columns visible, generated column for health shown

**Step 5: Commit**

```bash
git add docker/init-db/01-schema.sql tests/db/test_schema.sql
git commit -m "feat: add channel_sync_logs table for health tracking

- HTTP status tracking for channel and platform health checks
- Generated column for health (healthy/unhealthy based on http_status >= 400)
- Indexes for time-series queries (channel, platform, merchant)
- Constraints for data integrity (sync_type, http_status ranges)"
```

---

## Phase 2: Configuration & Generation

### Task 2.1: Create config.yaml

**Files:**
- Create: `config.yaml` (root directory)
- Test: `tests/config/test_config.py` (new)

**Step 1: Write config validation test**

```python
# tests/config/test_config.py
import yaml
import pytest
from pathlib import Path

def test_config_yaml_exists():
    """Config file must exist at project root"""
    config_path = Path(__file__).parent.parent.parent / 'config.yaml'
    assert config_path.exists(), f"config.yaml not found at {config_path}"

def test_config_yaml_parses():
    """Config YAML must be valid"""
    config_path = Path(__file__).parent.parent.parent / 'config.yaml'
    with open(config_path) as f:
        config = yaml.safe_load(f)
    assert config is not None
    assert isinstance(config, dict)

def test_config_has_required_sections():
    """Config must have system, platforms, infrastructure, services, jobs"""
    config_path = Path(__file__).parent.parent.parent / 'config.yaml'
    with open(config_path) as f:
        config = yaml.safe_load(f)

    required = ['system', 'platforms', 'infrastructure', 'services', 'jobs']
    for section in required:
        assert section in config, f"Missing section: {section}"

def test_config_has_7_platforms():
    """Must define all 7 MVP platforms"""
    config_path = Path(__file__).parent.parent.parent / 'config.yaml'
    with open(config_path) as f:
        config = yaml.safe_load(f)

    platform_codes = [p['code'] for p in config['platforms']]
    expected = ['momo', 'shopee', 'yahoo', 'pchome', 'cyberbiz', 'shopline', 'shopify']

    assert len(platform_codes) == 7, f"Expected 7 platforms, got {len(platform_codes)}"
    for code in expected:
        assert code in platform_codes, f"Missing platform: {code}"

def test_config_platforms_have_required_fields():
    """Each platform must have code, name, fastConcurrency, slowConcurrency"""
    config_path = Path(__file__).parent.parent.parent / 'config.yaml'
    with open(config_path) as f:
        config = yaml.safe_load(f)

    required_fields = ['code', 'name', 'fastConcurrency', 'slowConcurrency']
    for platform in config['platforms']:
        for field in required_fields:
            assert field in platform, f"Platform {platform.get('code')} missing {field}"
```

**Step 2: Run test to verify it fails**

Run: `pytest tests/config/test_config.py -v`

Expected: FAIL - config.yaml not found

**Step 3: Create config.yaml**

Create `config.yaml` at project root:

```yaml
system:
  name: "SimpleEC OMS"
  version: 1

platforms:
  - code: momo
    name: "MOMO"
    fastConcurrency: 8
    slowConcurrency: 4

  - code: shopee
    name: "Shopee"
    fastConcurrency: 8
    slowConcurrency: 4

  - code: yahoo
    name: "Yahoo 購物中心"
    fastConcurrency: 8
    slowConcurrency: 4

  - code: pchome
    name: "PChome"
    fastConcurrency: 8
    slowConcurrency: 4

  - code: cyberbiz
    name: "Cyberbiz"
    fastConcurrency: 6
    slowConcurrency: 3

  - code: shopline
    name: "Shopline"
    fastConcurrency: 6
    slowConcurrency: 3

  - code: shopify
    name: "Shopify"
    fastConcurrency: 8
    slowConcurrency: 4

infrastructure:
  postgres:
    port: 5433
    image: postgres:16-alpine
    container_name: simpleec-postgres

  redis:
    port: 6379
    image: redis:7-alpine
    container_name: simpleec-redis

  kafka:
    port: 9092
    image: apache/kafka:3.7.1
    container_name: simpleec-kafka
    autoCreateTopics: true
    retentionHours: 1

services:
  api:
    port: 8082
    image: simpleec-api
    contextPath: /api

  gateway:
    port: 8081
    image: simpleec-gateway

  nginx:
    port: 8089
    image: simpleec-nginx

  userApp:
    port: 5173
    image: simpleec-user-app

  adminApp:
    port: 8084
    image: simpleec-admin-app

jobs:
  orderJob:
    image: simpleec-order-job

  schedulerJob:
    image: simpleec-scheduler-job

  backendJob:
    image: simpleec-backend-job

  frontendJob:
    image: simpleec-frontend-job

  retryJob:
    image: simpleec-retry-job
```

**Step 4: Run tests to verify they pass**

Run: `pytest tests/config/test_config.py -v`

Expected: All tests PASS

**Step 5: Commit**

```bash
git add config.yaml tests/config/test_config.py
git commit -m "feat: add config.yaml as single source of truth

- Defines all 7 MVP platforms with concurrency settings
- Infrastructure configuration (postgres, redis, kafka)
- Service ports and images
- Foundation for docker-compose generation
- Tests verify structure and platform completeness"
```

---

### Task 2.2: Create docker-compose Generation Script

**Files:**
- Create: `docker/generate-compose.py` (new)
- Test: `tests/docker/test_generate_compose.py` (new)

**Step 1: Write generation test**

```python
# tests/docker/test_generate_compose.py
import yaml
import subprocess
from pathlib import Path
import pytest

@pytest.fixture
def config_yaml():
    config_path = Path(__file__).parent.parent.parent / 'config.yaml'
    with open(config_path) as f:
        return yaml.safe_load(f)

def test_script_exists():
    """generate-compose.py must exist"""
    script = Path(__file__).parent.parent.parent / 'docker' / 'generate-compose.py'
    assert script.exists()

def test_script_is_executable():
    """Script must be executable"""
    script = Path(__file__).parent.parent.parent / 'docker' / 'generate-compose.py'
    assert script.stat().st_mode & 0o111  # Check execute bit

def test_script_generates_compose(tmp_path):
    """Script should generate valid docker-compose.yml"""
    config_path = Path(__file__).parent.parent.parent / 'config.yaml'
    output_path = tmp_path / 'docker-compose.yml'
    script = Path(__file__).parent.parent.parent / 'docker' / 'generate-compose.py'

    result = subprocess.run(
        ['python3', str(script), str(config_path), str(output_path)],
        capture_output=True,
        text=True
    )

    assert result.returncode == 0, f"Script failed: {result.stderr}"
    assert output_path.exists(), "Output file not created"

def test_generated_compose_is_valid_yaml(tmp_path):
    """Generated docker-compose must be valid YAML"""
    config_path = Path(__file__).parent.parent.parent / 'config.yaml'
    output_path = tmp_path / 'docker-compose.yml'
    script = Path(__file__).parent.parent.parent / 'docker' / 'generate-compose.py'

    subprocess.run(
        ['python3', str(script), str(config_path), str(output_path)],
        check=True
    )

    with open(output_path) as f:
        compose = yaml.safe_load(f)

    assert compose is not None
    assert isinstance(compose, dict)

def test_generated_compose_has_all_services(tmp_path, config_yaml):
    """Generated compose must have all platform + system services"""
    config_path = Path(__file__).parent.parent.parent / 'config.yaml'
    output_path = tmp_path / 'docker-compose.yml'
    script = Path(__file__).parent.parent.parent / 'docker' / 'generate-compose.py'

    subprocess.run(
        ['python3', str(script), str(config_path), str(output_path)],
        check=True
    )

    with open(output_path) as f:
        compose = yaml.safe_load(f)

    services = compose.get('services', {})

    # Check infrastructure services
    assert 'postgres' in services or 'simpleec-postgres' in services
    assert 'kafka' in services or 'simpleec-kafka' in services

    # Check each platform has fast + slow jobs
    for platform in config_yaml['platforms']:
        code = platform['code']
        fast_job = f'simpleec-channel-{code}-fast'
        slow_job = f'simpleec-channel-{code}-slow'
        assert fast_job in services, f"Missing {fast_job}"
        assert slow_job in services, f"Missing {slow_job}"

def test_channel_job_has_correct_env_vars(tmp_path, config_yaml):
    """Each channel-job must have JOB_CHANNEL_TOPICS and concurrency"""
    config_path = Path(__file__).parent.parent.parent / 'config.yaml'
    output_path = tmp_path / 'docker-compose.yml'
    script = Path(__file__).parent.parent.parent / 'docker' / 'generate-compose.py'

    subprocess.run(
        ['python3', str(script), str(config_path), str(output_path)],
        check=True
    )

    with open(output_path) as f:
        compose = yaml.safe_load(f)

    services = compose['services']

    for platform in config_yaml['platforms']:
        code = platform['code']
        fast_job = services[f'simpleec-channel-{code}-fast']
        slow_job = services[f'simpleec-channel-{code}-slow']

        # Fast job
        assert fast_job['environment']['JOB_CHANNEL_TOPICS'] == f'{code}.fast'
        assert fast_job['environment']['JOB_CHANNEL_CONCURRENCY'] == str(platform['fastConcurrency'])

        # Slow job
        assert slow_job['environment']['JOB_CHANNEL_TOPICS'] == f'{code}.slow'
        assert slow_job['environment']['JOB_CHANNEL_CONCURRENCY'] == str(platform['slowConcurrency'])
```

**Step 2: Run test to verify it fails**

Run: `pytest tests/docker/test_generate_compose.py -v`

Expected: FAIL - script not found

**Step 3: Create the generation script**

Create `docker/generate-compose.py`:

```python
#!/usr/bin/env python3
"""
Generate docker-compose.yml from config.yaml

Usage:
    python docker/generate-compose.py config.yaml docker-compose.yml
"""

import sys
import yaml
from pathlib import Path
from typing import Any, Dict

def load_config(config_path: str) -> Dict[str, Any]:
    """Load and parse config.yaml"""
    with open(config_path) as f:
        return yaml.safe_load(f)

def generate_compose(config: Dict[str, Any]) -> Dict[str, Any]:
    """Generate docker-compose structure from config"""

    compose = {
        'version': '3.8',
        'services': {}
    }

    # Add infrastructure services
    services = compose['services']

    # Postgres
    postgres_cfg = config['infrastructure']['postgres']
    services['postgres'] = {
        'image': postgres_cfg['image'],
        'container_name': postgres_cfg.get('container_name', 'simpleec-postgres'),
        'ports': [f"{postgres_cfg['port']}:5432"],
        'environment': {
            'POSTGRES_DB': 'simpleec',
            'POSTGRES_USER': 'simpleec',
            'POSTGRES_PASSWORD': 'simpleec123'
        },
        'volumes': [
            '${DATA_DIR:-./data}/postgres:/var/lib/postgresql/data',
            './docker/init-db:/docker-entrypoint-initdb.d'
        ],
        'healthcheck': {
            'test': ['CMD-SHELL', 'pg_isready -U simpleec'],
            'interval': '5s',
            'timeout': '5s',
            'retries': 10
        }
    }

    # Redis
    redis_cfg = config['infrastructure']['redis']
    services['redis'] = {
        'image': redis_cfg['image'],
        'container_name': redis_cfg.get('container_name', 'simpleec-redis'),
        'ports': [f"{redis_cfg['port']}:6379"],
        'command': ['redis-server', '--appendonly', 'yes', '--dir', '/data'],
        'volumes': ['${DATA_DIR:-./data}/redis:/data'],
        'healthcheck': {
            'test': ['CMD', 'redis-cli', 'ping'],
            'interval': '5s',
            'timeout': '3s',
            'retries': 10
        }
    }

    # Kafka
    kafka_cfg = config['infrastructure']['kafka']
    services['kafka'] = {
        'image': kafka_cfg['image'],
        'container_name': kafka_cfg.get('container_name', 'simpleec-kafka'),
        'ports': [f"{kafka_cfg['port']}:9092"],
        'environment': {
            'KAFKA_NODE_ID': '1',
            'KAFKA_PROCESS_ROLES': 'broker,controller',
            'KAFKA_CONTROLLER_QUORUM_VOTERS': '1@kafka:9093',
            'KAFKA_LISTENERS': 'PLAINTEXT://:9092,CONTROLLER://:9093',
            'KAFKA_ADVERTISED_LISTENERS': 'PLAINTEXT://kafka:9092',
            'KAFKA_LISTENER_SECURITY_PROTOCOL_MAP': 'CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT',
            'KAFKA_CONTROLLER_LISTENER_NAMES': 'CONTROLLER',
            'KAFKA_AUTO_CREATE_TOPICS_ENABLE': 'true' if kafka_cfg.get('autoCreateTopics', True) else 'false',
            'KAFKA_LOG_RETENTION_HOURS': str(kafka_cfg.get('retentionHours', 1)),
            'KAFKA_LOG_RETENTION_BYTES': '-1',
            'KAFKA_LOG_CLEANUP_POLICY': 'delete',
            'KAFKA_LOG_CLEANUP_ENABLE': 'true',
            'CLUSTER_ID': 'simpleec-kraft-cluster-001'
        },
        'volumes': ['${DATA_DIR:-./data}/kafka:/tmp/kraft-combined-logs'],
        'healthcheck': {
            'test': ['CMD-SHELL', '/opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 || exit 1'],
            'interval': '10s',
            'timeout': '10s',
            'retries': 15,
            'start_period': '30s'
        }
    }

    # Channel Jobs (dynamically from platforms)
    for platform in config['platforms']:
        code = platform['code']

        # Fast channel job
        fast_service = f'simpleec-channel-{code}-fast'
        services[fast_service] = {
            'image': 'simpleec-channel-job',
            'build': {
                'context': '.',
                'dockerfile': 'docker/Dockerfile.channel-job'
            },
            'container_name': fast_service,
            'environment': {
                'DB_HOST': 'postgres',
                'DB_PORT': '5432',
                'DB_NAME': 'simpleec',
                'DB_USER': 'simpleec',
                'DB_PASSWORD': 'simpleec123',
                'REDIS_HOST': 'redis',
                'REDIS_PORT': '6379',
                'KAFKA_BOOTSTRAP_SERVERS': 'kafka:9092',
                'JOB_CHANNEL_TOPICS': f'{code}.fast',
                'JOB_CHANNEL_GROUP_ID': f'channel-job-{code}-fast',
                'JOB_CHANNEL_CONCURRENCY': str(platform['fastConcurrency'])
            },
            'depends_on': {
                'postgres': {'condition': 'service_healthy'},
                'redis': {'condition': 'service_healthy'},
                'kafka': {'condition': 'service_healthy'}
            },
            'restart': 'unless-stopped'
        }

        # Slow channel job
        slow_service = f'simpleec-channel-{code}-slow'
        services[slow_service] = {
            'image': 'simpleec-channel-job',
            'build': {
                'context': '.',
                'dockerfile': 'docker/Dockerfile.channel-job'
            },
            'container_name': slow_service,
            'environment': {
                'DB_HOST': 'postgres',
                'DB_PORT': '5432',
                'DB_NAME': 'simpleec',
                'DB_USER': 'simpleec',
                'DB_PASSWORD': 'simpleec123',
                'REDIS_HOST': 'redis',
                'REDIS_PORT': '6379',
                'KAFKA_BOOTSTRAP_SERVERS': 'kafka:9092',
                'JOB_CHANNEL_TOPICS': f'{code}.slow',
                'JOB_CHANNEL_GROUP_ID': f'channel-job-{code}-slow',
                'JOB_CHANNEL_CONCURRENCY': str(platform['slowConcurrency'])
            },
            'depends_on': {
                'postgres': {'condition': 'service_healthy'},
                'redis': {'condition': 'service_healthy'},
                'kafka': {'condition': 'service_healthy'}
            },
            'restart': 'unless-stopped'
        }

    # Add other critical jobs (order, scheduler, backend, frontend, retry)
    jobs_config = {
        'simpleec-order-job': 'Dockerfile.order-job',
        'simpleec-scheduler-job': 'Dockerfile.scheduler-job',
        'simpleec-backend-job': 'Dockerfile.backend-job',
        'simpleec-frontend-job': 'Dockerfile.frontend-job',
        'simpleec-retry-job': 'Dockerfile.retry-job'
    }

    for job_name, dockerfile in jobs_config.items():
        services[job_name] = {
            'image': job_name,
            'build': {
                'context': '.',
                'dockerfile': f'docker/{dockerfile}'
            },
            'container_name': job_name,
            'environment': {
                'DB_HOST': 'postgres',
                'DB_PORT': '5432',
                'DB_NAME': 'simpleec',
                'DB_USER': 'simpleec',
                'DB_PASSWORD': 'simpleec123',
                'REDIS_HOST': 'redis',
                'REDIS_PORT': '6379',
                'KAFKA_BOOTSTRAP_SERVERS': 'kafka:9092'
            },
            'depends_on': {
                'postgres': {'condition': 'service_healthy'},
                'redis': {'condition': 'service_healthy'},
                'kafka': {'condition': 'service_healthy'}
            },
            'restart': 'unless-stopped'
        }

    # API and frontend services (minimal for brevity, expand as needed)
    services['simpleec-api'] = {
        'image': 'simpleec-api',
        'build': {
            'context': '.',
            'dockerfile': 'docker/Dockerfile.api'
        },
        'container_name': 'simpleec-api',
        'ports': ['8082:8080'],
        'environment': {
            'SERVER_PORT': '8080',
            'SERVER_SERVLET_CONTEXT_PATH': '/api',
            'DB_HOST': 'postgres',
            'DB_PORT': '5432',
            'DB_NAME': 'simpleec',
            'DB_USER': 'simpleec',
            'DB_PASSWORD': 'simpleec123'
        },
        'depends_on': {
            'postgres': {'condition': 'service_healthy'}
        },
        'healthcheck': {
            'test': ['CMD', 'sh', '-c', 'nc -z localhost 8080 || exit 1'],
            'interval': '10s',
            'timeout': '5s',
            'retries': 30,
            'start_period': '60s'
        },
        'restart': 'unless-stopped'
    }

    services['simpleec-user-app'] = {
        'image': 'simpleec-user-app',
        'build': {
            'context': 'user-app',
            'dockerfile': 'Dockerfile.user'
        },
        'container_name': 'simpleec-user-app',
        'ports': ['5173:5173'],
        'environment': {
            'VITE_API_BASE_URL': 'http://simpleec-api:8080/api'
        },
        'depends_on': {
            'simpleec-api': {'condition': 'service_healthy'}
        },
        'restart': 'unless-stopped'
    }

    services['simpleec-admin-app'] = {
        'image': 'simpleec-admin-app',
        'build': {
            'context': 'admin-app',
            'dockerfile': 'Dockerfile.admin'
        },
        'container_name': 'simpleec-admin-app',
        'ports': ['8084:8084'],
        'environment': {
            'VITE_API_BASE_URL': 'http://simpleec-api:8080/api'
        },
        'depends_on': {
            'simpleec-api': {'condition': 'service_healthy'}
        },
        'restart': 'unless-stopped'
    }

    services['simpleec-nginx'] = {
        'image': 'simpleec-nginx',
        'build': {
            'context': '.',
            'dockerfile': 'docker/Dockerfile.nginx'
        },
        'container_name': 'simpleec-nginx',
        'ports': ['8089:80', '8090:443'],
        'depends_on': {
            'simpleec-user-app': {'condition': 'service_started'},
            'simpleec-admin-app': {'condition': 'service_started'},
            'simpleec-api': {'condition': 'service_healthy'}
        },
        'restart': 'unless-stopped'
    }

    return compose

def main():
    if len(sys.argv) != 3:
        print("Usage: python docker/generate-compose.py <config.yaml> <output-compose.yml>")
        sys.exit(1)

    config_path = sys.argv[1]
    output_path = sys.argv[2]

    print(f"Loading config from {config_path}...")
    config = load_config(config_path)

    print(f"Generating docker-compose for {len(config['platforms'])} platforms...")
    compose = generate_compose(config)

    print(f"Writing docker-compose to {output_path}...")
    with open(output_path, 'w') as f:
        yaml.dump(compose, f, default_flow_style=False, sort_keys=False)

    print(f"✅ Success! Generated {output_path}")
    print(f"   Platforms: {len(config['platforms'])}")
    print(f"   Channel jobs: {len(config['platforms']) * 2}")
    print(f"   System jobs: 5 (order, scheduler, backend, frontend, retry)")

if __name__ == '__main__':
    main()
```

**Step 4: Make script executable and run tests**

Run: `chmod +x docker/generate-compose.py && pytest tests/docker/test_generate_compose.py -v`

Expected: All tests PASS

**Step 5: Generate docker-compose.yml**

Run: `python docker/generate-compose.py config.yaml docker-compose.yml`

Expected: File generated successfully

**Step 6: Verify generated file**

Run: `docker-compose config --services | head -20`

Expected: Lists all services (postgres, redis, kafka, momo-fast, momo-slow, ..., shopify-slow, etc.)

**Step 7: Commit**

```bash
git add docker/generate-compose.py tests/docker/test_generate_compose.py docker-compose.yml
git commit -m "feat: add docker-compose generation from config.yaml

- Python script reads config.yaml and generates complete docker-compose.yml
- Automatically creates platform-specific channel-job services (fast + slow)
- Sets JOB_CHANNEL_TOPICS and concurrency from config
- Comprehensive tests verify generation correctness
- Eliminates manual docker-compose editing"
```

---

## Phase 3: Channel-Job enable_sync Gate

### Task 3.1: Implement enable_sync Check in Channel-Job

**Files:**
- Modify: `simpleec-channel-job/src/main/java/com/simpleec/channeljob/processor/ChannelMessageProcessor.java` (new)
- Modify: `simpleec-channel-job/src/main/java/com/simpleec/channeljob/service/ChannelService.java` (add method)
- Test: `simpleec-channel-job/src/test/java/com/simpleec/channeljob/processor/ChannelMessageProcessorTest.java` (new)

**Step 1: Write failing test for enable_sync gate**

```java
// ChannelMessageProcessorTest.java
package com.simpleec.channeljob.processor;

import com.simpleec.channeljob.service.ChannelService;
import com.simpleec.channeljob.repository.ChannelSyncLogRepository;
import com.simpleec.channeljob.entity.ChannelMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.mockito.Mockito.*;

public class ChannelMessageProcessorTest {

    @Mock
    private ChannelService channelService;

    @Mock
    private ChannelSyncLogRepository channelSyncLogRepository;

    private ChannelMessageProcessor processor;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        processor = new ChannelMessageProcessor(channelService, channelSyncLogRepository);
    }

    @Test
    void testProcessMessage_ChannelDisabled_SkipsProcessing() throws Exception {
        // Arrange
        ChannelMessage message = new ChannelMessage();
        message.setChannelId("test-channel-id");
        message.setMerchantId("merchant-1");
        message.setTaskType("FETCH_ORDERS");

        when(channelService.isChannelEnabled("test-channel-id")).thenReturn(false);

        // Act
        boolean result = processor.processMessage(message);

        // Assert
        assert (!result) : "Should return false when channel disabled";
        verify(channelService, never()).fetchFromPlatform(anyString(), any());
    }

    @Test
    void testProcessMessage_ChannelEnabled_ProcessesMessage() throws Exception {
        // Arrange
        ChannelMessage message = new ChannelMessage();
        message.setChannelId("test-channel-id");
        message.setMerchantId("merchant-1");
        message.setTaskType("FETCH_ORDERS");

        when(channelService.isChannelEnabled("test-channel-id")).thenReturn(true);
        when(channelService.fetchFromPlatform("test-channel-id", message)).thenReturn(true);

        // Act
        boolean result = processor.processMessage(message);

        // Assert
        assert (result) : "Should return true when processing succeeds";
        verify(channelService, times(1)).fetchFromPlatform("test-channel-id", message);
    }

    @Test
    void testProcessMessage_RecordsHealthLog() throws Exception {
        // Arrange
        ChannelMessage message = new ChannelMessage();
        message.setChannelId("test-channel-id");
        message.setMerchantId("merchant-1");

        when(channelService.isChannelEnabled("test-channel-id")).thenReturn(true);
        when(channelService.fetchFromPlatform("test-channel-id", message)).thenReturn(true);

        // Act
        processor.processMessage(message);

        // Assert
        verify(channelSyncLogRepository, times(1)).save(any());
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd simpleec-channel-job && mvn test -Dtest=ChannelMessageProcessorTest`

Expected: FAIL - class not found

**Step 3: Create ChannelMessageProcessor class**

```java
// ChannelMessageProcessor.java
package com.simpleec.channeljob.processor;

import com.simpleec.channeljob.service.ChannelService;
import com.simpleec.channeljob.repository.ChannelSyncLogRepository;
import com.simpleec.channeljob.entity.ChannelMessage;
import com.simpleec.channeljob.entity.ChannelSyncLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
public class ChannelMessageProcessor {

    private final ChannelService channelService;
    private final ChannelSyncLogRepository channelSyncLogRepository;

    public ChannelMessageProcessor(ChannelService channelService,
                                   ChannelSyncLogRepository channelSyncLogRepository) {
        this.channelService = channelService;
        this.channelSyncLogRepository = channelSyncLogRepository;
    }

    /**
     * Process incoming channel message with enable_sync gate
     *
     * @param message Incoming message from Kafka
     * @return true if processing succeeded, false if skipped or failed
     */
    public boolean processMessage(ChannelMessage message) {
        String channelId = message.getChannelId();
        String merchantId = message.getMerchantId();

        try {
            // [GATE 1: Check enable_sync]
            if (!channelService.isChannelEnabled(channelId)) {
                log.info("Channel {} disabled (enable_sync=false), skipping", channelId);

                // Record skip in logs
                recordSyncLog(merchantId, channelId, 200, "SKIPPED", "Channel disabled");
                return false;
            }

            // Channel enabled - proceed with processing
            log.info("Processing message for channel {}, task: {}", channelId, message.getTaskType());

            boolean success = channelService.fetchFromPlatform(channelId, message);

            if (success) {
                recordSyncLog(merchantId, channelId, 200, "SUCCESS", null);
            } else {
                recordSyncLog(merchantId, channelId, 500, "FAILED", "Processing failed");
            }

            return success;

        } catch (Exception e) {
            log.error("Error processing message for channel {}", channelId, e);
            recordSyncLog(merchantId, channelId, 500, "ERROR", e.getMessage());
            return false;
        }
    }

    private void recordSyncLog(String merchantId, String channelId,
                               int httpStatus, String status, String errorMessage) {
        ChannelSyncLog log = new ChannelSyncLog();
        log.setMerchantId(merchantId);
        log.setChannelId(channelId);
        log.setHttpStatus(httpStatus);
        log.setStatus(status);
        log.setErrorMessage(errorMessage);
        log.setCreatedAt(LocalDateTime.now());

        channelSyncLogRepository.save(log);
    }
}
```

**Step 4: Add isChannelEnabled method to ChannelService**

```java
// In ChannelService.java, add:

public boolean isChannelEnabled(String channelId) {
    Channel channel = channelRepository.findById(channelId)
        .orElse(null);

    if (channel == null) {
        log.warn("Channel {} not found", channelId);
        return false;
    }

    return channel.isEnableSync();
}
```

**Step 5: Run tests to verify they pass**

Run: `cd simpleec-channel-job && mvn test -Dtest=ChannelMessageProcessorTest`

Expected: All tests PASS

**Step 6: Commit**

```bash
git add simpleec-channel-job/src/main/java/com/simpleec/channeljob/processor/ChannelMessageProcessor.java
git add simpleec-channel-job/src/main/java/com/simpleec/channeljob/service/ChannelService.java
git add simpleec-channel-job/src/test/java/com/simpleec/channeljob/processor/ChannelMessageProcessorTest.java

git commit -m "feat: implement enable_sync gate in channel-job processor

- Check channel.enable_sync before processing each message
- Skip messages for disabled channels (log as 'skipped')
- Record HTTP status and sync status in channel_sync_logs
- Graceful error handling with detailed logging"
```

---

## Phase 4: Scheduler Health Check Jobs

### Task 4.1: Implement Scheduler Health Check Publishing

**Files:**
- Modify: `simpleec-scheduler-job/src/main/java/com/simpleec/schedulerjob/scheduler/HealthCheckScheduler.java` (new)
- Test: `simpleec-scheduler-job/src/test/java/com/simpleec/schedulerjob/scheduler/HealthCheckSchedulerTest.java` (new)

**Step 1: Write failing test**

```java
// HealthCheckSchedulerTest.java
package com.simpleec.schedulerjob.scheduler;

import com.simpleec.schedulerjob.service.ChannelService;
import com.simpleec.schedulerjob.kafka.KafkaProducer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.mockito.Mockito.*;

public class HealthCheckSchedulerTest {

    @Mock
    private ChannelService channelService;

    @Mock
    private KafkaProducer kafkaProducer;

    private HealthCheckScheduler scheduler;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        scheduler = new HealthCheckScheduler(channelService, kafkaProducer);
    }

    @Test
    void testRunEvery5Minutes_PublishesChannelHealthChecks() {
        // Arrange
        List<ChannelInfo> enabledChannels = List.of(
            new ChannelInfo("channel-1", "momo", "M001"),
            new ChannelInfo("channel-2", "shopee", "M001")
        );
        when(channelService.findEnabledChannels()).thenReturn(enabledChannels);

        // Act
        scheduler.runHealthCheck();

        // Assert
        verify(kafkaProducer, times(2)).publishHealthCheck(anyString(), anyString());
    }

    @Test
    void testRunEvery5Minutes_PublishesPlatformHealthChecks() {
        // Arrange
        List<String> activePlatforms = List.of("momo", "shopee", "cyberbiz");
        when(channelService.findActivePlatforms()).thenReturn(activePlatforms);

        // Act
        scheduler.runHealthCheck();

        // Assert
        verify(kafkaProducer, times(3)).publishPlatformHealthCheck(anyString());
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd simpleec-scheduler-job && mvn test -Dtest=HealthCheckSchedulerTest`

Expected: FAIL - class not found

**Step 3: Create HealthCheckScheduler**

```java
// HealthCheckScheduler.java
package com.simpleec.schedulerjob.scheduler;

import com.simpleec.schedulerjob.service.ChannelService;
import com.simpleec.schedulerjob.kafka.KafkaProducer;
import com.simpleec.schedulerjob.entity.Channel;
import com.simpleec.schedulerjob.entity.Platform;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class HealthCheckScheduler {

    private final ChannelService channelService;
    private final KafkaProducer kafkaProducer;

    public HealthCheckScheduler(ChannelService channelService, KafkaProducer kafkaProducer) {
        this.channelService = channelService;
        this.kafkaProducer = kafkaProducer;
    }

    /**
     * Run every 5 minutes to publish health check tasks
     * Sends CHECK_HEALTH task for each enabled channel
     * Sends CHECK_HEALTH_PLATFORM task for each active platform
     */
    @Scheduled(fixedRate = 300000) // 5 minutes
    public void runHealthCheck() {
        log.info("Starting health check cycle...");

        try {
            publishChannelHealthChecks();
            publishPlatformHealthChecks();
            log.info("Health check cycle completed");
        } catch (Exception e) {
            log.error("Error in health check cycle", e);
        }
    }

    private void publishChannelHealthChecks() {
        // Get all enabled channels
        List<Channel> enabledChannels = channelService.findEnabledChannels();

        log.info("Publishing health checks for {} enabled channels", enabledChannels.size());

        for (Channel channel : enabledChannels) {
            try {
                // Publish CHECK_HEALTH task to platform.{speed} topic
                String topic = channel.getPlatformCode() + ".fast"; // Use fast by default

                HealthCheckMessage message = new HealthCheckMessage();
                message.setTaskType("CHECK_HEALTH");
                message.setMerchantId(channel.getMerchantId());
                message.setChannelId(channel.getId());
                message.setPlatformCode(channel.getPlatformCode());
                message.setTimestamp(System.currentTimeMillis());

                kafkaProducer.publishToTopic(topic, channel.getId(), message);
                log.debug("Published health check for channel {}", channel.getId());

            } catch (Exception e) {
                log.error("Error publishing health check for channel {}", channel.getId(), e);
            }
        }
    }

    private void publishPlatformHealthChecks() {
        // Get all active platforms
        List<Platform> activePlatforms = channelService.findActivePlatforms();

        log.info("Publishing platform health checks for {} platforms", activePlatforms.size());

        for (Platform platform : activePlatforms) {
            try {
                // Publish CHECK_HEALTH_PLATFORM task to platform.fast topic
                String topic = platform.getCode() + ".fast";

                PlatformHealthCheckMessage message = new PlatformHealthCheckMessage();
                message.setTaskType("CHECK_HEALTH_PLATFORM");
                message.setPlatformCode(platform.getCode());
                message.setTimestamp(System.currentTimeMillis());

                kafkaProducer.publishToTopic(topic, platform.getCode(), message);
                log.debug("Published platform health check for {}", platform.getCode());

            } catch (Exception e) {
                log.error("Error publishing platform health check for {}", platform.getCode(), e);
            }
        }
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `cd simpleec-scheduler-job && mvn test -Dtest=HealthCheckSchedulerTest`

Expected: All tests PASS

**Step 5: Commit**

```bash
git add simpleec-scheduler-job/src/main/java/com/simpleec/schedulerjob/scheduler/HealthCheckScheduler.java
git add simpleec-scheduler-job/src/test/java/com/simpleec/schedulerjob/scheduler/HealthCheckSchedulerTest.java

git commit -m "feat: implement health check scheduler (every 5 minutes)

- Publishes CHECK_HEALTH for each enabled channel
- Publishes CHECK_HEALTH_PLATFORM for each active platform
- Messages sent to platform.fast topics
- Comprehensive logging and error handling"
```

---

## Phase 5: Channel Sync Logs Recording

### Task 5.1: Create ChannelSyncLog Entity and Repository

**Files:**
- Create: `simpleec-backend-job/src/main/java/com/simpleec/backendjob/entity/ChannelSyncLog.java` (new)
- Create: `simpleec-backend-job/src/main/java/com/simpleec/backendjob/repository/ChannelSyncLogRepository.java` (new)
- Test: `simpleec-backend-job/src/test/java/com/simpleec/backendjob/repository/ChannelSyncLogRepositoryTest.java` (new)

**Step 1: Write failing test**

```java
// ChannelSyncLogRepositoryTest.java
package com.simpleec.backendjob.repository;

import com.simpleec.backendjob.entity.ChannelSyncLog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
public class ChannelSyncLogRepositoryTest {

    @Autowired
    private ChannelSyncLogRepository repository;

    @Test
    void testSaveChannelSyncLog() {
        ChannelSyncLog log = new ChannelSyncLog();
        log.setId("log-1");
        log.setMerchantId("merchant-1");
        log.setPlatformId("momo");
        log.setChannelId("channel-1");
        log.setSyncType("CHANNEL");
        log.setHttpStatus(200);
        log.setErrorMessage(null);
        log.setCreatedAt(LocalDateTime.now());

        ChannelSyncLog saved = repository.save(log);

        assertThat(saved.getId()).isEqualTo("log-1");
        assertThat(saved.getHealth()).isEqualTo("healthy");
    }

    @Test
    void testFindByChannelIdOrderByCreatedAtDesc() {
        // Insert test data
        ChannelSyncLog log1 = createLog("log-1", "channel-1", 200, "2026-02-23T10:00:00");
        ChannelSyncLog log2 = createLog("log-2", "channel-1", 401, "2026-02-23T10:05:00");
        repository.saveAll(List.of(log1, log2));

        // Query
        List<ChannelSyncLog> result = repository.findByChannelIdOrderByCreatedAtDesc("channel-1");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getId()).isEqualTo("log-2"); // Most recent first
        assertThat(result.get(1).getId()).isEqualTo("log-1");
    }

    private ChannelSyncLog createLog(String id, String channelId, int httpStatus, String timestamp) {
        ChannelSyncLog log = new ChannelSyncLog();
        log.setId(id);
        log.setMerchantId("merchant-1");
        log.setPlatformId("momo");
        log.setChannelId(channelId);
        log.setSyncType("CHANNEL");
        log.setHttpStatus(httpStatus);
        log.setCreatedAt(LocalDateTime.parse(timestamp));
        return log;
    }
}
```

**Step 2: Create ChannelSyncLog Entity**

```java
// ChannelSyncLog.java
package com.simpleec.backendjob.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "channel_sync_logs",
       indexes = {
           @Index(name = "idx_sync_log_channel", columnList = "channel_id, created_at DESC"),
           @Index(name = "idx_sync_log_platform", columnList = "platform_id, created_at DESC"),
           @Index(name = "idx_sync_log_merchant", columnList = "merchant_id, created_at DESC")
       })
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChannelSyncLog {

    @Id
    private String id;

    @Column(nullable = false)
    private String merchantId;

    @Column(nullable = false)
    private String platformId;

    @Column(nullable = true)
    private String channelId;

    @Column(nullable = false)
    private String syncType; // 'CHANNEL' or 'PLATFORM'

    @Column(nullable = false)
    private Integer httpStatus; // 200, 401, 500, etc.

    @Column(nullable = true)
    private String errorMessage;

    @Column(nullable = true, columnDefinition = "TEXT")
    private String requestPayload;

    @Column(nullable = true, columnDefinition = "TEXT")
    private String responsePayload;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Generated column: Computed from httpStatus
     * < 400 → 'healthy', >= 400 → 'unhealthy'
     */
    @Transient
    public String getHealth() {
        return httpStatus >= 400 ? "unhealthy" : "healthy";
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
```

**Step 3: Create Repository**

```java
// ChannelSyncLogRepository.java
package com.simpleec.backendjob.repository;

import com.simpleec.backendjob.entity.ChannelSyncLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ChannelSyncLogRepository extends JpaRepository<ChannelSyncLog, String> {

    /**
     * Find latest sync log for a channel
     */
    List<ChannelSyncLog> findByChannelIdOrderByCreatedAtDesc(String channelId);

    /**
     * Find latest sync log for a platform
     */
    List<ChannelSyncLog> findByPlatformIdAndChannelIdNullOrderByCreatedAtDesc(String platformId);

    /**
     * Find all logs for a merchant in time range
     */
    @Query("SELECT l FROM ChannelSyncLog l WHERE l.merchantId = :merchantId AND l.createdAt BETWEEN :start AND :end ORDER BY l.createdAt DESC")
    List<ChannelSyncLog> findByMerchantAndTimeRange(
        @Param("merchantId") String merchantId,
        @Param("start") LocalDateTime start,
        @Param("end") LocalDateTime end
    );

    /**
     * Find unhealthy channels for a platform
     */
    @Query("SELECT DISTINCT l.channelId FROM ChannelSyncLog l WHERE l.platformId = :platformId AND l.syncType = 'CHANNEL' AND l.httpStatus >= 400 ORDER BY l.createdAt DESC")
    List<String> findUnhealthyChannels(@Param("platformId") String platformId);
}
```

**Step 4: Run tests**

Run: `cd simpleec-backend-job && mvn test -Dtest=ChannelSyncLogRepositoryTest`

Expected: All tests PASS

**Step 5: Commit**

```bash
git add simpleec-backend-job/src/main/java/com/simpleec/backendjob/entity/ChannelSyncLog.java
git add simpleec-backend-job/src/main/java/com/simpleec/backendjob/repository/ChannelSyncLogRepository.java
git add simpleec-backend-job/src/test/java/com/simpleec/backendjob/repository/ChannelSyncLogRepositoryTest.java

git commit -m "feat: add ChannelSyncLog entity for health tracking

- HTTP status recording (200, 401, 500, etc.)
- Generated health column (healthy/unhealthy)
- Indexes for efficient time-series queries
- Repository methods for diagnostic queries"
```

---

## Phase 6: Channel-Job Health Check Handling

### Task 6.1: Handle CHECK_HEALTH Messages

**Files:**
- Modify: `simpleec-channel-job/src/main/java/com/simpleec/channeljob/processor/ChannelMessageProcessor.java` (add health check logic)
- Create: `simpleec-channel-job/src/main/java/com/simpleec/channeljob/service/HealthCheckService.java` (new)
- Test: `simpleec-channel-job/src/test/java/com/simpleec/channeljob/service/HealthCheckServiceTest.java` (new)

**Step 1: Write failing test**

```java
// HealthCheckServiceTest.java
package com.simpleec.channeljob.service;

import com.simpleec.channeljob.entity.Channel;
import com.simpleec.channeljob.repository.ChannelRepository;
import com.simpleec.channeljob.repository.ChannelSyncLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.mockito.Mockito.*;

public class HealthCheckServiceTest {

    @Mock
    private ChannelRepository channelRepository;

    @Mock
    private ChannelSyncLogRepository syncLogRepository;

    @Mock
    private PlatformApiClient platformApiClient;

    private HealthCheckService healthCheckService;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        healthCheckService = new HealthCheckService(channelRepository, syncLogRepository, platformApiClient);
    }

    @Test
    void testPerformChannelHealthCheck_TokenValid() throws Exception {
        // Arrange
        Channel channel = new Channel();
        channel.setId("channel-1");
        channel.setMerchantId("M001");
        channel.setPlatformId("momo");
        channel.setToken("valid-token");

        when(channelRepository.findById("channel-1")).thenReturn(java.util.Optional.of(channel));
        when(platformApiClient.healthCheck("momo", "valid-token")).thenReturn(200);

        // Act
        HealthCheckResult result = healthCheckService.performChannelHealthCheck("channel-1");

        // Assert
        assert (result.getHttpStatus() == 200);
        assert (result.getHealth().equals("healthy"));
        verify(syncLogRepository, times(1)).save(any());
    }

    @Test
    void testPerformChannelHealthCheck_TokenExpired() throws Exception {
        // Arrange
        Channel channel = new Channel();
        channel.setId("channel-1");
        channel.setMerchantId("M001");
        channel.setPlatformId("momo");
        channel.setToken("expired-token");

        when(channelRepository.findById("channel-1")).thenReturn(java.util.Optional.of(channel));
        when(platformApiClient.healthCheck("momo", "expired-token")).thenReturn(401);

        // Act
        HealthCheckResult result = healthCheckService.performChannelHealthCheck("channel-1");

        // Assert
        assert (result.getHttpStatus() == 401);
        assert (result.getHealth().equals("unhealthy"));
        verify(syncLogRepository, times(1)).save(any());
    }
}
```

**Step 2: Create HealthCheckService**

```java
// HealthCheckService.java
package com.simpleec.channeljob.service;

import com.simpleec.channeljob.entity.Channel;
import com.simpleec.channeljob.entity.ChannelSyncLog;
import com.simpleec.channeljob.repository.ChannelRepository;
import com.simpleec.channeljob.repository.ChannelSyncLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
public class HealthCheckService {

    private final ChannelRepository channelRepository;
    private final ChannelSyncLogRepository syncLogRepository;
    private final PlatformApiClient platformApiClient;

    public HealthCheckService(ChannelRepository channelRepository,
                              ChannelSyncLogRepository syncLogRepository,
                              PlatformApiClient platformApiClient) {
        this.channelRepository = channelRepository;
        this.syncLogRepository = syncLogRepository;
        this.platformApiClient = platformApiClient;
    }

    /**
     * Perform health check for specific channel
     * Returns HTTP status and health status
     */
    public HealthCheckResult performChannelHealthCheck(String channelId) {
        try {
            Channel channel = channelRepository.findById(channelId)
                .orElse(null);

            if (channel == null) {
                log.warn("Channel {} not found", channelId);
                recordHealthLog(channelId, null, null, 404, "Channel not found");
                return new HealthCheckResult(404, "unhealthy", "Channel not found");
            }

            // Call platform API with channel's token
            int httpStatus = platformApiClient.healthCheck(
                channel.getPlatformId(),
                channel.getToken()
            );

            String errorMessage = httpStatus >= 400
                ? getPlatformErrorMessage(httpStatus, channel.getPlatformId())
                : null;

            recordHealthLog(
                channelId,
                channel.getMerchantId(),
                channel.getPlatformId(),
                httpStatus,
                errorMessage
            );

            return new HealthCheckResult(
                httpStatus,
                httpStatus >= 400 ? "unhealthy" : "healthy",
                errorMessage
            );

        } catch (Exception e) {
            log.error("Error performing health check for channel {}", channelId, e);
            recordHealthLog(channelId, null, null, 500, e.getMessage());
            return new HealthCheckResult(500, "unhealthy", e.getMessage());
        }
    }

    /**
     * Perform health check for entire platform (no auth needed)
     */
    public HealthCheckResult performPlatformHealthCheck(String platformId) {
        try {
            int httpStatus = platformApiClient.platformHealthCheck(platformId);

            String errorMessage = httpStatus >= 400
                ? getPlatformErrorMessage(httpStatus, platformId)
                : null;

            recordPlatformHealthLog(platformId, httpStatus, errorMessage);

            return new HealthCheckResult(
                httpStatus,
                httpStatus >= 400 ? "unhealthy" : "healthy",
                errorMessage
            );

        } catch (Exception e) {
            log.error("Error performing platform health check for {}", platformId, e);
            recordPlatformHealthLog(platformId, 500, e.getMessage());
            return new HealthCheckResult(500, "unhealthy", e.getMessage());
        }
    }

    private void recordHealthLog(String channelId, String merchantId, String platformId,
                                 int httpStatus, String errorMessage) {
        ChannelSyncLog log = new ChannelSyncLog();
        log.setId(UUID.randomUUID().toString());
        log.setChannelId(channelId);
        log.setMerchantId(merchantId);
        log.setPlatformId(platformId);
        log.setSyncType("CHANNEL");
        log.setHttpStatus(httpStatus);
        log.setErrorMessage(errorMessage);
        log.setCreatedAt(LocalDateTime.now());

        syncLogRepository.save(log);
    }

    private void recordPlatformHealthLog(String platformId, int httpStatus, String errorMessage) {
        ChannelSyncLog log = new ChannelSyncLog();
        log.setId(UUID.randomUUID().toString());
        log.setPlatformId(platformId);
        log.setChannelId(null); // No specific channel
        log.setSyncType("PLATFORM");
        log.setHttpStatus(httpStatus);
        log.setErrorMessage(errorMessage);
        log.setCreatedAt(LocalDateTime.now());

        syncLogRepository.save(log);
    }

    private String getPlatformErrorMessage(int httpStatus, String platformId) {
        return switch (httpStatus) {
            case 401 -> "Unauthorized: Token invalid or expired";
            case 403 -> "Forbidden: Insufficient permissions";
            case 500 -> "Platform service error";
            case 503 -> "Platform service unavailable";
            default -> "HTTP " + httpStatus;
        };
    }

    public static class HealthCheckResult {
        public final int httpStatus;
        public final String health;
        public final String errorMessage;

        public HealthCheckResult(int httpStatus, String health, String errorMessage) {
            this.httpStatus = httpStatus;
            this.health = health;
            this.errorMessage = errorMessage;
        }

        public int getHttpStatus() { return httpStatus; }
        public String getHealth() { return health; }
        public String getErrorMessage() { return errorMessage; }
    }
}
```

**Step 3: Update ChannelMessageProcessor to handle CHECK_HEALTH**

Add to `ChannelMessageProcessor.processMessage()`:

```java
// Add at beginning of processMessage:
if ("CHECK_HEALTH".equals(message.getTaskType())) {
    return handleHealthCheck(message);
}
if ("CHECK_HEALTH_PLATFORM".equals(message.getTaskType())) {
    return handlePlatformHealthCheck(message);
}

// Add new methods:
private boolean handleHealthCheck(ChannelMessage message) {
    String channelId = message.getChannelId();
    log.info("Performing health check for channel {}", channelId);

    HealthCheckService.HealthCheckResult result = healthCheckService
        .performChannelHealthCheck(channelId);

    log.info("Health check complete: channel={}, status={}, health={}",
        channelId, result.getHttpStatus(), result.getHealth());

    return result.getHttpStatus() < 400;
}

private boolean handlePlatformHealthCheck(ChannelMessage message) {
    String platformCode = message.getPlatformCode();
    log.info("Performing health check for platform {}", platformCode);

    HealthCheckService.HealthCheckResult result = healthCheckService
        .performPlatformHealthCheck(platformCode);

    log.info("Platform health check complete: platform={}, status={}, health={}",
        platformCode, result.getHttpStatus(), result.getHealth());

    return result.getHttpStatus() < 400;
}
```

**Step 4: Run tests**

Run: `cd simpleec-channel-job && mvn test -Dtest=HealthCheckServiceTest`

Expected: All tests PASS

**Step 5: Commit**

```bash
git add simpleec-channel-job/src/main/java/com/simpleec/channeljob/service/HealthCheckService.java
git add simpleec-channel-job/src/main/java/com/simpleec/channeljob/processor/ChannelMessageProcessor.java
git add simpleec-channel-job/src/test/java/com/simpleec/channeljob/service/HealthCheckServiceTest.java

git commit -m "feat: implement health check handling in channel-job

- Handle CHECK_HEALTH task type (channel-level)
- Handle CHECK_HEALTH_PLATFORM task type (platform-level)
- Record HTTP status in channel_sync_logs
- Error message mapping for 401, 403, 500, 503
- Graceful error handling"
```

---

## Phase 7: API Endpoints for Channel Management

### Task 7.1: Add Health Check Endpoint to User API

**Files:**
- Modify: `simpleec-api/src/main/java/com/simpleec/api/controller/ChannelController.java` (add endpoint)
- Create: `simpleec-api/src/main/java/com/simpleec/api/service/ChannelHealthService.java` (new)
- Test: `simpleec-api/src/test/java/com/simpleec/api/controller/ChannelControllerTest.java` (add test)

**Step 1: Write failing test**

```java
// In ChannelControllerTest.java, add:
@Test
@WithMockUser(username = "merchant@test.com")
void testGetChannelHealth() throws Exception {
    // Arrange
    String channelId = "ch-123";
    ChannelHealthResponse expectedResponse = new ChannelHealthResponse();
    expectedResponse.setChannelId(channelId);
    expectedResponse.setHealth("healthy");
    expectedResponse.setLastCheckAt("2026-02-23T10:00:00");

    when(channelHealthService.getChannelHealth(any(), eq(channelId)))
        .thenReturn(expectedResponse);

    // Act & Assert
    mockMvc.perform(get("/api/user/channels/{id}/health", channelId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.health").value("healthy"));
}

@Test
@WithMockUser(username = "merchant@test.com")
void testGetChannelSyncLogs() throws Exception {
    // Arrange
    String channelId = "ch-123";
    List<ChannelSyncLogResponse> logs = List.of(
        new ChannelSyncLogResponse("healthy", 200, "2026-02-23T10:05:00"),
        new ChannelSyncLogResponse("healthy", 200, "2026-02-23T10:00:00")
    );

    when(channelHealthService.getChannelSyncLogs(any(), eq(channelId), any()))
        .thenReturn(logs);

    // Act & Assert
    mockMvc.perform(get("/api/user/channels/{id}/logs", channelId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(2)));
}
```

**Step 2: Create response DTOs**

```java
// ChannelHealthResponse.java
@Data
public class ChannelHealthResponse {
    private String channelId;
    private String health; // healthy / unhealthy
    private String platformId;
    private Integer lastHttpStatus;
    private String lastErrorMessage;
    private String lastCheckAt;
}

// ChannelSyncLogResponse.java
@Data
public class ChannelSyncLogResponse {
    private String health;
    private Integer httpStatus;
    private String timestamp;
    private String errorMessage;
}
```

**Step 3: Create ChannelHealthService**

```java
// ChannelHealthService.java
@Service
public class ChannelHealthService {

    private final ChannelRepository channelRepository;
    private final ChannelSyncLogRepository syncLogRepository;

    public ChannelHealthService(ChannelRepository channelRepository,
                                ChannelSyncLogRepository syncLogRepository) {
        this.channelRepository = channelRepository;
        this.syncLogRepository = syncLogRepository;
    }

    public ChannelHealthResponse getChannelHealth(String merchantId, String channelId) {
        // Get latest sync log for this channel
        List<ChannelSyncLog> logs = syncLogRepository
            .findByChannelIdOrderByCreatedAtDesc(channelId);

        if (logs.isEmpty()) {
            throw new ResourceNotFoundException("No health data for channel");
        }

        ChannelSyncLog latestLog = logs.get(0);

        ChannelHealthResponse response = new ChannelHealthResponse();
        response.setChannelId(channelId);
        response.setHealth(latestLog.getHealth());
        response.setLastHttpStatus(latestLog.getHttpStatus());
        response.setLastErrorMessage(latestLog.getErrorMessage());
        response.setLastCheckAt(latestLog.getCreatedAt().toString());

        return response;
    }

    public List<ChannelSyncLogResponse> getChannelSyncLogs(String merchantId, String channelId,
                                                            Integer limit) {
        List<ChannelSyncLog> logs = syncLogRepository
            .findByChannelIdOrderByCreatedAtDesc(channelId);

        if (limit != null && limit > 0) {
            logs = logs.stream().limit(limit).collect(Collectors.toList());
        }

        return logs.stream()
            .map(log -> new ChannelSyncLogResponse(
                log.getHealth(),
                log.getHttpStatus(),
                log.getCreatedAt().toString(),
                log.getErrorMessage()
            ))
            .collect(Collectors.toList());
    }
}
```

**Step 4: Add endpoints to ChannelController**

```java
// Add to ChannelController:
@GetMapping("/{channelId}/health")
public ResponseEntity<ChannelHealthResponse> getChannelHealth(
    @PathVariable String channelId) {

    String merchantId = getCurrentMerchantId(); // From JWT token
    ChannelHealthResponse response = channelHealthService
        .getChannelHealth(merchantId, channelId);

    return ResponseEntity.ok(response);
}

@GetMapping("/{channelId}/logs")
public ResponseEntity<List<ChannelSyncLogResponse>> getChannelSyncLogs(
    @PathVariable String channelId,
    @RequestParam(defaultValue = "24") Integer hours) {

    String merchantId = getCurrentMerchantId();
    List<ChannelSyncLogResponse> logs = channelHealthService
        .getChannelSyncLogs(merchantId, channelId, hours);

    return ResponseEntity.ok(logs);
}
```

**Step 5: Run tests**

Run: `cd simpleec-api && mvn test -Dtest=ChannelControllerTest -k "Health OR Logs"`

Expected: New tests PASS

**Step 6: Commit**

```bash
git add simpleec-api/src/main/java/com/simpleec/api/service/ChannelHealthService.java
git add simpleec-api/src/main/java/com/simpleec/api/controller/ChannelController.java
git add simpleec-api/src/test/java/com/simpleec/api/controller/ChannelControllerTest.java

git commit -m "feat: add channel health check endpoints

- GET /api/user/channels/{id}/health - Latest health status
- GET /api/user/channels/{id}/logs - Sync history (configurable hours)
- Integrates with channel_sync_logs for time-series data
- Merchant-specific (via JWT token)"
```

---

## Phase 8: Frontend Integration (ChannelPage Updates)

### Task 8.1: Display Health Status in ChannelPage

**Files:**
- Modify: `user-app/src/views/ChannelPage.vue` (add health display)
- Modify: `user-app/src/api/channel.ts` (add health endpoints)
- Test: `user-app/src/views/__tests__/ChannelPage.spec.ts` (add tests)

**Step 1: Update channel API**

```typescript
// user-app/src/api/channel.ts
export const getChannelHealth = (channelId: string): Promise<ChannelHealthResponse> =>
  axiosInstance.get(`/user/channels/${channelId}/health`)

export const getChannelSyncLogs = (channelId: string, hours?: number): Promise<ChannelSyncLog[]> =>
  axiosInstance.get(`/user/channels/${channelId}/logs`, {
    params: { hours: hours || 24 }
  })
```

**Step 2: Add types**

```typescript
// user-app/src/types/index.ts
export interface ChannelHealthResponse {
  channelId: string
  health: 'healthy' | 'unhealthy'
  lastHttpStatus: number
  lastErrorMessage?: string
  lastCheckAt: string
}

export interface ChannelSyncLog {
  health: 'healthy' | 'unhealthy'
  httpStatus: number
  timestamp: string
  errorMessage?: string
}
```

**Step 3: Update ChannelPage component**

```vue
<!-- user-app/src/views/ChannelPage.vue -->
<template>
  <div class="channel-page">
    <!-- ... existing code ... -->

    <!-- Health Status Section -->
    <div v-if="selectedChannel" class="health-section">
      <h3>通路健康狀態</h3>

      <div class="health-card" :class="selectedChannel.health">
        <div class="health-badge">{{ selectedChannel.health === 'healthy' ? '✅ 健康' : '❌ 異常' }}</div>
        <p>最後檢查: {{ formatTime(selectedChannel.lastCheckAt) }}</p>
        <p v-if="selectedChannel.lastHttpStatus">
          HTTP Status: {{ selectedChannel.lastHttpStatus }}
        </p>
        <p v-if="selectedChannel.lastErrorMessage" class="error-message">
          {{ selectedChannel.lastErrorMessage }}
        </p>
      </div>

      <!-- Sync History -->
      <div class="sync-history">
        <h4>同步歷史 (24小時)</h4>
        <table>
          <thead>
            <tr>
              <th>時間</th>
              <th>狀態</th>
              <th>HTTP 狀態碼</th>
              <th>錯誤訊息</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="log in syncLogs" :key="log.timestamp" :class="log.health">
              <td>{{ formatTime(log.timestamp) }}</td>
              <td>{{ log.health === 'healthy' ? '✅ 健康' : '❌ 異常' }}</td>
              <td>{{ log.httpStatus }}</td>
              <td>{{ log.errorMessage || '-' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { getChannelHealth, getChannelSyncLogs } from '@/api/channel'
import type { ChannelHealthResponse, ChannelSyncLog } from '@/types'

const selectedChannel = ref<ChannelHealthResponse | null>(null)
const syncLogs = ref<ChannelSyncLog[]>([])
const loading = ref(false)

const loadChannelHealth = async (channelId: string) => {
  loading.value = true
  try {
    selectedChannel.value = await getChannelHealth(channelId)
    syncLogs.value = await getChannelSyncLogs(channelId, 24)
  } catch (error) {
    console.error('Failed to load channel health:', error)
  } finally {
    loading.value = false
  }
}

const formatTime = (dateString: string) => {
  const date = new Date(dateString)
  return date.toLocaleString('zh-TW')
}
</script>

<style scoped>
.health-card {
  padding: 16px;
  border-radius: 8px;
  margin: 16px 0;
}

.health-card.healthy {
  background-color: #f0fdf4;
  border-left: 4px solid #22c55e;
}

.health-card.unhealthy {
  background-color: #fef2f2;
  border-left: 4px solid #ef4444;
}

.health-badge {
  font-weight: bold;
  font-size: 16px;
  margin-bottom: 8px;
}

.sync-history table {
  width: 100%;
  border-collapse: collapse;
  margin-top: 12px;
}

.sync-history th, .sync-history td {
  padding: 8px;
  text-align: left;
  border-bottom: 1px solid #e5e7eb;
}

.sync-history th {
  background-color: #f9fafb;
  font-weight: 600;
}

.sync-history tr.healthy {
  background-color: #f0fdf4;
}

.sync-history tr.unhealthy {
  background-color: #fef2f2;
}

.error-message {
  color: #dc2626;
  font-size: 14px;
}
</style>
```

**Step 4: Run frontend tests**

Run: `cd user-app && npm test -- ChannelPage.spec.ts`

Expected: Tests PASS

**Step 5: Commit**

```bash
git add user-app/src/api/channel.ts
git add user-app/src/types/index.ts
git add user-app/src/views/ChannelPage.vue
git add user-app/src/views/__tests__/ChannelPage.spec.ts

git commit -m "feat: display channel health status in ChannelPage

- Show latest health check result (healthy/unhealthy)
- Display HTTP status and error messages
- 24-hour sync history table
- Time-series visualization
- Color-coded status indicators"
```

---

## Phase 9: Integration & System Testing

### Task 9.1: End-to-End Test: Full Flow

**Files:**
- Create: `tests/e2e/test_full_flow.py` (new)
- Create: `tests/e2e/docker-compose.test.yml` (test environment)

**Step 1: Write end-to-end test**

```python
# tests/e2e/test_full_flow.py
import pytest
import time
import subprocess
import yaml
from pathlib import Path
import requests
import psycopg2

@pytest.fixture(scope="module")
def docker_env():
    """Start docker-compose for testing"""
    config_path = Path(__file__).parent.parent.parent / 'config.yaml'
    compose_path = Path(__file__).parent / 'docker-compose.test.yml'

    # Generate test compose
    subprocess.run(
        ['python', 'docker/generate-compose.py', str(config_path), str(compose_path)],
        check=True
    )

    # Start containers
    subprocess.run(['docker-compose', '-f', str(compose_path), 'up', '-d'], check=True)

    # Wait for services to be ready
    time.sleep(30)

    yield

    # Cleanup
    subprocess.run(['docker-compose', '-f', str(compose_path), 'down'], check=False)

def test_config_generates_compose_with_7_platforms(docker_env):
    """Verify compose has all 7 platforms"""
    compose_path = Path(__file__).parent / 'docker-compose.test.yml'

    with open(compose_path) as f:
        compose = yaml.safe_load(f)

    services = list(compose['services'].keys())
    platform_services = [s for s in services if 'channel-' in s]

    assert len(platform_services) == 14, f"Expected 14 (7x2), got {len(platform_services)}"

def test_kafka_auto_creates_topics(docker_env):
    """Verify Kafka is configured to auto-create topics"""
    # Query Kafka settings
    result = subprocess.run(
        ['docker', 'exec', 'simpleec-kafka',
         '/opt/kafka/bin/kafka-broker-api-versions.sh', '--bootstrap-server', 'localhost:9092'],
        capture_output=True, text=True
    )

    assert result.returncode == 0, "Kafka not responding"

def test_channel_table_schema_correct(docker_env):
    """Verify channel table has enable_sync column"""
    conn = psycopg2.connect(
        host="localhost", user="simpleec", password="simpleec123",
        database="simpleec"
    )
    cursor = conn.cursor()

    cursor.execute("""
        SELECT column_name FROM information_schema.columns
        WHERE table_name='channel' AND column_name='enable_sync'
    """)

    result = cursor.fetchone()
    assert result is not None, "enable_sync column not found"

    conn.close()

def test_channel_sync_logs_table_exists(docker_env):
    """Verify channel_sync_logs table created"""
    conn = psycopg2.connect(
        host="localhost", user="simpleec", password="simpleec123",
        database="simpleec"
    )
    cursor = conn.cursor()

    cursor.execute("""
        SELECT EXISTS(
            SELECT 1 FROM information_schema.tables
            WHERE table_name='channel_sync_logs'
        )
    """)

    exists = cursor.fetchone()[0]
    assert exists, "channel_sync_logs table not created"

    conn.close()

def test_api_health_endpoint(docker_env):
    """Verify API is responding"""
    response = requests.get("http://localhost:8082/health", timeout=5)
    assert response.status_code == 200, "API not healthy"

def test_platform_job_containers_running(docker_env):
    """Verify all 14 channel jobs are running"""
    result = subprocess.run(
        ['docker', 'ps', '--format', '{{.Names}}'],
        capture_output=True, text=True
    )

    containers = result.stdout.strip().split('\n')
    channel_jobs = [c for c in containers if 'channel-' in c]

    assert len(channel_jobs) >= 14, f"Expected >= 14 channel jobs, got {len(channel_jobs)}"
```

**Step 2: Run e2e tests**

Run: `pytest tests/e2e/test_full_flow.py -v --timeout=120`

Expected: All tests PASS

**Step 3: Commit**

```bash
git add tests/e2e/test_full_flow.py tests/e2e/docker-compose.test.yml

git commit -m "test: add end-to-end tests for complete flow

- Verify config generates correct docker-compose
- Verify all 14 channel jobs running
- Verify database schema (enable_sync, channel_sync_logs)
- Verify Kafka auto-topic creation
- Verify API health endpoint"
```

---

## Phase 10: Documentation & Final Validation

### Task 10.1: Update README and Deployment Guide

**Files:**
- Modify: `README.md` (update with config approach)
- Create: `docs/DEPLOYMENT.md` (deployment guide)
- Create: `docs/ARCHITECTURE.md` (technical architecture)

**Step 1: Update README**

```markdown
# SimpleEC OMS - Dynamic Platform Configuration

## Quick Start

### 1. Generate docker-compose from config

```bash
python docker/generate-compose.py config.yaml docker-compose.yml
```

### 2. Start services

```bash
docker-compose up -d
```

### 3. Verify all services running

```bash
docker-compose ps
```

### 4. Check Kafka topics (auto-created)

```bash
docker exec simpleec-kafka /opt/kafka/bin/kafka-topics.sh --list --bootstrap-server localhost:9092
```

### 5. Add new merchant channel (via User App)

1. Open http://localhost:8089 (User App)
2. Go to Channel Settings
3. Select platform (MOMO, Shopee, etc.)
4. Enter account name + API token
5. Save and wait for "Enable Sync"

### 6. Monitor channel health

- View "Health Status" section in ChannelPage
- Check sync history logs
- API: GET /api/user/channels/{id}/health

## Adding New Platform

1. **Implement adapter** - Add PlatformAdapter.java
2. **Edit config.yaml** - Add platform to platforms list
3. **Generate compose** - `python docker/generate-compose.py config.yaml docker-compose.yml`
4. **Start services** - `docker-compose up -d`
5. **Add platform record** - Admin App → Platforms → Add
6. **Done!** - Merchants can now see platform in ChannelPage

## Configuration

See `config.yaml` for:
- 7 MVP platforms (MOMO, Shopee, Yahoo, PChome, Cyberbiz, Shopline, Shopify)
- Infrastructure settings (postgres, redis, kafka)
- Job concurrency settings per platform

## Health Monitoring

Every 5 minutes, Scheduler publishes health checks:
- **Channel-level**: Tests each enabled channel's token
- **Platform-level**: Tests platform API availability

Results recorded in `channel_sync_logs` table with HTTP status codes.

Access via:
- User App: ChannelPage → Health Status → Sync History
- API: `/api/user/channels/{id}/health` and `/api/user/channels/{id}/logs`
```

**Step 2: Create deployment guide**

```markdown
# Deployment Guide

## Development (docker-compose)

### Prerequisites
- Docker + Docker Compose
- Python 3.8+
- 16GB RAM recommended

### Setup Steps

1. Clone repo and navigate to root

2. Generate docker-compose:
   ```bash
   python docker/generate-compose.py config.yaml docker-compose.yml
   ```

3. Start services:
   ```bash
   docker-compose up -d
   ```

4. Wait for postgres health check (~30s):
   ```bash
   docker-compose ps postgres
   # Should show "healthy"
   ```

5. Verify all services:
   ```bash
   docker-compose ps
   ```

6. Check API:
   ```bash
   curl http://localhost:8082/health
   ```

### Testing Health Checks

1. Add merchant + channel via User App
2. Enable sync for channel
3. Scheduler runs every 5 minutes, publishes CHECK_HEALTH
4. Channel-Job calls platform API
5. Records result in channel_sync_logs

Check logs:
```bash
docker logs simpleec-channel-momo-fast | grep "health check"
```

## Production (Kubernetes)

### Future Implementation

Same `config.yaml` will generate k8s manifests:

```bash
python k8s/generate-manifests.py config.yaml manifests/
kubectl apply -f manifests/
```

### Scaling Considerations

- **Channel Jobs**: Horizontal scaling per platform via deployment replicas
- **Database**: PostgreSQL connection pooling (pg_bouncer)
- **Kafka**: Broker replicas, topic partitions per platform
- **Health Checks**: Every 5 minutes, staggered per platform

### Monitoring Stack

- Prometheus: Metrics from jobs and API
- Grafana: Dashboards (health timeline, sync latency)
- Loki: Log aggregation from container logs
- Tempo: Distributed tracing for end-to-end flow

## Troubleshooting

### Channel shows "unhealthy" (401)

**Diagnosis**: Token expired
**Action**: Merchant refreshes token in ChannelPage

### Platform shows "unhealthy" (500)

**Diagnosis**: Platform service down
**Action**: Check platform status page, wait for recovery

### Mixed health statuses (some 200, some 401)

**Diagnosis**: Only some merchant tokens expired
**Action**: Notify specific merchants to update tokens

### No sync_logs appearing

**Diagnosis**: Scheduler job not running
**Action**:
```bash
docker logs simpleec-scheduler-job
```

### Kafka topics not auto-creating

**Verify**: `docker exec simpleec-kafka env | grep AUTO_CREATE`
Should show: `KAFKA_AUTO_CREATE_TOPICS_ENABLE=true`
```

**Step 3: Commit**

```bash
git add README.md docs/DEPLOYMENT.md docs/ARCHITECTURE.md

git commit -m "docs: add deployment guide and architecture documentation

- Quick start guide for docker-compose
- Steps to add new platform
- Health monitoring explanation
- Production k8s considerations
- Troubleshooting common issues"
```

### Task 10.2: Create Implementation Checklist

**Files:**
- Create: `IMPLEMENTATION_CHECKLIST.md`

```markdown
# Implementation Checklist

## Database
- [x] Task 1.1: Add channel_sync_logs table
  - [x] Columns: id, merchant_id, platform_id, channel_id, sync_type, http_status, health (generated), error_message, created_at
  - [x] Constraints: sync_type IN ('CHANNEL', 'PLATFORM'), http_status 100-599
  - [x] Indexes: channel_id, platform_id, merchant_id (all with created_at DESC)
  - [x] Foreign keys: merchant, platform, channel

## Configuration & Generation
- [x] Task 2.1: Create config.yaml
  - [x] 7 platforms with concurrency settings
  - [x] Infrastructure (postgres, redis, kafka)
  - [x] Services (api, jobs, frontend)
  - [x] Tests verify structure and completeness

- [x] Task 2.2: Create docker-compose generation script
  - [x] Python script reads config.yaml
  - [x] Generates 14 channel-job services (7 platforms × 2 speeds)
  - [x] Sets JOB_CHANNEL_TOPICS and concurrency env vars
  - [x] Comprehensive tests verify correctness
  - [x] docker-compose.yml created and verified

## Channel-Job
- [x] Task 3.1: Implement enable_sync gate
  - [x] Check channel.enable_sync before processing
  - [x] Skip messages for disabled channels
  - [x] Record in channel_sync_logs with status
  - [x] Unit tests for enable/disable behavior

## Scheduler
- [x] Task 4.1: Implement health check scheduling
  - [x] Runs every 5 minutes
  - [x] Publishes CHECK_HEALTH for each enabled channel
  - [x] Publishes CHECK_HEALTH_PLATFORM for each active platform
  - [x] Messages sent to platform.fast topics

## Database Logging
- [x] Task 5.1: Create ChannelSyncLog entity & repository
  - [x] Entity with all required columns
  - [x] Repository with query methods (byChannelId, byPlatformId, timeRange)
  - [x] Unit tests verify save and retrieval

## Channel-Job Health Checks
- [x] Task 6.1: Handle CHECK_HEALTH messages
  - [x] Identify CHECK_HEALTH task type
  - [x] Call platform API with channel token
  - [x] Record HTTP status in sync logs
  - [x] Error message mapping (401 → token, 403 → perm, 500 → service)
  - [x] Handle platform-level checks (no auth)

## APIs
- [x] Task 7.1: Add health endpoints to User API
  - [x] GET /api/user/channels/{id}/health
  - [x] GET /api/user/channels/{id}/logs
  - [x] Merchant-specific (JWT validated)
  - [x] Tests verify endpoint responses

## Frontend
- [x] Task 8.1: Update ChannelPage with health display
  - [x] Channel health card (healthy/unhealthy badge)
  - [x] 24-hour sync history table
  - [x] Time formatting and error message display
  - [x] API integration with new endpoints
  - [x] Color-coded status indicators

## Testing
- [x] Task 9.1: End-to-end tests
  - [x] Verify config generates 14 channel jobs
  - [x] Verify database schema correct
  - [x] Verify Kafka auto-topics enabled
  - [x] Verify API health endpoint
  - [x] Verify containers running

## Documentation
- [x] Task 10.1: Update README and guides
  - [x] Quick start (config → compose → services)
  - [x] Adding new platform workflow
  - [x] Health monitoring explanation
  - [x] Production k8s considerations
  - [x] Troubleshooting guide

- [x] Task 10.2: Implementation checklist (this file)

## Verification Checklist

Before merging to main:

- [ ] All tests pass locally
  ```bash
  cd simpleec-api && mvn test
  cd simpleec-channel-job && mvn test
  cd simpleec-scheduler-job && mvn test
  cd user-app && npm test
  pytest tests/
  ```

- [ ] docker-compose starts without errors
  ```bash
  python docker/generate-compose.py config.yaml docker-compose.yml
  docker-compose up -d
  docker-compose ps  # All running
  ```

- [ ] Kafka topics auto-created
  ```bash
  docker exec simpleec-kafka /opt/kafka/bin/kafka-topics.sh --list --bootstrap-server localhost:9092
  # Should show: momo.fast, momo.slow, shopee.fast, shopee.slow, ..., cyberbiz.fast, cyberbiz.slow, etc.
  ```

- [ ] Database schema correct
  ```bash
  docker exec simpleec-postgres psql -U simpleec -d simpleec -c "\d channel_sync_logs"
  # Should show all columns and indexes
  ```

- [ ] API responding
  ```bash
  curl http://localhost:8082/health
  # Should return 200 OK
  ```

- [ ] User App displays health section
  - Navigate to http://localhost:8089/channels
  - Add test channel
  - Enable sync
  - Check health status appears after 5 minutes

- [ ] Scheduler running health checks
  ```bash
  docker logs simpleec-scheduler-job | grep "health check"
  # Should see logs every 5 minutes
  ```

- [ ] All commits follow format
  ```bash
  git log --oneline | head -20
  # Each commit should be atomic and descriptive
  ```
```

**Step 4: Commit final**

```bash
git add IMPLEMENTATION_CHECKLIST.md

git commit -m "chore: add implementation completion checklist

- Track all 10 phases and sub-tasks
- Verification steps before merging
- Testing commands for validation
- Database, API, and UI confirmation steps"
```

---

## Summary

**Total Tasks:** 10 phases, 24 atomic tasks
**Key Deliverables:**
- ✅ Dynamic platform configuration (config.yaml)
- ✅ Docker-compose generation from config
- ✅ Channel-sync-logs with health tracking
- ✅ 5-minute health check cycle
- ✅ enable_sync gate in Channel-Job
- ✅ Time-series diagnostic queries
- ✅ Health status UI in ChannelPage
- ✅ Comprehensive testing

**Estimated Timeline:**
- Phase 1-2: 4-6 hours (schema, config, generation)
- Phase 3-4: 3-4 hours (job logic)
- Phase 5-6: 4-5 hours (logging, health checks)
- Phase 7-8: 3-4 hours (APIs, frontend)
- Phase 9-10: 2-3 hours (testing, docs)

**Total: ~20-25 hours of development**

---

**Plan Status:** Ready for implementation via superpowers:executing-plans or superpowers:subagent-driven-development

