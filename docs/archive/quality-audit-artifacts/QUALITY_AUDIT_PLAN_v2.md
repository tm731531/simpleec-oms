# SimpleEC OMS Quality Audit - 修正設計 (v2)

## 核心設計原則

**放棄 Provider Chain + Consumer Chain**，改為 **Sequential Phase Architecture**

目標：清晰的阶段、明確的同步點、無歧義的任務分配

---

## 【四階段順序執行】

### **第一阶段：架構層驗證（Architect Lead）**
**狀態**: `pending` → `in_progress` → `completed`

**Task #1 - Architect: 平台 API 與 Schema 協議檢查**

**输入**:
- 讀取文件：
  - `/docs/1-ARCHITECTURE/DESIGN_v2.md`
  - `/docs/2-API/ADMIN_API.md` 和 `/docs/2-API/USER_API.md`
  - `/docs/3-EVENT-FLOW/CORE_CONTRACTS.md`
  - `/docs/4-SCHEMA/SCHEMA.md`

**任務**:
1. 驗證 11 個模塊的架構邊界是否清晰
2. 檢查 16 個 Kafka Topic 的 Header/Body 約定是否一致
3. 確認 API 文檔中的字段和數據庫 Schema 是否匹配
4. 識別任何架構層的不一致（API 承諾了什麼，Schema 能否支持）

**交付物**:
```json
{
  "status": "completed",
  "findings": {
    "api_schema_alignment": "85%",
    "critical_issues": [
      {"issue": "...", "impact": "HIGH", "location": "..."}
    ],
    "recommendations": ["..."],
    "ready_for_backend_qa": true
  }
}
```

**依賴**: None（第一個開始）
**阻止**: Task #2 無法開始，直到此 Task 完成

**失敗條件**:
- 超過 2 小時未完成 → 強制標記為 `timeout`，繼續下一階段
- 發現 `CRITICAL` 問題 → 標記 `requires_architecture_revision`，需要開發人員手動修正後重新審查

---

### **第二階段：數據層驗證（Backend-QA Lead）**
**狀態**: `blocked` (等待 Task #1 完成) → `in_progress` → `completed`

**Task #2 - Backend-QA: Schema、Kafka、Handler 完整性驗證**

**前置條件**:
- Task #1 `completed` 或 `timeout` 後自動解鎖
- 讀取 Task #1 的交付物（了解已識別的架構問題）

**輸入**:
- 讀取文件：
  - `/docs/4-SCHEMA/SCHEMA.md`
  - `/docs/5-KAFKA/KAFKA_QUICKSTART.md`
  - `/docs/3-EVENT-FLOW/HANDLER_REGISTRY.md`
- 實際檢查：PostgreSQL schema 19 張表、Kafka 16 個 topic、所有 Handler 實現

**任務**:
1. 驗證所有 19 張表在數據庫中實際存在且結構正確
2. 確認 Kafka Topic 創建成功、Retention Policy 符合文檔
   - Order flow: 1 day retention
   - DLT: 30 days retention
3. 檢查 Handler Registry 是否完整（所有 TaskType 都有對應 handler）
4. 驗證事務邊界和原子性（DBA 層面的保證）
5. 識別任何數據層的風險（會導致數據遺失或不一致）

**交付物**:
```json
{
  "status": "completed",
  "findings": {
    "schema_integrity": "98%",
    "kafka_config": "PASS",
    "handler_coverage": "100%",
    "critical_issues": [],
    "data_risk_assessment": "LOW",
    "recommendations": ["..."],
    "ready_for_frontend_qa": true,
    "notes_for_architect": "..."
  }
}
```

**依賴**: Task #1 必須先完成
**阻止**: Task #3 和 Task #4 無法開始

**失敗條件**:
- 超過 2 小時未完成 → 強制標記為 `timeout`
- 發現數據層 `CRITICAL` 問題 → 需要 DBA 手動修正

---

### **第三階段：前端層驗證（Frontend-QA Lead）**
**狀態**: `blocked` (等待 Task #1 完成) → `in_progress` → `completed`

**Task #3 - Frontend-QA: User App + Admin App 實現完整性驗證**

**前置條件**:
- Task #1 `completed` 或 `timeout` 後自動解鎖
- 讀取 Task #1 的交付物（了解 API 設計）

**輸入**:
- 讀取文件：
  - `/docs/5-FRONTEND/USER_APP_IMPLEMENTATION.md`
  - `/docs/5-FRONTEND/ADMIN_APP_IMPLEMENTATION.md`
  - `/docs/2-API/ADMIN_API.md`、`/docs/2-API/USER_API.md`

**任務**:
1. 檢查 User App 的 6 個核心頁面是否實現完整
2. 檢查 Admin App 的管理界面是否與 API 契約一致
3. 驗證認證流程、Token 管理、Session 處理
4. 檢查所有 API 呼叫是否與 API 文檔匹配（參數、返回值、錯誤處理）
5. 驗證狀態管理（Pinia Store）是否正確

**交付物**:
```json
{
  "status": "completed",
  "findings": {
    "user_app_completeness": "95%",
    "admin_app_completeness": "92%",
    "api_integration_correctness": "98%",
    "critical_issues": [],
    "recommendations": ["..."],
    "ready_for_integration_test": true
  }
}
```

**依賴**: Task #1 必須先完成
**阻止**: Task #4 無法開始

**失敗條件**:
- 超過 2 小時未完成 → 強制標記為 `timeout`
- 發現 UI `CRITICAL` 問題 → 需要前端手動修正

---

### **第四階段：端到端整合驗證（Architect Lead）**
**狀態**: `blocked` (等待 Task #2 和 Task #3) → `in_progress` → `completed`

**Task #4 - Architect: 完整系統協調驗證**

**前置條件**:
- Task #2 `completed` 或 `timeout` 後解鎖
- Task #3 `completed` 或 `timeout` 後解鎖
- 両个都完成時，Task #4 自動開始

**輸入**:
- Task #1、#2、#3 的所有交付物（findings）
- 整個系統的文檔和代碼

**任務**:
1. 檢查三個層面的發現是否有相互衝突
   - API 層承諾了什麼 → 數據層能否保證 → 前端能否正確使用
2. 驗證端到端的數據流是否完整
   - Frontend 呼叫 API → Backend 讀寫數據 → Database 返回結果
3. 確認所有 CRITICAL 問題都被標記並有解決計劃
4. 生成最終的系統健康度報告

**交付物**:
```json
{
  "status": "completed",
  "executive_summary": "System is ready for UAT / 需要修正以下問題",
  "architecture_alignment_score": "92%",
  "data_integrity_score": "98%",
  "ui_integration_score": "96%",
  "overall_system_health": "GOOD / REQUIRES_FIXES / CRITICAL",
  "critical_items": ["..."],
  "recommendations": ["..."],
  "next_steps": ["..."]
}
```

**依賴**: Task #2 和 Task #3 都必須完成
**阻止**: None（最後一個階段）

---

## 【同步機制 & Timeout 保護】

```
Timeline:
┌──────────────────────────────────────────────┐
│ H0   H2   H4   H6   H8   H10                  │
├──────────────────────────────────────────────┤
│ [Task #1: Architect]|timeout                 │
│         [Task #2: Backend]|timeout            │
│         [Task #3: Frontend]|timeout           │
│                     [Task #4: Integration]    │
└──────────────────────────────────────────────┘

實線: 正常完成
|: 可接受的 timeout 邊界
```

**Timeout 規則**:
- 每個 Task 最多 2 小時
- 如果 2 小時未完成，自動標記 `timeout` 並解鎖下一阶段
- 被 timeout 的 Task 不會阻止後續階段

**通知機制**:
- Task 進入 `timeout` 時，自動通知 Team Lead
- 每 30 分鐘檢查一次 agent 是否還在運行

---

## 【失敗恢復流程】

如果發現 CRITICAL 問題：

```
Task #2 發現 CRITICAL → "Schema 無法支持 API 承諾"
  ↓
1. Backend-QA 標記為 `blocked_by_architecture_issue`
2. 通知 Architect 有需要討論的問題
3. Architect 和 Backend-QA 同步（離開 agent system，人工協調）
4. 修正問題後，重新執行 Task #1 或 Task #2
5. 完成後繼續下一階段
```

**誰來決定返工**:
- Architecture 層問題 → Architect + Tech Lead 決定
- Data 層問題 → Backend-QA + DBA 決定
- UI 層問題 → Frontend-QA + PM 決定

---

## 【Agent 分配 & 配置】

| Agent | Model | Task | 責任 |
|-------|-------|------|------|
| `architect` | Opus 4.6 | #1 & #4 | 架構層決策 + 最終驗證 |
| `backend-qa` | Opus 4.6 | #2 | 數據層驗證 |
| `frontend-qa` | Opus 4.6 | #3 | UI 層驗證 |
| `team-lead` | Haiku 4.5 | 協調 | 監控進度、強制 timeout、匯總報告 |

**每個 Agent 的明確指令**:
```markdown
You are the ARCHITECT on simpleec-oms-quality-audit-v2 team.

Your responsibilities:
1. Task #1 (Phase 1): 審查架構與 API 設計 ✓
2. Task #4 (Phase 4): 整合所有發現，給出最終報告 ✓

Timeline:
- Phase 1: 0-2 小時（Task #1）
- Phase 4: 4-6 小時（Task #4，在 Task #2 & #3 完成後）

If you complete Phase 1 and need to wait for Phase 4:
- You are DONE, do NOT start Task #4 until notified
- Do NOT contact backend-qa or frontend-qa
- Do NOT re-read files or generate extra reports
- Simply wait for team-lead notification when Phase 4 begins

When Phase 4 starts:
- You will receive explicit notification from team-lead
- Begin Task #4: Integrate findings from all phases
```

---

## 【預期成果】

✅ **清晰的阶段划分** — 沒有歧義，每個 agent 知道自己做什麼
✅ **顯式依賴關係** — Task 系統會強制順序，不會亂指派
✅ **有 Timeout 保護** — 不會再卡 24 小時
✅ **同步點明確** — Architect 在關鍵時刻把關
✅ **失敗恢復流程清晰** — 知道誰決定返工、怎麼返工

---

## 【與之前設計的區別】

| 方面 | 舊設計 | 新設計 (v2) |
|------|-------|-----------|
| **结构** | Provider Chain + Consumer Chain（複雜） | Sequential Phase（清晰） |
| **依赖** | 隐式（文字描述） | 显式（Task.blockedBy / Task.blocks） |
| **同步点** | 4 個但沒編碼 | 4 個，清晰標記在每個 Task 的 `交付物` 裡 |
| **Agent 等待** | 沒有規則，導致等待 | 規則清晰：只在特定時刻等待下一階段 |
| **Timeout** | 無保護，卡 24+ 小時 | 2 小時 timeout，自動解鎖 |
| **失敗恢復** | "回到对应开发阶段修正"（誰決定？） | 明確列出：Architecture/Data/UI 問題的誰決定返工 |

