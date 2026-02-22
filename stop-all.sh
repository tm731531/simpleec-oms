#!/bin/bash

# SimpleEC OMS - 完整系統停止腳本

echo "════════════════════════════════════════════════════════════"
echo "  停止 SimpleEC OMS 系統..."
echo "════════════════════════════════════════════════════════════"
echo ""

# 停止 Gradle 進程
echo "停止後端 API..."
pkill -f "gradle.*bootRun" || true
pkill -f "java.*ApiApplication" || true

# 停止 Node 進程 (Vite)
echo "停止前端應用開發伺服器..."
pkill -f "node.*vite" || true
pkill -f "npm.*dev" || true

# 停止 Docker 容器
echo "停止 Docker 容器..."
docker compose down 2>&1 || docker-compose down 2>&1

echo ""
echo "✓ 系統已停止"
echo ""
