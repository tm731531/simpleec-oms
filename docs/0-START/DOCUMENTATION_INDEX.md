# SimpleEC OMS 文档索引

**Last Updated**: Feb 24, 2026

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
👉 **[OPERATIONS_CURRENT_STATUS.md](OPERATIONS_CURRENT_STATUS.md)** ⭐ **最新**
- 系统健康状态
- 最近修复（Feb 24）
- API端点检查表
- 故障排除指南

### 我想... 深入理解系统设计
👉 **[DESIGN_v2.md](DESIGN_v2.md)** (224KB，完整设计)
- 完整的系统架构
- 数据流设计
- 通路集成设计

### 我想... 配置和部署
👉 **[DEPLOYMENT_GUIDE.md](DEPLOYMENT_GUIDE.md)**
- 详细部署步骤
- 环境配置
- 性能调优

### 我想... 修复系统重启问题
👉 **[docker/SYSTEMD_SERVICE_FIX.md](docker/SYSTEMD_SERVICE_FIX.md)**
- systemd service配置
- Docker image缓存问题
- 重启后版本部署

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
| [NETWORK_ACCESS.md](NETWORK_ACCESS.md) | 8KB | 网络和端口配置 |
| [KAFKA_QUICKSTART.md](KAFKA_QUICKSTART.md) | 4KB | Kafka操作快速参考 |
| [TASKS_8_9_QUICK_REFERENCE.md](TASKS_8_9_QUICK_REFERENCE.md) | 9KB | 任务8-9快速参考 |

### 核心设计和架构
| 文档 | 大小 | 用途 |
|------|------|------|
| [DESIGN_v2.md](DESIGN_v2.md) | 224KB | ⭐ **完整系统设计** |
| [PLATFORM_MAPPING.md](PLATFORM_MAPPING.md) | 通路状态映射和规范化 |
| [SCHEMA_ALIGNMENT_ACTUAL_DB.md](SCHEMA_ALIGNMENT_ACTUAL_DB.md) | Kafka消息与数据库对齐 |
| [EVENT_SAMPLES.md](EVENT_SAMPLES.md) | Kafka事件流示例 |
| [CODE_STRUCTURE.md](CODE_STRUCTURE.md) | 13KB | 代码目录结构 |

### 部署和运维
| 文档 | 大小 | 用途 |
|------|------|------|
| [OPERATIONS_CURRENT_STATUS.md](OPERATIONS_CURRENT_STATUS.md) | ⭐ **当前系统状态和修复** |
| [DEPLOYMENT_GUIDE.md](DEPLOYMENT_GUIDE.md) | 13KB | 详细部署步骤 |
| [docker/SYSTEMD_SERVICE_FIX.md](docker/SYSTEMD_SERVICE_FIX.md) | 系统服务和重启问题 |
| [README.DEPLOYMENT.md](README.DEPLOYMENT.md) | 5KB | 部署说明（备用） |

### 学习和参考
| 文档 | 大小 | 用途 |
|------|------|------|
| [CLAUDE.md](CLAUDE.md) | 14KB | Claude协作指南 |
| [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) | 16KB | 实现计划（参考） |
| [CRUD-GUIDE.md](CRUD-GUIDE.md) | 6KB | CRUD操作指南 |

### 项目计划和进度
| 文档 | 大小 | 用途 |
|------|------|------|
| [API_RESEARCH_SUMMARY.md](API_RESEARCH_SUMMARY.md) | 10KB | 7个通路API研究 |
| [PROGRESS.md](PROGRESS.md) | 12KB | 项目进度 |
| [PROJECT_COMPLETION_REPORT.md](PROJECT_COMPLETION_REPORT.md) | 9KB | 完成报告 |

### 测试和验证（历史）
| 文档 | 大小 | 用途 | 状态 |
|------|------|------|------|
| [FINAL_VERIFICATION_REPORT_2026-02-21.md](FINAL_VERIFICATION_REPORT_2026-02-21.md) | 12KB | 验证报告 | ⏰ Feb 21 |
| [QA_REPORT_2026-02-21.md](QA_REPORT_2026-02-21.md) | 30KB | QA测试报告 | ⏰ Feb 21 |
| [VERIFICATION_REPORT_INDEX.md](VERIFICATION_REPORT_INDEX.md) | 7KB | 验证报告索引 | ⏰ 旧 |

---

## 🔴 需要清理的文档（考虑归档）

以下文档可能过时，建议考虑归档到 `docs/archive/` 文件夹：

```
DESIGN.md (旧版，用DESIGN_v2.md替代)
BRANCHES.md (分支信息)
REWRITE_PLAN.md (旧计划)
README-DOCS-ONLY.md (重复)
README_SETUP.md (重复)
README-SIMPLE.md (重复)
IMPLEMENTATION_NOTES.md (1.8KB，太小)
TASKS_10_11_SUMMARY.md (任务记录)
TASKS_10_11_QUICK_REFERENCE.md (任务记录)
TASKS_8_9_IMPLEMENTATION_SUMMARY.md (任务记录)
TASKS_8_9_TECHNICAL_DETAILS.md (任务记录)
```

**清理命令**：
```bash
mkdir -p docs/archive
mv DESIGN.md BRANCHES.md REWRITE_PLAN.md README*.md TASKS_*.md IMPLEMENTATION_NOTES.md docs/archive/
```

---

## 📊 文档更新时间线

| 日期 | 文档 | 更新内容 |
|------|------|--------|
| **Feb 24, 2026** | OPERATIONS_CURRENT_STATUS.md | 创建 ⭐ 当前状态 |
| **Feb 24, 2026** | docker/SYSTEMD_SERVICE_FIX.md | 创建 - 修复指南 |
| **Feb 24, 2026** | DOCUMENTATION_INDEX.md | 创建本文件 - 文档索引 |
| **Feb 23, 2026** | DESIGN_v2.md | 更新 - 完整设计 |
| **Feb 21, 2026** | QA_REPORT_2026-02-21.md | 创建 - QA报告 |

---

## 🎓 推荐阅读顺序

### 对于新开发者
1. **[README.md](README.md)** - 了解项目
2. **[QUICK_START.md](QUICK_START.md)** - 启动系统
3. **[CODE_STRUCTURE.md](CODE_STRUCTURE.md)** - 理解代码组织
4. **[CLAUDE.md](CLAUDE.md)** - 学习工作流
5. **[DESIGN_v2.md](DESIGN_v2.md)** - 深入理解架构

### 对于系统运维
1. **[OPERATIONS_CURRENT_STATUS.md](OPERATIONS_CURRENT_STATUS.md)** - 当前状态
2. **[QUICK_START.md](QUICK_START.md)** - 快速启动
3. **[DEPLOYMENT_GUIDE.md](DEPLOYMENT_GUIDE.md)** - 部署指南
4. **[docker/SYSTEMD_SERVICE_FIX.md](docker/SYSTEMD_SERVICE_FIX.md)** - 系统配置
5. **[KAFKA_QUICKSTART.md](KAFKA_QUICKSTART.md)** - Kafka操作

### 对于架构师/决策者
1. **[DESIGN_v2.md](DESIGN_v2.md)** - 完整设计
2. **[PLATFORM_MAPPING.md](PLATFORM_MAPPING.md)** - 通路支持
3. **[API_RESEARCH_SUMMARY.md](API_RESEARCH_SUMMARY.md)** - API研究
4. **[PROJECT_COMPLETION_REPORT.md](PROJECT_COMPLETION_REPORT.md)** - 完成报告

---

## 🔍 快速查找

### 如何找到...

**Kafka相关**
- Kafka启动和操作：[KAFKA_QUICKSTART.md](KAFKA_QUICKSTART.md)
- Kafka自动化配置：[KAFKA_AUTOMATION_SUMMARY.md](KAFKA_AUTOMATION_SUMMARY.md)
- Kafka事件样本：[EVENT_SAMPLES.md](EVENT_SAMPLES.md)

**数据库相关**
- Schema定义：[SCHEMA_ALIGNMENT_ACTUAL_DB.md](SCHEMA_ALIGNMENT_ACTUAL_DB.md)
- 数据库初始化：[DEPLOYMENT_GUIDE.md](DEPLOYMENT_GUIDE.md)

**API相关**
- API端点状态：[OPERATIONS_CURRENT_STATUS.md](OPERATIONS_CURRENT_STATUS.md)
- API研究（7个通路）：[API_RESEARCH_SUMMARY.md](API_RESEARCH_SUMMARY.md)
- API设计细节：[DESIGN_v2.md](DESIGN_v2.md)

**通路集成相关**
- 通路状态映射：[PLATFORM_MAPPING.md](PLATFORM_MAPPING.md)
- 7个通路概况：[API_RESEARCH_SUMMARY.md](API_RESEARCH_SUMMARY.md)

**网络和部署相关**
- 端口和网络配置：[NETWORK_ACCESS.md](NETWORK_ACCESS.md)
- 部署步骤：[DEPLOYMENT_GUIDE.md](DEPLOYMENT_GUIDE.md)

---

## 💡 文档维护建议

1. **每周更新**: OPERATIONS_CURRENT_STATUS.md 中的系统状态
2. **每个功能完成后**: 更新相关的设计文档
3. **每个修复后**: 在 OPERATIONS_CURRENT_STATUS.md 记录
4. **季度清理**: 归档过时的任务和临时文档

---

**维护者**: Tom
**最后更新**: Feb 24, 2026
**版本**: 1.0
