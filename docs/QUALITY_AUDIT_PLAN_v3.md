# SimpleEC OMS Quality Audit - 改進設計 (v3)

**文檔狀態**: 可直接實施
**生成日期**: 2026-03-17
**基礎**: QUALITY_AUDIT_PLAN_v2 (保留核心架構) + AGENT_TEAM_DESIGN_IMPROVEMENTS (改進細節)
**標記規則**: 🔴 修改部分 | 🟢 新增部分 | 無標記 = 保留自 v2

---

## 核心設計原則（保留自 v2）

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

**交付物** (🔴 改進: 添加 JSON Schema 定義):
```json
{
  "task_id": 1,
  "status": "completed",
  "findings": {
    "api_schema_alignment_score": <number 0-100>,
    "critical_issues": [
      {
        "issue": <string, 簡短描述>,
        "impact": <"CRITICAL" | "HIGH" | "MEDIUM">,
        "location": <string, 文件名:行號>,
        "fix_effort_hours": <number>,
        "recommendation": <string>
      }
    ],
    "p2_issues": [
      {
        "issue": <string>,
        "impact": <"MEDIUM" | "LOW">,
        "location": <string>,
        "recommendation": <string>
      }
    ],
    "p3_recommendations": [<string>],
    "ready_for_backend_qa": <boolean>
  }
}
```

**依賴** (🔴 改進: 明確編碼):
```json
{
  "blockedBy": [],      // 我不等任何人
  "blocks": [2, 3]      // 我阻擋 Task #2 和 #3
}
```

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

**交付物** (🔴 改進: 添加驗證標記):
```json
{
  "task_id": 2,
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

**依賴** (🔴 改進: 明確編碼):
```json
{
  "blockedBy": [1],    // 等待 Task #1
  "blocks": [4]        // 阻擋 Task #4
}
```

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

**交付物** (🔴 改進: 添加驗證標記):
```json
{
  "task_id": 3,
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

**依賴** (🔴 改進: 明確編碼):
```json
{
  "blockedBy": [1],    // 等待 Task #1
  "blocks": [4]        // 阻擋 Task #4
}
```

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

**交付物** (🔴 改進: 添加完整的系統評分):
```json
{
  "task_id": 4,
  "status": "completed",
  "executive_summary": <string>,
  "architecture_alignment_score": <number 0-100>,
  "data_integrity_score": <number 0-100>,
  "ui_integration_score": <number 0-100>,
  "overall_system_health": <"EXCELLENT" | "GOOD" | "REQUIRES_FIXES" | "CRITICAL">,
  "critical_items": [
    {
      "item": <string>,
      "found_in_phase": <1|2|3>,
      "impact": <string>,
      "resolution_plan": <string>
    }
  ],
  "recommendations": [<string>],
  "next_steps": [<string>]
}
```

**依賴** (🔴 改進: 明確編碼):
```json
{
  "blockedBy": [2, 3],  // 等待 Task #2 和 #3
  "blocks": []          // 最後一個階段
}
```

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

**🔴 改進的 Timeout 規則**:
- 每個 Task 最多 **120 分鐘**（明確編碼在 Task 定義中）
- 如果 120 分鐘未完成，自動標記 `timeout` 並解鎖下一阶段
- 被 timeout 的 Task **不會阻止後續階段**
- Team-lead 每 **30 分鐘檢查一次** 是否有 Task 超時
- Timeout 不是失敗，只是狀態轉遷

**🔴 改進的通知機制**:
- 當 Task 進入 `timeout` 時，自動通知 Team Lead
- Team Lead 自動解鎖依賴的 Tasks：`TaskUpdate(id=X, blockedBy=[])`
- 所有通知都通過 **SendMessage** 工具，不依賴隱式通訊

**🔴 新增的 Session 監視機制** (🟢):
- Team-lead 每 **30 秒** 檢查一次：
  - 所有 agents 是否還在運行
  - 是否有 agents 超過 2 小時沒有進展
  - 是否有 agents 顯示 "orphaned" 狀態（parent 死亡了）
- 如果發現孤立 agent：
  - 標記該 agent 的 task 為 `FAILED_PARENT_DIED`
  - 標記依賴的 tasks 為 `BLOCKED_BY_PARENT_FAILURE`
  - 發送通知："Agent Team 失敗，需要人工介入"

---

## 【失敗恢復流程】

如果發現 CRITICAL 問題：

```
Task #2 發現 CRITICAL → "Schema 無法支持 API 承諾"
  ↓
1. Backend-QA 在交付物中標記 `ready_for_next_phase: false`
   + 詳細的 critical_issues 清單

2. Team-lead 檢測到該標記，自動：
   - 通知 Architect：`"Task #2 發現 CRITICAL 問題，需要決策"`
   - 將 Task #4 標記為 `blocked`（額外的 blockedBy）
   - 等待 Architect 和 Backend-QA 的人工同步

3. Architect 和 Backend-QA 離開 agent system，人工協調
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
| `team-lead` | Haiku 4.5 | 協調 | 監控進度、強制 timeout、Session 生命週期、匯總報告 |

### 🟢 新增的 Agent Prompt 要求

**每個 Agent 的 Prompt 必須包含**:

1. **責任清晰度** ✅
   - 一個 Agent，一件事
   - 目標可測
   - 交付物明確

2. **停止條件明確** ✅ (🔴 改進: 之前缺失)
   - 明確說什麼時候應該停止
   - 沒有「循環工作」的空間
   - 明確的等待機制

3. **依賴關係編碼** ✅ (🟢 新增)
   - Task.blockedBy 和 Task.blocks 已確認
   - 沒有循環依賴

4. **超時保護** ✅ (🔴 改進: 實現細節現已明確)
   - 明確提到超時時間（2 小時）
   - 告訴 Agent 超時時會發生什麼
   - Timeout 不導致錯誤，只是解鎖

5. **Agent 之間的協調** ✅ (🔴 改進: 之前隱式)
   - 明確說是否應該主動聯繫其他 Agent（通常是 NO）
   - 明確說如何接收通知（team-lead 消息）
   - 明確說何時應該報告問題

---

## 【Agent Prompt 模板】

### 🟢 共通部分（所有 Agents）

```markdown
**你的責任:**
- 清晰的單一目標（1-2 句）
- 不要承擔其他任務

**Timeline:**
- Phase X: H0-H2 (或具體時間範圍)
- 你有 2 小時完成此 Phase
- 如果超過 2 小時，系統會自動標記 timeout，不是你的錯

**通訊規則:**
- ✅ 收到 team-lead 的消息時執行
- ❌ 不要主動聯繫其他 agents
- ❌ 不要嘗試自己協調
- ❌ 如果有疑問，在交付物中記錄，team-lead 會看

**交付物格式:**
- 必須是有效的 JSON
- 必須包含 `status`, `findings`, `ready_for_next_phase` 字段
- 下一個 agent 會用 JSON parser 讀取

**完成後:**
⚠️ **STOP. Do NOT continue.**
- ✓ JSON 交付物已生成
- ✗ 不要重新分析
- ✗ 不要聯繫其他 agents
- ✗ 不要等待（timeout 會自動處理）
- 等待 team-lead 的下一個消息
```

---

## 【預期成果與評估標準】

✅ **清晰的阶段划分** — 沒有歧義，每個 agent 知道自己做什麼
✅ **顯式依賴關係** — Task 系統會強制順序，不會亂指派
✅ **有 Timeout 保護** — 不會再卡 24 小時
✅ **同步點明確** — Architect 在關鍵時刻把關
✅ **失敗恢復流程清晰** — 知道誰決定返工、怎麼返工
🔴 **改進: 編碼依賴** — Task.blockedBy/blocks 已填寫
🔴 **改進: Prompt 清晰** — 停止條件和禁止清單已明確
🔴 **改進: Session 生命週期** — Heartbeat 和孤立 agent 檢測已實現
🔴 **改進: Timeout 實現** — Team-lead 監控邏輯已編碼
🔴 **改進: Agent 協調** — 消息格式和流程已標準化
🔴 **改進: 交付物格式** — JSON Schema 已定義

---

## 【與 v2 的區別總結】

| 方面 | v2 | v3 (改進) |
|------|-----|----------|
| **結構** | Sequential Phase（正確） | 保留 Sequential Phase ✅ |
| **依賴編碼** | 文字描述（隱式） | 🔴 Task.blockedBy/blocks（顯式） |
| **Prompt 清晰度** | 基本但不完整 | 🔴 添加停止條件和禁止清單 |
| **Timeout 實現** | 說了但沒細節 | 🔴 30 分鐘檢查一次 + 自動解鎖 |
| **Session 監視** | 無 | 🟢 Heartbeat + 孤立 agent 檢測 |
| **Agent 協調** | 隱式 | 🔴 標準化消息格式和流程 |
| **交付物格式** | 粗略的 JSON | 🔴 詳細的 JSON Schema 定義 |
| **錯誤恢復** | 清晰流程 | 保留 + 🔴 添加 Session 恢復 |

---

## 【改進版 v3 的啟動檢查清單】

見 **QUALITY_AUDIT_IMPLEMENTATION_CHECKLIST.md**（詳細版）

快速檢查：
- [ ] Task JSON 中所有 `blockedBy` 和 `blocks` 已填寫
- [ ] 所有 Agent Prompts 都有「停止條件」和「禁止清單」章節
- [ ] Team-lead Prompt 中有「Session 監視」和「Timeout 檢查」邏輯
- [ ] 所有交付物都是有效的 JSON（包含 JSON Schema）
- [ ] 沒有循環依賴（檢查：Task #1 → #2, #3 → #4）

---

**文檔狀態**: 改進完成，可直接實施
**下一步**:
1. 查看 QUALITY_AUDIT_AGENT_PROMPTS.md 以了解完整的 Prompts
2. 查看 QUALITY_AUDIT_IMPLEMENTATION_CHECKLIST.md 以了解啟動前的檢查清單
3. 啟動新的 Agent Team：`TeamCreate team_name=simpleec-oms-quality-audit-v3`
