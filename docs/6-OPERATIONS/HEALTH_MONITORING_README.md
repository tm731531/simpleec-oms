# SimpleEC OMS 健康監控系統

> 一個完整的多層次監控解決方案，用於實時追蹤商家通路和電商平台的連接狀態

## 📚 文檔結構

本系統包含以下主要文檔,請按需要查閱:

### 📘 系統文檔

| 文檔 | 受眾 | 內容 |
|------|------|------|
| **[系統架構](./HEALTH_MONITORING_ARCHITECTURE.md)** | 開發者/架構師 | 完整的系統設計、數據流、技術棧 |
| **[用戶指南](./HEALTH_MONITORING_USER_GUIDE.md)** | 運營人員/商家 | 如何使用儀表板、解釋狀態碼、最佳實踐 |
| **[API參考](./HEALTH_MONITORING_API.md)** | 開發者/集成者 | 所有REST端點的完整文檔和示例 |
| **[故障排除](./HEALTH_MONITORING_TROUBLESHOOTING.md)** | 系統管理員/開發者 | 常見問題診斷和解決方案 |
| **[部署清單](./HEALTH_MONITORING_DEPLOYMENT_CHECKLIST.md)** | 部署人員/DBA | 從預部署到生產驗證的完整檢查清單 |

---

## 🚀 快速開始 (30秒)

### 最簡單的開始方式:

1. **訪問儀表板**
   ```
   http://localhost:8089/health
   ```

2. **登錄** (使用測試賬戶)
   - Email: `admin@a00000.com`
   - Password: `pass123456`

3. **查看整體狀態** - 「整體健康摘要」卡片
   - 看到綠色百分比 = 系統正常 ✅

### 常見任務

| 任務 | 操作 | 參考 |
|------|------|------|
| 檢查特定通路狀態 | 搜尋框輸入通路ID → 搜尋 | [用戶指南](./HEALTH_MONITORING_USER_GUIDE.md#通路狀態查詢) |
| 查看平台健康 | 點擊平台卡片 | [用戶指南](./HEALTH_MONITORING_USER_GUIDE.md#平台健康狀態卡片) |
| 理解錯誤消息 | 查看HTTP狀態碼對照表 | [用戶指南](./HEALTH_MONITORING_USER_GUIDE.md#狀態碼對照表) |
| 查詢API數據 | 使用REST端點 | [API參考](./HEALTH_MONITORING_API.md) |
| 解決問題 | 按症狀查閱 | [故障排除](./HEALTH_MONITORING_TROUBLESHOOTING.md) |
| 部署系統 | 按步驟執行 | [部署清單](./HEALTH_MONITORING_DEPLOYMENT_CHECKLIST.md) |

---

## ⭐ 核心特性

### 🕐 自動檢查
- **頻率**: 每5分鐘自動執行一次
- **覆蓋**: 所有7個MVP平台 + 商家所有啟用的通路
- **智能**: 只檢查啟用的通路,避免不必要的API調用

### 📊 實時監控
- **儀表板**: 一目了然的綠/紅狀態指示
- **歷史追蹤**: 完整的檢查歷史和趨勢分析
- **響應式UI**: 支持桌面、平板、手機訪問

### 🔍 詳細診斷
- **HTTP狀態碼**: 立即看到具體是什麼問題
  - 200 = 正常 ✅
  - 401 = 令牌失效 🔴
  - 403 = 權限不足 🔴
  - 500 = 平台故障 🔴
  - 503 = 平台不可用 🔴

### 📈 可擴展性
- **新平台**: 添加到 `config.yaml` 自動生成Docker服務
- **高可用**: Kafka消息隊列 + 多個Channel-Job消費者
- **數據持久化**: PostgreSQL長期存儲所有檢查記錄

---

## 📋 系統概覽

### 架構示意圖

```
┌─────────────────┐
│ HealthCheck     │  ← 每5分鐘執行
│ Scheduler       │
└────────┬────────┘
         │ 發佈任務
         ▼
┌─────────────────┐
│  Kafka Topics   │  ← 平台隊列
│  (shopee.fast   │
│   momo.slow等)  │
└────────┬────────┘
         │ 消費消息
         ▼
┌─────────────────────┐
│ Channel-Job Worker  │  ← 執行檢查
│ (shopee-fast,       │
│  momo-slow, etc)    │
└────────┬────────────┘
         │ 調用API
         ▼
┌─────────────────────┐
│ Platform APIs       │  ← 7個電商平台
│ (Shopee, Momo,      │
│  Yahoo, PChome...)  │
└─────────────────────┘
         │ 返回狀態碼
         ▼
┌──────────────────────────┐
│ channel_sync_logs Table  │  ← 記錄結果
│ (日期, 狀態, HTTP碼等)   │
└──────────┬───────────────┘
      ┌────┴───────────────┐
      ▼                    ▼
  ┌────────┐         ┌──────────┐
  │ REST   │         │ Frontend │
  │ API    │         │ Dashboard│
  └────────┘         └──────────┘
      │                  │
      └──────────┬───────┘
                 │
          用戶查看結果
```

### 系統組成

| 組件 | 技術 | 職責 |
|------|------|------|
| **Scheduler** | Spring Boot | 每5分鐘發起健康檢查 |
| **Channel-Job** | Spring Boot × 14 | 執行檢查,調用平台API,記錄結果 |
| **API** | Spring Boot | 提供REST端點查詢檢查結果 |
| **數據庫** | PostgreSQL | 存儲檢查歷史記錄 |
| **消息隊列** | Kafka | 分發檢查任務到各個Job |
| **前端** | Vue 3 | 儀表板UI展示結果 |

---

## 📊 支持的平台

完整支持以下7個MVP平台的健康檢查:

| # | 平台 | 狀態 | 檢查類型 |
|---|------|------|--------|
| 1 | 🛒 **Cyberbiz** | ✅ | 認證 + 平台 |
| 2 | 📱 **PChome** | ✅ | 認證 + 平台 |
| 3 | 💰 **MOMO** | ✅ | 認證 + 平台 |
| 4 | 🏪 **Shopline** | ✅ | 認證 + 平台 |
| 5 | 🛍️ **Yahoo購物** | ✅ | 認證 + 平台 |
| 6 | ⭐ **Shopee** | ✅ | 認證 + 平台 |
| 7 | 🌍 **Shopify** | ✅ | 認證 + 平台 |

**認證檢查**: 使用商家的API令牌驗證通路連接
**平台檢查**: 不需認證,只檢查平台整體可用性

---

## 🎯 使用場景

### 場景1: 日常運營監控
**人物**: 運營人員
**操作**: 每天登入儀表板查看各通路狀態
**目的**: 及時發現問題,快速解決

### 場景2: 商家故障排查
**人物**: 技術支持
**操作**:
1. 商家報告無法同步訂單
2. 搜尋商家通路ID
3. 查看最近檢查結果 → 看到401錯誤
4. 告知商家需要更新API令牌
**目的**: 快速定位問題根源

### 場景3: 平台故障響應
**人物**: 系統管理員
**操作**:
1. 看到某平台全部顯示紅色
2. 檢查HTTP狀態碼 → 都是500
3. 查看平台官方狀態頁 → 確認平台故障
4. 通知相關商家預期延遲
**目的**: 快速識別系統級問題

### 場景4: 性能分析
**人物**: 產品經理
**操作**:
1. 查詢每周的平均健康百分比
2. 分析各平台的失敗原因
3. 識別需要改進的地方
**目的**: 數據驅動的決策

### 場景5: API集成
**人物**: 第三方開發者
**操作**: 調用REST API獲取健康數據
**目的**: 集成到自己的系統中

---

## ⚙️ 配置說明

### 啟用/禁用通路

通路是否進行健康檢查由 `enable_sync` 字段控制:

```sql
-- 禁用某個通路的檢查
UPDATE channels SET enable_sync = false WHERE id = 'channel-xxx';

-- 啟用某個通路的檢查
UPDATE channels SET enable_sync = true WHERE id = 'channel-xxx';

-- 查看所有啟用的通路
SELECT id, merchant_id, platform_code FROM channels WHERE enable_sync = true;
```

### 調整檢查頻率

修改 `HealthCheckScheduler.java`:

```java
// 改為10分鐘檢查一次
@Scheduled(fixedRate = 600000)  // 毫秒
```

### 調整超時時間

修改 `RestTemplateConfig.java`:

```java
.setConnectTimeout(Duration.ofSeconds(5))   // 連接超時
.setReadTimeout(Duration.ofSeconds(10))     // 讀取超時
```

---

## 🔍 常見問題

### Q: 檢查的頻率可以改嗎?
**A**: 可以,詳見 [系統架構文檔](./HEALTH_MONITORING_ARCHITECTURE.md#擴展性考慮)

### Q: 怎樣添加新平台?
**A**: 四個步驟:
1. 在 `config.yaml` 中添加平台配置
2. 在 `PlatformApiClientImpl.java` 中添加API端點映射
3. 在 `HealthDashboard.vue` 中添加平台卡片
4. 重新部署

詳見 [系統架構文檔](./HEALTH_MONITORING_ARCHITECTURE.md#添加新平台)

### Q: 怎樣導出數據?
**A**: 可以:
- 直接截圖儀表板
- 查看歷史表格並複製
- 調用REST API程式化獲取
- 直接查詢數據庫

詳見 [API參考](./HEALTH_MONITORING_API.md)

### Q: 數據保留多久?
**A**: 取決於數據庫清理策略
- 建議: 保留30天歷史數據
- 可修改: `DELETE FROM channel_sync_logs WHERE created_at < NOW() - INTERVAL '30 days'`

### Q: 系統性能如何?
**A**:
- 響應時間 < 500ms
- 內存使用 < 1GB/服務
- CPU使用 < 5% (閒置)
- 支持 1000+ 商家通路

更多信息見 [部署清單](./HEALTH_MONITORING_DEPLOYMENT_CHECKLIST.md#性能檢查)

---

## 🛠️ 技術棧

```
Frontend:     Vue 3 + Vite + TypeScript + Element Plus
Backend:      Spring Boot 3.5.0 + Spring Data JPA
Database:     PostgreSQL 16
Message Queue: Kafka 3.7.1
Cache:        Redis 7
Container:    Docker + Docker Compose
```

---

## 📞 獲取幫助

### 遇到問題?

1. **查看本README** - 通常有快速答案
2. **查看對應文檔**:
   - 使用問題 → [用戶指南](./HEALTH_MONITORING_USER_GUIDE.md)
   - API問題 → [API參考](./HEALTH_MONITORING_API.md)
   - 系統問題 → [故障排除](./HEALTH_MONITORING_TROUBLESHOOTING.md)
   - 部署問題 → [部署清單](./HEALTH_MONITORING_DEPLOYMENT_CHECKLIST.md)
3. **聯繫技術支持** - 提供錯誤日誌和環境信息

### 文檔導航

```
健康監控系統文檔樹:
├── HEALTH_MONITORING_README.md (本文件)
├── HEALTH_MONITORING_ARCHITECTURE.md (系統設計)
├── HEALTH_MONITORING_USER_GUIDE.md (使用指南)
├── HEALTH_MONITORING_API.md (API文檔)
├── HEALTH_MONITORING_TROUBLESHOOTING.md (故障排除)
└── HEALTH_MONITORING_DEPLOYMENT_CHECKLIST.md (部署檢查)
```

---

## 📈 系統指標

### 核心指標

| 指標 | 值 | 說明 |
|------|-----|------|
| **檢查頻率** | 5分鐘 | 每5分鐘檢查一次所有通路和平台 |
| **支持平台** | 7個 | Shopee, Momo, Yahoo, PChome, Cyberbiz, Shopline, Shopify |
| **支持通路** | 1000+ | 可支持1000+商家通路並發監控 |
| **平均響應時間** | <500ms | 查詢API響應時間 |
| **可用性** | 99.5%+ | 系統設計目標 |
| **數據保留** | 30天 | 建議保留期限 |

---

## 🔄 更新歷史

| 版本 | 日期 | 變更 |
|------|------|------|
| **1.0** | 2026-02-23 | 首次發佈,包含完整文檔 |

---

## 📄 文檔協議

所有文檔遵循以下約定:
- 中文: 用於用戶和運營人員
- 英文: 用於API和技術規範
- Markdown格式: 便於版本控制和在線查閱

---

## ✅ 最後檢查清單

在投入生產前,確保:

- [ ] 已閱讀 [部署清單](./HEALTH_MONITORING_DEPLOYMENT_CHECKLIST.md)
- [ ] 已執行所有預部署檢查
- [ ] 已在測試環境驗證
- [ ] 已備份現有數據
- [ ] 已告知所有相關人員

---

**系統版本**: 1.0
**文檔版本**: 1.0
**最後更新**: 2026-02-23
**維護者**: SimpleEC OMS 開發團隊

---

## 🎉 下一步

根據你的角色選擇合適的文檔開始:

- **我是運營人員** → [用戶指南](./HEALTH_MONITORING_USER_GUIDE.md)
- **我是開發者** → [系統架構](./HEALTH_MONITORING_ARCHITECTURE.md)
- **我是系統管理員** → [部署清單](./HEALTH_MONITORING_DEPLOYMENT_CHECKLIST.md)
- **我遇到了問題** → [故障排除](./HEALTH_MONITORING_TROUBLESHOOTING.md)
- **我需要集成API** → [API參考](./HEALTH_MONITORING_API.md)
