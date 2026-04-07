# SimpleEC OMS 文档索引

**Last Updated**: Apr 7, 2026

---

## 🎯 根据用途选择文档

### 我想... 快速开始
👉 **[QUICK_START.md](QUICK_START.md)** (3分钟)
- Docker启动
- 登录测试
- API验证

### 我想... 了解项目概览
👉 **[README.md](README.md)**
- 项目简介
- 支持的通路
- 基本架构

### 我想... 检查当前系统状态
👉 **[OPERATIONS_CURRENT_STATUS.md](OPERATIONS_CURRENT_STATUS.md)** ⭐ **最新 (Apr 7)**
- 系统健康状态
- 最近修复（Platform Capabilities + Channel Health）
- API端点检查表
- 故障排除指南

### 我想... 深入理解系统设计
👉 **[DESIGN_v2.md](DESIGN_v2.md)** (224KB，完整设计)
- 完整的系统架构
- 数据流设计
- 通路集成设计

### 我想... 了解 Platform 管理
👉 **[docs/7-IMPLEMENTATION/PLATFORM_CAPABILITIES_GUIDE.md](docs/7-IMPLEMENTATION/PLATFORM_CAPABILITIES_GUIDE.md)** ⭐ **新增**
- capabilities JSONB 设计
- OAuth 类型设定
- Token Labels 自定义
- 平台能力旗标

### 我想... 了解 Channel 健康监控
👉 **[docs/7-IMPLEMENTATION/CHANNEL_HEALTH_MONITORING.md](docs/7-IMPLEMENTATION/CHANNEL_HEALTH_MONITORING.md)** ⭐ **新增**
- 健康检查机制
- Redis 缓存策略
- 同步日志 API
- ChannelHealthOverview 元件

### 我想... 配置和部署
👉 **[DEPLOYMENT_GUIDE.md](DEPLOYMENT_GUIDE.md)**
- 详细部署步骤
- 环境配置
- 性能调优

### 我想... 获得工作指示
👉 **[CLAUDE.md](CLAUDE.md)**
- 与Claude协作的指南
- 工作流
- 最佳实践

### 我想... 了解代码结构
👉 **[CODE_STRUCTURE.md](CODE_STRUCTURE.md)**
- Java后端结构
- Vue前端结构
- 目录说明

---

## 📚 按功能分类的文档

### 快速参考
| 文档 | 大小 | 用途 |
|------|------|------|
| [QUICK_START.md](QUICK_START.md) | 8KB | 3分钟启动系统 |
| [QUICK_COMMANDS.md](QUICK_COMMANDS.md) | 5KB | 常用命令速查 |
| [NETWORK_ACCESS.md](NETWORK_ACCESS.md) | 8KB | 网络和端口配置 |
| [KAFKA_QUICKSTART.md](KAFKA_QUICKSTART.md) | 4KB | Kafka操作快速参考 |

### 核心设计和架构
| 文档 | 大小 | 用途 |
|------|------|------|
| [DESIGN_v2.md](DESIGN_v2.md) | 224KB | ⭐ **完整系统设计** |
| [ARCHITECTURE_OVERVIEW.md](ARCHITECTURE_OVERVIEW.md) | 15KB | 系统架构全景图 |
| [PLATFORM_MAPPING.md](PLATFORM_MAPPING.md) | 通路状态映射和规范化 |
| [SCHEMA.md](SCHEMA.md) | 19+ 张表 DDL 定义 |
| [CODE_STRUCTURE.md](CODE_STRUCTURE.md) | 13KB | 代码目录结构 |

### Platform & Channel 管理
| 文档 | 状态 | 用途 |
|------|------|------|
| [PLATFORM_CAPABILITIES_GUIDE.md](docs/7-IMPLEMENTATION/PLATFORM_CAPABILITIES_GUIDE.md) | ⭐ **新增** | Platform capabilities 设定指南 |
| [CHANNEL_HEALTH_MONITORING.md](docs/7-IMPLEMENTATION/CHANNEL_HEALTH_MONITORING.md) | ⭐ **新增** | Channel 健康监控指南 |
| [SHOPEE_OAUTH_GUIDE.md](docs/7-IMPLEMENTATION/SHOPEE_OAUTH_GUIDE.md) | ✅ 最新 | Shopee OAuth 授权流程 |
| [CHANNEL_IMPLEMENTATION_GUIDE.md](docs/7-IMPLEMENTATION/CHANNEL_IMPLEMENTATION_GUIDE.md) | ✅ | 新平台接入指南 |

### 事件流和 Kafka
| 文档 | 用途 |
|------|------|
| [CORE_CONTRACTS.md](docs/3-EVENT-FLOW/CORE_CONTRACTS.md) | Kafka Topic 定义和消息格式 |
| [DATA_FLOW_MAPPING.md](docs/3-EVENT-FLOW/DATA_FLOW_MAPPING.md) | 数据流映射表 |
| [HANDLER_REGISTRY.md](docs/3-EVENT-FLOW/HANDLER_REGISTRY.md) | Handler 注册与路由 |
| [REDIS_DEDUPLICATION.md](docs/3-EVENT-FLOW/REDIS_DEDUPLICATION.md) | Redis 去重机制 |
| [KAFKA_AUTOMATION_SUMMARY.md](docs/5-KAFKA/KAFKA_AUTOMATION_SUMMARY.md) | Kafka 自动化配置 |

### 部署和运维
| 文档 | 状态 | 用途 |
|------|------|------|
| [OPERATIONS_CURRENT_STATUS.md](OPERATIONS_CURRENT_STATUS.md) | ⭐ **最新** | 当前系统状态和修复记录 |
| [DEPLOYMENT_GUIDE.md](DEPLOYMENT_GUIDE.md) | ✅ | 详细部署步骤 |
| [OPERATIONS_RUNBOOK.md](docs/6-OPERATIONS/OPERATIONS_RUNBOOK.md) | ✅ | 运维手册 |
| [DOCKER_GUIDE.md](docs/6-OPERATIONS/DOCKER_GUIDE.md) | ✅ | Docker 指南 |
| [HEALTH_MONITORING_GUIDE.md](docs/6-OPERATIONS/HEALTH_MONITORING_GUIDE.md) | ✅ | 健康监控指南 |

### 规则和技术规范
| 文档 | 用途 |
|------|------|
| [docs/rules/INDEX.md](docs/rules/INDEX.md) | 规则书导航 |
| [docs/rules/tech/capabilities-model.md](docs/rules/tech/capabilities-model.md) | Capabilities 模型规范 |
| [docs/rules/tech/kafka-envelope.md](docs/rules/tech/kafka-envelope.md) | Kafka 消息信封规范 |
| [docs/rules/tech/platform-api.md](docs/rules/tech/platform-api.md) | 平台 API 集成规范 |
| [docs/rules/tech/rest-api.md](docs/rules/tech/rest-api.md) | REST API 设计规范 |

### 学习和参考
| 文档 | 大小 | 用途 |
|------|------|------|
| [CLAUDE.md](CLAUDE.md) | 14KB | Claude协作指南 |
| [DESIGN_RULES.md](DESIGN_RULES.md) | 设计模式与 SOLID 应用 |
| [WORK_PRINCIPLES.md](WORK_PRINCIPLES.md) | 工作约法三章 |
| [GLOSSARY.zh-TW.md](GLOSSARY.zh-TW.md) | 术语词典（繁体中文） |

---

## 📊 文档更新时间线

| 日期 | 文档 | 更新内容 |
|------|------|--------|
| **Apr 7, 2026** | OPERATIONS_CURRENT_STATUS.md | Platform Capabilities UI + Channel Health + Nginx DNS |
| **Apr 7, 2026** | admin-app/types.ts, PlatformForm.vue, PlatformTable.vue | Platform 管理完整 capabilities 设定 |
| **Apr 7, 2026** | user-app/ChannelPage.vue | Token 永远显示 + 同步日志 Drawer |
| **Apr 7, 2026** | Platform.java, JsonNodeConverter.java | JSONB 映射修复 |
| **Apr 7, 2026** | docker/nginx.conf | 动态 DNS 解析，避免 502 |
| **Apr 6, 2026** | SHOPEE_OAUTH_GUIDE.md | Shopee OAuth 授权流程 |
| **Apr 6, 2026** | OPERATIONS_CURRENT_STATUS.md | 全面审查 Round 2 + 扩充性修复 |
| **Apr 6, 2026** | INCIDENT-2026-04-06-frontend-api-mismatch.md | 前端代码遗失事故报告 |
| **Mar 27, 2026** | 多个文件 | Stats Pipeline 修复 + Test Seeder |
| **Mar 27, 2026** | 多个文件 | 全面审查修复 Batch 1+2 |

---

## 🎓 推荐阅读顺序

### 对于新开发者
1. **[README.zh-TW.md](README.zh-TW.md)** - 了解项目
2. **[QUICK_START.md](QUICK_START.md)** - 启动系统
3. **[CODE_STRUCTURE.md](CODE_STRUCTURE.md)** - 理解代码组织
4. **[CLAUDE.md](CLAUDE.md)** - 学习工作流
5. **[ARCHITECTURE_OVERVIEW.md](docs/1-ARCHITECTURE/ARCHITECTURE_OVERVIEW.md)** - 深入理解架构

### 对于开发新平台
1. **[CHANNEL_IMPLEMENTATION_GUIDE.md](docs/7-IMPLEMENTATION/CHANNEL_IMPLEMENTATION_GUIDE.md)** - 平台接入概念
2. **[docs/rules/tech/platform-api.md](docs/rules/tech/platform-api.md)** - API 集成规范
3. **[docs/rules/tech/kafka-envelope.md](docs/rules/tech/kafka-envelope.md)** - Kafka 消息格式
4. **[CORE_CONTRACTS.md](docs/3-EVENT-FLOW/CORE_CONTRACTS.md)** - 事件流契约

### 对于系统运维
1. **[OPERATIONS_CURRENT_STATUS.md](OPERATIONS_CURRENT_STATUS.md)** - 当前状态
2. **[QUICK_COMMANDS.md](docs/0-START/QUICK_COMMANDS.md)** - 常用命令
3. **[OPERATIONS_RUNBOOK.md](docs/6-OPERATIONS/OPERATIONS_RUNBOOK.md)** - 运维手册
4. **[HEALTH_MONITORING_GUIDE.md](docs/6-OPERATIONS/HEALTH_MONITORING_GUIDE.md)** - 健康监控

### 对于架构师/决策者
1. **[DESIGN_v2.md](DESIGN_v2.md)** - 完整设计
2. **[ARCHITECTURE_OVERVIEW.md](docs/1-ARCHITECTURE/ARCHITECTURE_OVERVIEW.md)** - 架构全景
3. **[PLATFORM_CAPABILITIES_GUIDE.md](docs/7-IMPLEMENTATION/PLATFORM_CAPABILITIES_GUIDE.md)** - Capabilities 设计
4. **[docs/rules/INDEX.md](docs/rules/INDEX.md)** - 规则书

---

## 🔍 快速查找

### 如何找到...

**Kafka相关**
- Kafka启动和操作：[KAFKA_QUICKSTART.md](docs/5-KAFKA/KAFKA_QUICKSTART.md)
- Kafka自动化配置：[KAFKA_AUTOMATION_SUMMARY.md](docs/5-KAFKA/KAFKA_AUTOMATION_SUMMARY.md)
- Kafka事件样本：[EVENT_SAMPLES.md](docs/3-EVENT-FLOW/EVENT_SAMPLES.md)
- 消息信封规范：[kafka-envelope.md](docs/rules/tech/kafka-envelope.md)

**数据库相关**
- Schema定义：[SCHEMA.md](docs/4-SCHEMA/SCHEMA.md)
- 数据库初始化：[DEPLOYMENT_GUIDE.md](DEPLOYMENT_GUIDE.md)
- JSONB 字段设计：[JSONB_SCHEMA_AND_API_FIELDS.md](docs/4-SCHEMA/JSONB_SCHEMA_AND_API_FIELDS.md)

**API相关**
- API端点状态：[OPERATIONS_CURRENT_STATUS.md](OPERATIONS_CURRENT_STATUS.md)
- REST API 设计规范：[rest-api.md](docs/rules/tech/rest-api.md)
- 平台 API 研究（17个通路）：[docs/2-API/](docs/2-API/)

**Platform & Channel 相关**
- Platform capabilities 设定：[PLATFORM_CAPABILITIES_GUIDE.md](docs/7-IMPLEMENTATION/PLATFORM_CAPABILITIES_GUIDE.md)
- Channel 健康监控：[CHANNEL_HEALTH_MONITORING.md](docs/7-IMPLEMENTATION/CHANNEL_HEALTH_MONITORING.md)
- Shopee OAuth 流程：[SHOPEE_OAUTH_GUIDE.md](docs/7-IMPLEMENTATION/SHOPEE_OAUTH_GUIDE.md)
- 新平台接入指南：[CHANNEL_IMPLEMENTATION_GUIDE.md](docs/7-IMPLEMENTATION/CHANNEL_IMPLEMENTATION_GUIDE.md)

**网络和部署相关**
- 端口和网络配置：[NETWORK_ACCESS.md](docs/6-OPERATIONS/NETWORK_ACCESS.md)
- 部署步骤：[DEPLOYMENT_GUIDE.md](DEPLOYMENT_GUIDE.md)
- Nginx 动态 DNS：[OPERATIONS_CURRENT_STATUS.md](OPERATIONS_CURRENT_STATUS.md) § Nginx 动态 DNS 解析

---

## 💡 文档维护建议

1. **每次部署后**: 更新 OPERATIONS_CURRENT_STATUS.md
2. **每个功能完成后**: 更新相关的设计文档和规则
3. **每个修复后**: 在 OPERATIONS_CURRENT_STATUS.md 记录
4. **每月清理**: 归档过时的临时文档到 `docs/archive/`

---

**维护者**: Tom
**最后更新**: Apr 7, 2026
**版本**: 2.0
