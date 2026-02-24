#!/bin/bash
# Start SimpleEC OMS services on system boot
cd /home/tom/ONEEC/simpleec-oms

# Start all services with rebuild (ensures latest code is deployed)
# Build critical services first to ensure latest fixes are applied
docker compose build simpleec-api simpleec-user-app simpleec-admin-app >> /tmp/simpleec-startup.log 2>&1
docker compose up -d >> /tmp/simpleec-startup.log 2>&1

# Verify critical services started
echo "[$(date)] Verifying services..." >> /tmp/simpleec-startup.log
for i in {1..30}; do
  if docker ps | grep -q simpleec-nginx && docker ps | grep -q simpleec-user-app; then
    echo "[$(date)] ✅ Both nginx and user-app are running" >> /tmp/simpleec-startup.log
    break
  fi
  echo "[$(date)] Waiting for nginx and user-app... ($i/30)" >> /tmp/simpleec-startup.log
  sleep 1
done

# Note: docker compose up -d handles service dependencies automatically via depends_on
# No manual sync monitor needed
