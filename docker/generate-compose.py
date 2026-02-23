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
            'KAFKA_NUM_PARTITIONS': str(kafka_cfg.get('defaultPartitions', 3)),
            'KAFKA_DEFAULT_REPLICATION_FACTOR': str(kafka_cfg.get('defaultReplicationFactor', 1)),
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

    # Additional Infrastructure (Monitoring, Logging, etc.)
    # Skip postgres, redis, kafka which are already handled above
    infrastructure_config = config.get('infrastructure', {})
    for infra_key, infra_cfg in infrastructure_config.items():
        if infra_key in ['postgres', 'redis', 'kafka']:
            continue  # Already handled

        # Create generic infrastructure service
        service_name = infra_key
        if not service_name.startswith('simpleec-'):
            service_name = f'simpleec-{infra_key}'

        services[service_name] = {
            'image': infra_cfg.get('image'),
            'container_name': infra_cfg.get('container_name', service_name),
            'ports': [f"{infra_cfg['port']}:{infra_cfg.get('internal_port', infra_cfg['port'])}"]
                if 'port' in infra_cfg else [],
        }

        # Special handling for Kafka UI
        if infra_key == 'kafka-ui':
            services[service_name]['environment'] = {
                'KAFKA_CLUSTERS_0_NAME': 'simpleec',
                'KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS': 'kafka:9092'
            }

        if 'depends_on' in infra_cfg:
            services[service_name]['depends_on'] = infra_cfg['depends_on']

        if infra_cfg.get('restart'):
            services[service_name]['restart'] = infra_cfg['restart']
        else:
            services[service_name]['restart'] = 'unless-stopped'

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
                'SPRING_PROFILES_ACTIVE': 'docker',
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
                'SPRING_PROFILES_ACTIVE': 'docker',
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
                'SPRING_PROFILES_ACTIVE': 'docker',
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

    # API
    services['simpleec-api'] = {
        'image': 'simpleec-api',
        'build': {
            'context': '.',
            'dockerfile': 'docker/Dockerfile.api'
        },
        'container_name': 'simpleec-api',
        'ports': ['8082:8080'],
        'environment': {
            'SPRING_PROFILES_ACTIVE': 'docker',
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

    # Frontend apps
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

    # Nginx
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
