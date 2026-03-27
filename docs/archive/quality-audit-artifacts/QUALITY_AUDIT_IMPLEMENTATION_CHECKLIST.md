# SimpleEC OMS Quality Audit v3 - 實施檢查清單

**文檔狀態**: 完整檢查清單
**生成日期**: 2026-03-17
**適用版本**: QUALITY_AUDIT_PLAN_v3
**用途**: 啟動改進版 Agent Team 前的準備檢查

---

## 第一部分：系統環境檢查

在啟動新 Agent Team 之前，確認系統狀態。

### A. 內存與資源

- [ ] **內存充足**
  - 執行：`free -h` 或 `docker stats`
  - 要求：至少 8GB 可用內存（推薦 10GB+）
  - 原因：4 個 Agents（3 個 Opus @ ~1GB, 1 個 Haiku @ ~400MB），加上 buffer

- [ ] **沒有舊的 Agent Team 在運行**
  - 執行：`ps aux | grep -i agent` 或查看 Claude 活躍會話
  - 要求：沒有之前失敗的 Agent Team 仍佔用資源
  - 清理：使用 `TeamDelete` 刪除舊 team，或手動殺死進程

- [ ] **Task 目錄是乾淨的**
  - 執行：`ls -la ~/.claude/tasks/`
  - 要求：沒有舊的 `simpleec-oms-*` 目錄
  - 清理：`rm -rf ~/.claude/tasks/simpleec-oms-*`

### B. 文檔準備

- [ ] **所有必需的文檔都存在**
  ```
  /docs/1-ARCHITECTURE/DESIGN_v2.md
  /docs/2-API/ADMIN_API.md
  /docs/2-API/USER_API.md
  /docs/3-EVENT-FLOW/CORE_CONTRACTS.md
  /docs/4-SCHEMA/SCHEMA.md
  /docs/5-KAFKA/KAFKA_QUICKSTART.md
  /docs/3-EVENT-FLOW/HANDLER_REGISTRY.md
  /docs/5-FRONTEND/USER_APP_IMPLEMENTATION.md
  /docs/5-FRONTEND/ADMIN_APP_IMPLEMENTATION.md
  ```
  - 執行：`for file in ...; do [ -f $file ] && echo OK || echo MISSING; done`
  - 要求：所有文檔都存在，大小 > 0
  - 修復：如果缺失，補全或標記為 "TBD"

- [ ] **文檔內容一致性**
  - 檢查：API 文檔中的欄位名稱是否匹配 Schema
  - 檢查：Kafka Topics 清單是否完整（應該是 16 個）
  - 檢查：Handler Registry 是否覆蓋所有 TaskTypes
  - 修復：如果發現不一致，手動修正或記錄為 Known Issue（在交付物中報告）

### C. 代碼庫狀態

- [ ] **代碼能編譯**
  ```bash
  cd /home/tom/ONEEC/simpleec-oms
  ./gradlew clean build -x test 2>&1 | tail -20
  ```
  - 要求：最後一行應該是 "BUILD SUCCESSFUL"
  - 修復：如果失敗，修復編譯錯誤或記錄為 Known Issue

- [ ] **Docker 容器能啟動**
  ```bash
  docker compose up -d --build 2>&1 | tail -10
  docker ps | grep simpleec | wc -l
  ```
  - 要求：至少 20 個容器在運行
  - 修復：如果失敗，檢查 docker logs 或手動啟動

---

## 第二部分：Task 設計檢查

改進版 v3 的核心是顯式的依賴編碼。驗證所有 4 個 Tasks。

### A. Task 結構完整性

- [ ] **Task #1 定義完整**
  ```json
  {
    "id": 1,
    "subject": "Architect: 平台 API 與 Schema 協議檢查",
    "description": "...",
    "status": "pending",
    "owner": "",
    "blockedBy": [],
    "blocks": [2, 3],
    "metadata": {
      "timeout_minutes": 120,
      "phase": "phase_1"
    }
  }
  ```
  - 檢查：`id`, `subject`, `blockedBy`, `blocks` 都存在
  - 檢查：`blockedBy: []` 且 `blocks: [2, 3]`
  - 檢查：`timeout_minutes: 120`

- [ ] **Task #2 定義完整**
  - 檢查：`blockedBy: [1]` 且 `blocks: [4]`
  - 檢查：`timeout_minutes: 120`

- [ ] **Task #3 定義完整**
  - 檢查：`blockedBy: [1]` 且 `blocks: [4]`
  - 檢查：`timeout_minutes: 120`

- [ ] **Task #4 定義完整**
  - 檢查：`blockedBy: [2, 3]` 且 `blocks: []`
  - 檢查：`timeout_minutes: 120`

### B. 依賴關係驗證

- [ ] **沒有循環依賴**
  - 檢查方式：按 blockedBy 排序
    ```
    Task #1: blocked_by=[] → 可以立即開始 ✓
    Task #2: blocked_by=[1] → 需要 Task #1 ✓
    Task #3: blocked_by=[1] → 需要 Task #1 ✓
    Task #4: blocked_by=[2, 3] → 需要 Task #2 和 #3 ✓
    ```
  - 檢查：沒有 Task X 等待 Task Y，而 Task Y 又等待 Task X

- [ ] **所有 Task 都可達**
  - 檢查：從 Task #1 開始，能否到達所有其他 Tasks
  - 檢查：是否有孤立的 Task（沒人阻擋，但也沒被阻擋）
  - 預期結果：Task #1 → Task #2/3 → Task #4（線性流程）

- [ ] **依賴清單正確**
  - 檢查：Task #2 的 `blockedBy: [1]` 對應 Task #1 的 `blocks: [2]`
  - 檢查：雙向一致性（不是只有 blockedBy 沒有 blocks）

### C. 交付物 JSON Schema

- [ ] **Task #1 交付物 Schema**
  ```json
  {
    "task_id": 1,
    "status": "completed",
    "findings": {
      "api_schema_alignment_score": <0-100>,
      "critical_issues": [
        {
          "issue": <string>,
          "impact": <"CRITICAL"|"HIGH"|"MEDIUM">,
          "location": <string>,
          "fix_effort_hours": <number>,
          "recommendation": <string>
        }
      ],
      "p2_issues": [...],
      "p3_recommendations": [...],
      "ready_for_backend_qa": <boolean>
    }
  }
  ```
  - 檢查：是否包含 `ready_for_backend_qa` 字段（v2 缺失）
  - 檢查：是否包含 `fix_effort_hours`（便於評估返工成本）

- [ ] **Task #2 交付物 Schema**
  - 檢查：包含 `ready_for_frontend_qa` 字段
  - 檢查：包含 `data_risk_assessment` 字段

- [ ] **Task #3 交付物 Schema**
  - 檢查：包含 `ready_for_integration_test` 字段

- [ ] **Task #4 交付物 Schema**
  - 檢查：包含 `overall_system_health` 字段（EXCELLENT/GOOD/REQUIRES_FIXES/CRITICAL）
  - 檢查：包含 `critical_items` 清單（而不是模糊的「發現」）

---

## 第三部分：Prompt 設計檢查

改進版 v3 的關鍵改進是 Prompt 的清晰性。每個 Prompt 都必須通過這些檢查。

### A. Architect Prompt（Task #1 & #4）

- [ ] **責任清晰度**
  - [ ] 明確說「你只做 Task #1 和 Task #4，不做其他事」
  - [ ] 明確說「Task #1 和 Task #4 之間有一個等待期」

- [ ] **停止條件清晰** (🔴 v2 缺失，v3 新增)
  - [ ] 包含「After Task #1 Completion:」段落
  - [ ] 包含 ❌ 不要做的事清單，至少 5 項：
    ```
    ❌ 不要主動檢查是否有新任務
    ❌ 不要重新讀文件或重新分析
    ❌ 不要嘗試自行開始 Task #4
    ❌ 不要主動聯繫其他 Agents
    ❌ 不要因為時間還充足就繼續工作
    ```

- [ ] **等待機制明確**
  - [ ] 說明「team-lead 會發送消息: 'Phase 4 begins'」
  - [ ] 說明「只有收到該消息，才應該進行 Task #4」
  - [ ] 說明「如果 2 小時後還沒收到消息，不用擔心，timeout 機制會處理」

- [ ] **交付物格式明確**
  - [ ] 包含完整的 JSON 範例（Task #1）
  - [ ] 包含 `ready_for_backend_qa` 和 `ready_for_frontend_qa` 字段的說明
  - [ ] 包含完整的 JSON 範例（Task #4）

- [ ] **Timeout 說明**
  - [ ] 明確說「你有 2 小時完成」
  - [ ] 明確說「如果超過 2 小時，系統會自動標記 timeout，不是你的錯」
  - [ ] 明確說「Timeout 後下一階段會自動開始」

### B. Backend-QA Prompt（Task #2）

- [ ] **責任清晰度**
  - [ ] 明確說「你只做 Task #2，驗證數據層」
  - [ ] 明確說「Task #2 完成後，不要做任何其他事」

- [ ] **停止條件清晰**
  - [ ] 包含「After Task #2 Completion:」段落
  - [ ] 包含 ❌ 不要做的事清單

- [ ] **前置條件明確**
  - [ ] 說明「Task #1 必須先完成」
  - [ ] 說明「如何讀取 Task #1 的交付物」

- [ ] **輸入清單明確**
  - [ ] 列出需要讀的文件（SCHEMA.md, KAFKA_QUICKSTART.md, HANDLER_REGISTRY.md）
  - [ ] 說明「實際檢查」的內容（19 張表、16 個 Topics、所有 Handlers）

- [ ] **交付物格式明確**
  - [ ] 包含完整的 JSON 範例
  - [ ] 包含 `ready_for_integration_test` 字段的說明

### C. Frontend-QA Prompt（Task #3）

- [ ] **責任清晰度**
  - [ ] 明確說「你只做 Task #3，驗證前端層」
  - [ ] 明確說「Task #3 完成後，不要做任何其他事」

- [ ] **停止條件清晰**
  - [ ] 包含「After Task #3 Completion:」段落
  - [ ] 包含 ❌ 不要做的事清單

- [ ] **交付物格式明確**
  - [ ] 包含完整的 JSON 範例
  - [ ] 包含 `ready_for_integration_test` 字段

### D. Team-Lead Prompt

- [ ] **責任清晰度**
  - [ ] 明確說「你是協調者，不是工作者」
  - [ ] 明確說「你的工作是監控進度、強制 timeout、調解衝突」

- [ ] **Session 監視邏輯** (🟢 v3 新增)
  - [ ] 包含「Session 生命週期監視」章節
  - [ ] 說明「每 30 秒檢查一次」的內容：
    ```
    - 所有 agents 是否還在運行
    - 是否有 agents 超過 2 小時沒有進展
    - 是否有 agents 顯示 "orphaned" 狀態
    ```
  - [ ] 說明「如果發現孤立 agent」的行為：
    ```
    - 標記 task 為 "FAILED_PARENT_DIED"
    - 標記依賴的 tasks 為 "BLOCKED_BY_PARENT_FAILURE"
    - 發送通知給用戶
    ```

- [ ] **Timeout 監控邏輯** (🔴 v2 有概念，v3 實現細節明確)
  - [ ] 包含「Timeout 檢查」邏輯
  - [ ] 說明「每 30 分鐘檢查一次」的內容
  - [ ] 說明「如果 Task #X 超過 2 小時」的行為：
    ```
    - TaskUpdate(id=X, status="timeout")
    - TaskUpdate(id=Y, blockedBy=[])  // 自動解鎖
    ```

- [ ] **Agent 協調流程** (🔴 v3 改進：標準化消息格式)
  - [ ] 包含「Agent 協調」章節
  - [ ] 說明「等待所有 Task 完成後」的通知邏輯
  - [ ] 說明「通知格式」應該是：
    ```
    SendMessage(to="architect", message="Phase 4 begins now. Read Task #4 requirements.")
    ```
  - [ ] 說明「不應該主動聯繫其他 agents」

- [ ] **通知機制標準化**
  - [ ] 定義標準的通知格式（例如「Phase X begins」）
  - [ ] 說明「所有通知都通過 SendMessage」，不用其他方式

---

## 第四部分：啟動前的最終驗證

### A. Task 依賴圖驗證

```
繪製依賴圖：

Task #1 (blocked_by=[])
   ↓
Task #2 (blocked_by=[1])  AND  Task #3 (blocked_by=[1])
   ↓                               ↓
Task #4 (blocked_by=[2,3])
```

- [ ] 依賴圖與上述一致
- [ ] 沒有迴圈
- [ ] 所有 Tasks 都可達

### B. 交付物流程驗證

```
Task #1 交付物 JSON (findings)
        ↓
Task #2 讀取 Task #1 交付物
Task #3 讀取 Task #1 交付物
        ↓
Task #2 交付物 JSON (data_risk_assessment)
Task #3 交付物 JSON (ui_integration_correctness)
        ↓
Task #4 讀取 Task #2 和 #3 交付物
        ↓
Task #4 交付物 JSON (overall_system_health)
```

- [ ] 每個 Agent 知道如何讀前一個 Agent 的交付物
- [ ] JSON 格式一致（都用 `findings` 字段）
- [ ] JSON 字段名稱一致（沒有 Task #1 用 `api_schema_alignment_score`，Task #2 用 `alignment` 的情況）

### C. 人工 Dry-run

- [ ] **模擬 Task #1 的執行**
  - 閱讀 Architect Prompt（5 分鐘）
  - 檢查「停止條件」是否清晰（3 分鐘）
  - 檢查「交付物 JSON Schema」是否明確（3 分鐘）
  - 預期結果：一個清晰的 Agent，知道該做什麼，什麼時候停止

- [ ] **模擬 Task #2 的執行**
  - 閱讀 Backend-QA Prompt（5 分鐘）
  - 檢查「是否能讀取 Task #1 交付物」（3 分鐘）
  - 檢查「是否知道依賴於 Task #1」（2 分鐘）
  - 預期結果：清晰的依賴關係，知道不能在 Task #1 完成前開始

- [ ] **模擬 Team-Lead 的執行**
  - 閱讀 Team-Lead Prompt（10 分鐘）
  - 檢查「Session 監視邏輯」是否能執行（5 分鐘）
  - 檢查「Timeout 監控邏輯」是否有具體的檢查點（5 分鐘）
  - 預期結果：一個清晰的協調 Agent，知道定期檢查什麼

---

## 第五部分：啟動流程

### 啟動前最終檢查清單

- [ ] **內存** ≥ 8GB
- [ ] **舊 Agent Team 已清理**
- [ ] **文檔完整且一致**
- [ ] **代碼能編譯**
- [ ] **Docker 能啟動**
- [ ] **所有 Task JSON 的 blockedBy/blocks 已驗證**
- [ ] **所有 Agent Prompts 的「停止條件」已驗證**
- [ ] **Team-Lead Prompt 的「監視邏輯」已驗證**
- [ ] **交付物 JSON Schema 已確認**

### 啟動命令

```bash
# 1. 創建新的 Team
TeamCreate team_name=simpleec-oms-quality-audit-v3

# 2. 創建 4 個 Tasks（或導入預定義的 Tasks）
TaskCreate subject="Architect: 平台 API 與 Schema 協議檢查" ...
TaskCreate subject="Backend-QA: Schema、Kafka、Handler 完整性驗證" ...
TaskCreate subject="Frontend-QA: User App + Admin App 實現完整性驗證" ...
TaskCreate subject="Architect: 完整系統協調驗證" ...

# 3. 設置 Task 依賴
TaskUpdate taskId=1 addBlocks=[2, 3]
TaskUpdate taskId=2 addBlockedBy=[1] addBlocks=[4]
TaskUpdate taskId=3 addBlockedBy=[1] addBlocks=[4]
TaskUpdate taskId=4 addBlockedBy=[2, 3]

# 4. 生成 4 個 Agents（3 Opus + 1 Haiku）
Agent subagent_type=opus name=architect team_name=simpleec-oms-quality-audit-v3
Agent subagent_type=opus name=backend-qa team_name=simpleec-oms-quality-audit-v3
Agent subagent_type=opus name=frontend-qa team_name=simpleec-oms-quality-audit-v3
Agent subagent_type=haiku name=team-lead team_name=simpleec-oms-quality-audit-v3

# 5. 分配 Tasks
TaskUpdate taskId=1 owner=architect
TaskUpdate taskId=2 owner=backend-qa
TaskUpdate taskId=3 owner=frontend-qa
TaskUpdate taskId=4 owner=architect
# Team-lead 監控所有 Tasks，不分配特定 Task
```

### 啟動後的監控（首 30 分鐘）

- [ ] **監控 Task #1 的進度**
  - 第 5 分鐘：Architect 應該開始讀文件
  - 第 15 分鐘：Architect 應該開始分析
  - 第 25 分鐘：Architect 應該生成交付物 JSON

- [ ] **確認 Team-Lead 進入監視模式**
  - 第 10 分鐘：Team-Lead 應該發送「我已啟動」的消息
  - 第 10-30 分鐘：Team-Lead 應該定期檢查（不應該一直在分析）

- [ ] **監控內存使用**
  - 第 0 分鐘：基線（應該 < 500MB）
  - 第 10 分鐘：Architect 讀文件中（< 800MB）
  - 第 30 分鐘：Task #1 快完成（< 900MB）
  - 異常信號：內存超過 1GB（可能是 Agent 在無限循環）

---

## 第六部分：常見問題與解決方案

### Q1: Task #1 卡在「讀文件」階段超過 30 分鐘

**症狀**: Task #1 的 status 仍是 `in_progress`，沒有交付物

**診斷**:
- 檢查 Architect 的最後一條消息（時間戳）
- 檢查內存使用（是否超過 1GB）
- 檢查是否有「文件遺失」的錯誤消息

**解決**:
1. 等待 30 分鐘更久
2. 如果仍未完成，檢查文件是否真的存在
3. 如果 2 小時仍未完成，強制 timeout：`TaskUpdate taskId=1 status=timeout`
4. 自動解鎖 Task #2 和 #3：`TaskUpdate taskId=2 blockedBy=[]`

### Q2: Task #2 在等 Task #1，但 Task #1 已經完成

**症狀**: Task #1 已完成（status=completed），但 Task #2 仍是 `blocked`

**診斷**:
- 檢查 Task #2 的 `blockedBy` 字段
- 檢查是否有其他條件阻擋 Task #2（例如手動標記的 BLOCKED）

**解決**:
1. 自動解鎖：`TaskUpdate taskId=2 blockedBy=[]`
2. 檢查 Team-Lead 的監視邏輯是否有 bug

### Q3: 兩個 Agents 在同時工作（應該是順序）

**症狀**: Task #2 和 Task #3 同時在 `in_progress`

**診斷**:
- 這是正常的！Task #2 和 #3 都被 Task #1 阻擋，Task #1 完成後可以同時開始

**驗證**:
- 檢查時間軸：Task #2 和 #3 是否在 Task #1 完成後才開始
- 預期：Task #1 (0-2h) → Task #2+3 (2-4h 並行) → Task #4 (4-6h)

### Q4: 一個 Agent 停止運行（沒有完成 Task）

**症狀**: Agent 的最後一條消息是 1 小時前，Task 仍是 `in_progress`

**診斷**:
- 檢查是否是 Session 孤立（parent 死亡）
- 檢查是否是內存 OOM
- 檢查是否是人工中斷

**解決**:
1. 檢查系統日誌：`dmesg | tail -20`（查看 OOM）
2. 檢查 Claude 活躍會話（是否有 parent session）
3. 強制標記 Task 為失敗：`TaskUpdate taskId=X status=failed metadata={"reason": "agent_stopped"}`
4. 重新啟動 Team

### Q5: Timeout 時間太短/太長

**症狀**: 正常工作的 Task 被提前 timeout（或超時仍未完成）

**診斷**:
- 檢查 Task 定義中的 `timeout_minutes`（應該是 120）
- 檢查 Team-Lead Prompt 中的檢查間隔（應該是 30 分鐘）

**解決**:
- 如果太短，增加 timeout_minutes（但不超過 180）
- 如果太長，減少 timeout_minutes（但不少於 60）
- 調整後重新啟動 Team

---

## 第七部分：啟動後的監控指標

### 實時指標

- **Task 進度**: Task #1 何時完成，Task #2/3 何時解鎖，Task #4 何時開始
- **Agent 健康**: 每個 Agent 是否在定期發送消息
- **內存趨勢**: 內存使用是否穩定（不應該持續增長)
- **錯誤率**: 是否有任何 Task 因為 timeout 或異常而失敗

### 事後評估（完成後）

- [ ] **Task #1 評分**: `findings.api_schema_alignment_score` （預期 > 80）
- [ ] **Task #2 評分**: `findings.data_risk_assessment` （預期 LOW）
- [ ] **Task #3 評分**: `findings.api_integration_correctness` （預期 > 95）
- [ ] **Task #4 評分**: `findings.overall_system_health` （預期 GOOD 或 EXCELLENT）

### 改進點紀錄

啟動完成後，更新 AGENT_TEAM_DESIGN_LESSONS.md：
- 實際運行時間 vs 預期時間
- 發現的新問題（如果有）
- Prompt 的改進方向（如果有）
- Timeout 時間是否合適

---

## 附錄 A: JSON Schema 定義（供驗證用）

### Task #1 交付物 JSON Schema

```json
{
  "type": "object",
  "required": ["task_id", "status", "findings"],
  "properties": {
    "task_id": {"type": "integer"},
    "status": {"enum": ["completed", "failed"]},
    "findings": {
      "type": "object",
      "required": ["api_schema_alignment_score", "critical_issues", "ready_for_backend_qa"],
      "properties": {
        "api_schema_alignment_score": {"type": "integer", "minimum": 0, "maximum": 100},
        "critical_issues": {
          "type": "array",
          "items": {
            "type": "object",
            "required": ["issue", "impact", "location", "fix_effort_hours"],
            "properties": {
              "issue": {"type": "string"},
              "impact": {"enum": ["CRITICAL", "HIGH", "MEDIUM"]},
              "location": {"type": "string"},
              "fix_effort_hours": {"type": "number"},
              "recommendation": {"type": "string"}
            }
          }
        },
        "p2_issues": {"type": "array"},
        "p3_recommendations": {"type": "array"},
        "ready_for_backend_qa": {"type": "boolean"}
      }
    }
  }
}
```

### Task #4 交付物 JSON Schema

```json
{
  "type": "object",
  "required": ["task_id", "status", "overall_system_health"],
  "properties": {
    "task_id": {"type": "integer"},
    "status": {"enum": ["completed", "failed"]},
    "executive_summary": {"type": "string"},
    "architecture_alignment_score": {"type": "integer", "minimum": 0, "maximum": 100},
    "data_integrity_score": {"type": "integer", "minimum": 0, "maximum": 100},
    "ui_integration_score": {"type": "integer", "minimum": 0, "maximum": 100},
    "overall_system_health": {
      "enum": ["EXCELLENT", "GOOD", "REQUIRES_FIXES", "CRITICAL"]
    },
    "critical_items": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "item": {"type": "string"},
          "found_in_phase": {"enum": [1, 2, 3]},
          "impact": {"type": "string"},
          "resolution_plan": {"type": "string"}
        }
      }
    },
    "recommendations": {"type": "array"},
    "next_steps": {"type": "array"}
  }
}
```

---

## 附錄 B: Team 啟動範本 (可複製)

```bash
#!/bin/bash

# SimpleEC OMS Quality Audit v3 啟動腳本
# 用法：bash launch-quality-audit-v3.sh

set -e

TEAM_NAME="simpleec-oms-quality-audit-v3"

echo "🚀 Launching Agent Team: $TEAM_NAME"

# 1. 檢查系統
echo "✓ Checking system resources..."
FREE_MEMORY=$(free -h | grep Mem | awk '{print $7}' | sed 's/G//')
if (( $(echo "$FREE_MEMORY < 8" | bc -l) )); then
    echo "❌ ERROR: Not enough memory. Need 8GB, have ${FREE_MEMORY}GB"
    exit 1
fi

# 2. 清理舊 Team
echo "✓ Cleaning up old teams..."
rm -rf ~/.claude/teams/$TEAM_NAME ~/.claude/tasks/$TEAM_NAME 2>/dev/null || true

# 3. 創建新 Team（使用 Claude Code 的 TeamCreate）
echo "✓ Creating team..."
# 這裡需要通過 Claude Code 的 Tool 調用

echo "✅ Team $TEAM_NAME created successfully!"
echo ""
echo "Next steps:"
echo "1. Run TaskCreate for each of the 4 tasks"
echo "2. Set up task dependencies using TaskUpdate"
echo "3. Create 4 agents (3 Opus + 1 Haiku)"
echo "4. Assign tasks to agents"
echo ""
echo "見 QUALITY_AUDIT_IMPLEMENTATION_CHECKLIST.md 第五部分的「啟動命令」"
```

---

**文檔狀態**: 完整檢查清單，可直接使用
**更新日期**: 2026-03-17
**下一步**: 啟動 Agent Team 前務必完成此清單中的所有檢查
