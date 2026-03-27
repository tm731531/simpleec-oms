# Agent Team 設計與 Prompt 編寫指南

**Document Status**: Living Document (持續更新)
**Last Updated**: 2026-03-17
**Author**: Claude Code + Tom
**Purpose**: 從 SimpleEC OMS 多次失敗的 Agent Team 經驗中提煉最佳實踐

---

## 序言：為什麼我們一直失敗

從 2026-03-03 到 2026-03-17，我們嘗試啟動過至少 **4 個 Agent Team**，每一次都卡住 24+ 小時：

1. **simpleec-oms-audit** (3/3) — 任務分配錯亂，Agent 互相等待
2. **simpleec-oms-bidirectional-audit** (3/10) — 複雜的雙向驗證設計，Agent 不知道什麼時候應該停止
3. **simpleec-oms-quality-audit** (3/16) — Task 被分配給錯誤的 Agent，導致 3 個 Agent 空轉 24 小時

**根本原因不在代碼，而在於**：
- ❌ 設計太複雜，隱式依賴沒有編碼
- ❌ Prompt 寫得不清楚，Agent 不知道什麼時候應該停止或等待
- ❌ 沒有 Timeout 機制，導致無限期等待
- ❌ 任務分配邏輯複雜，容易出錯
- ❌ Agent 沒有明確的"停止條件"，導致亂工作或空轉

本文將詳細解析這些問題，並提供可複製的最佳實踐。

---

## 第一部分：為什麼 Agent Team 會卡住？

### 原因 1：複雜的隱式依賴

**❌ 壞設計示例**：

```markdown
# Provider Chain + Consumer Chain 雙向驗證（複雜且失敗）

【開發面向驗證】(Provider Chain)
阶段 1：Channel job → DBA
  - Task #1: Architect 審查平台 API 結構
  - Task #2: DBA 審查 Schema 是否能承載這些數據
  → 架構師確認：API 結構 ✓ Schema 設計 ✓ 匹配

阶段 2：DBA → Backend API
  - Task #2: DBA 提供數據保證
  - Task #3: Backend 確認能否正確讀寫
  → 架構師跟PM 確認：數據流 ✓ 業務邏輯 ✓ 匹配

【測試面向驗證】(Consumer Chain)
阶段 4：Frontend → Backend → DBA → Channel job
  - Task #4, #5: 前端真實調用測試
  - Task #3: 後端數據處理驗證
  - Task #2: 數據庫返回值正確性
  - Task #1: 平台源數據一致性
```

**問題**：
- 有 4 個同步點，但都是**文字描述**，沒有編碼到 Task 系統
- Task 之間的依賴關係是**隱式的**（"Task #1 完成後才能開始 Task #2"，但沒有在 `blockedBy` 里標記）
- Agent 不知道應該**等待誰**或**被誰等待**
- 當任務分配出錯（比如 Task #2 被分給了錯誤的 Agent），整個鏈條斷裂，但沒有檢測機制

**後果**：
```
Task #1 (architect)  ← 應該分給 architect，被分給了 frontend-qa
Task #2 (backend-qa) ← 應該分給 backend-qa，被分給了 frontend-qa
Task #3 (frontend-qa) ← 分對了
Task #4 (整合)      ← 應該等待 #2 和 #3，但 #2 沒人做

→ architect 和 backend-qa 沒有被分配任務，空轉 24+ 小時，持續消耗內存
```

---

### 原因 2：Prompt 寫得不清楚 → Agent 不知道什麼時候該停止

**❌ 壞 Prompt 示例**：

```markdown
You are the ARCHITECT on the simpleec-oms-quality-audit team.

Your task: Review system architecture & API contract consistency (Task #1)

**Required Actions:**
1. Read these documents thoroughly:
   - /docs/1-ARCHITECTURE/DESIGN_v2.md
   - /docs/3-EVENT-FLOW/CORE_CONTRACTS.md
   - /docs/2-API/ADMIN_API.md
   - /docs/2-API/USER_API.md

2. Check actual code against documented architecture:
   - Verify 11 modules and their dependency boundaries
   - Validate Channel adapter pattern usage
   - Confirm message header/body consistency across code

3. Generate audit report with:
   - Executive summary (1-2 paragraphs)
   - Critical (P1) issues list
   - Important (P2) issues list
   - Alignment percentage score
   - Recommendations

4. Mark task #1 as completed with findings

【問題在這裡】
- 沒有明確說 "Task #1 完成後，你應該做什麼"
- 沒有說 "你應該等待其他 Agent"
- 沒有說 "如果其他 Task 還沒完成，不要開始 Task #4"
- Agent 完成後，會自動尋找下一個工作，或者開始重新讀文件（導致內存消耗）
- 沒有 Timeout 機制，所以不知道什麼時候可以停止等待
```

**看起來合理，但實際上**：
- Architect 完成 Task #1 後，發現沒有 Task #2 和 #3 的結果（因為被分配錯了）
- Architect 開始懷疑，重新讀一遍所有文件
- 14 小時後，Architect 還在讀文件或重新分析
- 內存從 380MB 漲到 440MB（文件緩存沒有被清理）
- 最後 OOM → 系統當機

---

### 原因 3：沒有 Timeout 機制

**❌ 舊設計**：
```
Agent Task 超時限制：無
→ 可以無限期等待
→ 可以無限期工作（讀文件、重新分析）
→ 內存持續增長，最終 OOM
```

**✅ 新設計**：
```
Agent Task 超時限制：2 小時
→ 2 小時後，自動標記 timeout
→ 解鎖下一個任務或停止
→ 即使出錯，也不會無限期卡住
```

---

### 原因 4：Agent 之間沒有同步點

**❌ 舊設計**：
```
Task #1 (Architect)  ← 工作中...
Task #2 (Backend-QA) ← 工作中... 但應該等待 Task #1？
Task #3 (Frontend-QA) ← 工作中... 但應該等待 Task #1？
Task #4 (Architect)  ← 應該等待 Task #2 和 #3

問題：沒人告訴他們應該等待誰
```

**✅ 新設計**：
```
Task #1 (Architect)  → 完成
  ↓ (自動解鎖)
Task #2 (Backend-QA) → 完成
Task #3 (Frontend-QA) → 完成
  ↓ (同時完成時自動解鎖)
Task #4 (Architect)  → 完成

原理：Task.blockedBy = [1, 2] 明確表示 "我要等 Task #1, #2"
```

---

## 第二部分：好的 Agent Team 設計原則

### 原則 1：Show, Don't Tell（編碼依賴，不要寫文字）

**❌ 壞做法**：
```json
{
  "id": 1,
  "subject": "Architecture Review",
  "description": "After completing this task, Task #2 can begin...",
  "status": "pending"
}
```

**✅ 好做法**：
```json
{
  "id": 1,
  "subject": "Architecture Review",
  "description": "...",
  "status": "pending",
  "blocks": [2, 3],        // ← 明確編碼
  "blockedBy": []          // ← 我不等任何人
}

{
  "id": 2,
  "subject": "Backend Data Layer Review",
  "description": "...",
  "status": "pending",
  "blocks": [4],
  "blockedBy": [1]         // ← 我等 Task #1
}
```

**為什麼？**
- Task 系統會**自動強制**依賴順序，不會分配錯誤
- 不依賴人工判斷（"他們應該等待彼此"）
- 系統可以自動檢測循環依賴或孤立任務

---

### 原則 2：一個 Agent 做一件事

**❌ 壞做法**：
```markdown
Task #2 - Backend-QA: Schema、Kafka、Handler 完整性驗證

包含 3 個不同的東西：
1. 驗證 PostgreSQL Schema（19 張表）
2. 驗證 Kafka Topic 配置（16 個 topics）
3. 驗證 Handler Registry（所有 TaskType）
```

**為什麼壞？**
- Agent 容易分心，或者做了一半
- 難以判斷什麼時候"完成"（Schema 驗完了，但 Kafka 還沒？）
- 失敗時難以明確原因（是 Schema 問題還是 Kafka 問題？）

**✅ 好做法**：
```markdown
Task #2 - Backend-QA: Data Layer Verification

一個清晰的目標：驗證數據層能否支持 API 承諾

包含 3 個驗證點（都是同一件事的一部分）：
1. Verify PostgreSQL Schema integrity (19 tables)
2. Confirm Kafka Topic configuration (16 topics, retention policies)
3. Validate Handler Registry completeness (all TaskTypes mapped)

交付物是一個 JSON 對象，包含所有驗證結果
```

**為什麼好？**
- 目標清晰：只驗證"數據層能否支持 API"
- 交付物明確：一個 JSON 對象
- 失敗時容易定位：JSON 裡標記了具體哪個部分失敗

---

### 原則 3：明確的交付物結構

**❌ 壞做法**：
```markdown
**Output**: Generate audit report with:
  - Executive summary (1-2 paragraphs)
  - Critical (P1) issues list
  - Important (P2) issues list
  - Alignment percentage score
  - Recommendations
```

**問題**：
- "Executive summary" 怎麼算"完成"？100 字？1000 字？
- "Critical issues list" 應該有多少個？
- 下一個 Agent 怎麼驗證這份報告是否合格？

**✅ 好做法**：
```json
{
  "status": "completed",
  "findings": {
    "api_schema_alignment": 85,
    "critical_issues": [
      {"issue": "...", "impact": "HIGH", "location": "...", "fix_effort_hours": 4}
    ],
    "p2_issues": [...],
    "recommendations": ["..."],
    "ready_for_next_phase": true
  }
}
```

**為什麼好？**
- 結構化：下一個 Agent 可以用 JSON parser 讀取
- 可驗證：`ready_for_next_phase: true` 明確表示"可以進入下一階段"
- 可追蹤：每個 Issue 都有 location 和 fix_effort，便於後續統計

---

### 原則 4：Prompt 要明確定義"停止條件"

**❌ 壞 Prompt**：
```markdown
You are the ARCHITECT on the simpleec-oms-quality-audit team.

Your task: Review system architecture & API contract consistency (Task #1)

**Required Actions:**
1. Read these documents...
2. Check actual code...
3. Generate audit report...
4. Mark task #1 as completed with findings
```

**問題**：
- Agent 完成後，他不知道應該做什麼
- 是否應該主動聯繫其他 Agent？
- 是否應該重新檢查自己的工作？
- 內存會持續增長（因為 Agent 一直在運行）

**✅ 好 Prompt**：
```markdown
You are the ARCHITECT on the simpleec-oms-quality-audit-v2 team.

**Your Responsibilities:**
1. Task #1 (Phase 1): Review architecture & API contract consistency
2. Task #4 (Phase 4): Integrate findings from all phases

**Timeline:**
- Phase 1: 0-2 小時（Task #1）
- Phase 4: 4-6 小時（Task #4，在 Task #2 & #3 完成後）

**If you complete Phase 1 and need to wait for Phase 4:**
- You are DONE with Phase 1
- Do NOT start Phase 4 until explicitly notified by team-lead
- Do NOT contact backend-qa or frontend-qa
- Do NOT re-read files or generate extra reports
- Simply wait for team-lead notification

**How to know when to start Phase 4:**
- team-lead will send you an explicit message: "Phase 4 begins now"
- Only then should you read Task #4 requirements

**Success Criteria for Task #1:**
- JSON findings object with at least 3 critical issues identified (or 0 if none found)
- Alignment score between 0-100
- ready_for_next_phase: true or false
- Commit findings to a clear, parseable JSON format
```

**為什麼好？**
- Phase 1 完成後，Architect 知道**不應該繼續工作**
- 明確的通知機制（等待 team-lead 的消息）
- 明確的停止條件（JSON 交付物生成後，停止工作）
- 防止 Agent 自作聰明繼續分析或讀文件

---

### 原則 5：Timeout 是必需的

**❌ 舊設計**：
```
No timeout → Agent 可以無限期運行
→ 如果卡住，沒人會發現（直到內存爆炸）
```

**✅ 新設計**：
```
2 小時 timeout：
- 時間到了，自動標記 timeout
- 自動解鎖下一個 Task（即使當前 Task 沒完成）
- 自動通知 team-lead
- 防止無限期等待
```

---

## 第三部分：Prompt 編寫終極檢查清單

當你為 Agent Team 寫 Prompt 時，檢查以下項目：

### A. 責任清晰度（Clarity of Responsibility）

- [ ] **一個 Agent，一件事**
  ```
  ✓ "Verify data layer integrity"
  ✗ "Verify schema, kafka, handler, api, and business logic"
  ```

- [ ] **目標可測**
  ```
  ✓ "Generate JSON with schema_integrity score and critical_issues list"
  ✗ "Verify schema completeness"
  ```

- [ ] **交付物明確**
  ```
  ✓ Structured JSON with specific fields
  ✗ "Report with findings"
  ```

### B. 停止條件明確（Clear Stop Conditions）

- [ ] **Prompt 明確說什麼時候應該停止**
  ```
  ✓ "After Task #1 is complete, do NOT continue.
      Wait for team-lead notification for Task #2."

  ✗ "Mark task as completed"（模糊）
  ```

- [ ] **沒有"循環工作"的空間**
  ```
  ✓ "After generating findings JSON, stop. Do not re-read files."

  ✗ "Verify everything is correct"（容易導致無限循環）
  ```

- [ ] **明確的等待機制**
  ```
  ✓ "Wait for team-lead message: 'Phase 2 begins'"

  ✗ "Wait until other tasks are done"（模糊，Agent 怎麼知道什麼時候完成？）
  ```

### C. 依賴關係編碼（Dependency Encoding）

- [ ] **Task.blockedBy 明確填寫**
  ```json
  ✓ "blockedBy": [1]  // 我等 Task #1
  ✗ "blockedBy": []   // Task 之間無依賴（隱式寫在文字裡）
  ```

- [ ] **Task.blocks 明確填寫**
  ```json
  ✓ "blocks": [3, 4]  // 我阻擋 Task #3 和 #4
  ✗ "blocks": []      // （隱式在文字裡）
  ```

- [ ] **沒有循環依賴**
  ```
  Task #1 blocks Task #2
  Task #2 blocks Task #3
  Task #3 blocks Task #1  ← ❌ 循環！
  ```

### D. 超時保護（Timeout Protection）

- [ ] **Prompt 中明確提到超時時間**
  ```
  ✓ "You have 2 hours to complete Task #1"

  ✗ "Complete Task #1"（沒有時間限制）
  ```

- [ ] **告訴 Agent 超時時會發生什麼**
  ```
  ✓ "If 2 hours pass, your task will be marked timeout
     and next phase will begin regardless."

  ✗ 沒有說（Agent 不知道後果是什麼）
  ```

- [ ] **Timeout 不應該導致錯誤，只是解鎖**
  ```
  ✓ timeout 後自動繼續下一階段
  ✗ timeout 後拋出錯誤並停止整個流程
  ```

### E. Agent 之間的協調（Inter-Agent Coordination）

- [ ] **明確說是否應該主動聯繫其他 Agent**
  ```
  ✓ "Do NOT contact backend-qa or frontend-qa"

  ✗ 沒說（Agent 可能自作聰明去聯繫）
  ```

- [ ] **明確說如何接收通知**
  ```
  ✓ "You will receive explicit notification from team-lead
     when Phase 2 begins. Only then should you proceed."

  ✗ "Proceed when other agents are done"（怎麼知道別人做完了？）
  ```

- [ ] **明確說何時應該報告問題**
  ```
  ✓ "If you find CRITICAL issues, report them in your findings JSON.
     Do NOT try to fix them yourself."

  ✗ 沒說
  ```

---

## 第四部分：Prompt 好與壞的完整對比

### 例子 1：Architecture Review Task

**❌ 壞 Prompt（導致失敗）**：

```markdown
You are the ARCHITECT on the simpleec-oms-quality-audit team.

Your task: Review system architecture & API contract consistency (Task #1)

**Required Actions:**
1. Read these documents thoroughly:
   - /docs/1-ARCHITECTURE/DESIGN_v2.md
   - /docs/2-API/ADMIN_API.md
   - /docs/2-API/USER_API.md
   - /docs/3-EVENT-FLOW/CORE_CONTRACTS.md
   - /docs/4-SCHEMA/SCHEMA.md

2. Check actual code against documented architecture:
   - Verify 11 modules and their dependency boundaries
   - Validate Channel adapter pattern usage
   - Confirm message header/body consistency across code

3. Generate audit report with:
   - Executive summary (1-2 paragraphs)
   - Critical (P1) issues list
   - Important (P2) issues list
   - Alignment percentage score
   - Recommendations

4. Mark task #1 as completed with findings

**Constraints:**
- Focus on architecture alignment, not code style
- Flag inconsistencies between docs and code
- Be specific about what's wrong and where

**Success:** Clear, actionable report on architectural health
```

**為什麼失敗**：
- ❌ 沒有明確說"完成後應該做什麼"
- ❌ 沒有說"你應該等待誰"或"誰會等待你"
- ❌ 沒有 Timeout 時間
- ❌ 沒有停止條件（Agent 可以一直讀文件）
- ❌ 交付物格式模糊（"Executive summary" 有多長？）

**後果**：
```
1. Architect 完成 Task #1（花了 30 分鐘）
2. 發現沒有 Task #2 和 #3 的結果（因為被分配給錯誤的 Agent）
3. 不知道應該等待還是繼續工作
4. 開始懷疑自己的分析，重新讀文件
5. 持續讀文件和重新分析 14+ 小時
6. 內存從 380MB 漲到 440MB
7. 最後 OOM，系統當機
```

---

**✅ 好 Prompt（成功設計）**：

```markdown
You are the ARCHITECT on the simpleec-oms-quality-audit-v2 team.

**Your Responsibilities:**
1. Task #1 (Phase 1): Review architecture & API contract consistency
2. Task #4 (Phase 4): Integrate findings from all phases and deliver final report

**Timeline:**
- Phase 1: 0-2 小時（Task #1）
  - Read documentation
  - Review architecture
  - Verify API-Schema alignment
  - Generate JSON findings
  - Mark task completed

- Waiting Period: 2-4 小時
  - Do NOT start Phase 4 until notified
  - Do NOT contact other agents
  - Do NOT re-read files or re-analyze
  - Simply wait in standby mode

- Phase 4: 4-6 小時（Task #4，在 Task #2 & #3 完成後）
  - Receive notifications from team-lead
  - Read Task #2 and #3 findings
  - Integrate all three findings
  - Generate final system health report

**Phase 1 Execution (Task #1):**

**Input:**
- /docs/1-ARCHITECTURE/DESIGN_v2.md
- /docs/2-API/ADMIN_API.md, USER_API.md
- /docs/3-EVENT-FLOW/CORE_CONTRACTS.md
- /docs/4-SCHEMA/SCHEMA.md

**Your Job:**
1. Verify 11 modules' boundary clarity
2. Check 16 Kafka Topics' Header/Body contracts
3. Confirm API fields ↔ Schema alignment
4. Identify any architecture-level inconsistencies

**Deliverable (Structured JSON):**
```json
{
  "task_id": 1,
  "status": "completed",
  "findings": {
    "api_schema_alignment_score": 85,
    "critical_issues": [
      {
        "issue": "Field type mismatch in order_status",
        "impact": "HIGH",
        "location": "API_ADMIN.md:line 45 vs SCHEMA.md:table order",
        "fix_effort_hours": 2,
        "recommendation": "Align schema to accept string values as per API contract"
      }
    ],
    "p2_issues": [
      {"issue": "...", "impact": "MEDIUM", ...}
    ],
    "p3_recommendations": ["..."],
    "ready_for_backend_qa": true
  }
}
```

**After Task #1 Completion:**

⚠️ **CRITICAL: Stop here. Do not proceed.**

- ✓ Mark the findings JSON in the response
- ✓ You are DONE with Phase 1
- ✗ Do NOT contact backend-qa or frontend-qa
- ✗ Do NOT re-read files or continue analyzing
- ✗ Do NOT try to start Task #4 on your own
- ✗ Do NOT wait indefinitely (you have 2 hours)

**How Phase 4 will start:**
- team-lead will send you explicit message: "Phase 4 begins. Read your next task."
- Only at that moment should you proceed to Phase 4
- You will receive findings from Task #2 and #3
- Your job is to integrate and verify they are aligned

**Phase 4 Execution (Task #4):**

[Details for Task #4 will be sent when Phase 4 begins]

**Success Criteria:**
- Task #1: JSON findings with proper structure
- Task #4: Final system health report with alignment scores
```

**為什麼成功**：
- ✅ 明確的時間表（0-2h Phase 1, 2-4h 等待, 4-6h Phase 4）
- ✅ 明確的停止條件（"完成後停止，不要做任何其他事"）
- ✅ 明確的交付物（結構化 JSON）
- ✅ 明確的等待機制（team-lead 會告訴你什麼時候開始 Phase 4）
- ✅ 明確的禁止行為（❌ 列出了不應該做的事）

**後果**：
```
1. Architect 完成 Task #1（花了 30 分鐘）
2. 知道自己應該停止（Prompt 明確說了）
3. 進入等待模式（不讀文件，不重新分析）
4. 2 小時後，如果沒有 Task #4 通知，自動 timeout
5. 內存穩定在 380MB（沒有持續增長）
6. 系統正常運行
```

---

## 第五部分：常見錯誤與修正

### 錯誤 1：Prompt 寫得太長和太複雜

**❌ 壞**：
```
一個 2000 字的 Prompt，充滿細節
Agent 讀了半小時都讀不完
導致 Agent 開始自作聰明填補空白
```

**✅ 好**：
```
一個 500 字的清晰 Prompt
- 責任：1-2 句
- 時間表：清晰的時間點
- 輸入和輸出：具體的文件和 JSON 格式
- 停止條件：明確的停止點
```

**修正**：
- 用清晰的結構（Markdown headers）
- 用列表而不是段落
- 用例子而不是抽象描述
- 用"禁止行為"而不是"允許行為"

---

### 錯誤 2：隱式假設 Agent 會自己推斷

**❌ 壞**：
```
"After Task #1, you will know when to start Task #2"
（Agent 怎麼知道？沒人告訴他！）
```

**✅ 好**：
```
"After Task #1, wait for team-lead message: 'Task #2 begins'
Only then should you proceed."
```

---

### 錯誤 3：交付物格式模糊

**❌ 壞**：
```
"Generate a report with findings"
```

**✅ 好**：
```
Generate JSON with this exact structure:
{
  "status": "completed",
  "findings": {
    "score": <number 0-100>,
    "critical_issues": [
      {"issue": <string>, "impact": <string>, "fix_effort_hours": <number>}
    ]
  }
}
```

---

### 錯誤 4：沒有明確的超時時間

**❌ 壞**：
```
沒有提及超時
Agent 可能無限期運行
```

**✅ 好**：
```
**Timeout: 2 hours**
- If 2 hours pass, your task is automatically marked timeout
- Next phase will proceed regardless
- You will be notified when your timeout occurs
```

---

## 第六部分：Agent Team 設計檢查清單

啟動新的 Agent Team 之前，檢查以下項目：

### Pre-Launch Checklist

**系統狀態**：
- [ ] 內存充足（>8GB available）
- [ ] 沒有舊的 Agent Team 在運行
- [ ] Task 目錄是空的（`~/.claude/tasks/` 無文件）

**Task 設計**：
- [ ] 每個 Task 有明確的 `id`, `subject`, `description`
- [ ] 每個 Task 的 `blockedBy` 和 `blocks` 已填寫（沒有循環依賴）
- [ ] 沒有孤立的 Task（所有 Task 都有路徑從 start 到 end）
- [ ] 每個 Task 有 2 小時的隱式 timeout

**Prompt 設計**：
- [ ] 每個 Agent 的 Prompt 有明確的責任（一件事）
- [ ] 每個 Prompt 有明確的停止條件
- [ ] 每個 Prompt 有具體的交付物格式（JSON）
- [ ] 每個 Prompt 有明確的等待機制（如何知道下一個任務何時開始）
- [ ] 每個 Prompt 禁止了不應該做的事情（❌ 列表）

**交付物定義**：
- [ ] 所有交付物都是結構化的（JSON）
- [ ] 每個交付物都有 `status` 字段
- [ ] 下一個 Agent 能解析上一個 Agent 的交付物
- [ ] 交付物不應該是自由文本，應該是結構化數據

**同步機制**：
- [ ] 有明確的 "team-lead" 角色負責協調
- [ ] 有明確的通知機制（如何告訴 Agent"開始下一個 Task"）
- [ ] 有明確的失敗恢復流程（如果發現 CRITICAL 問題）
- [ ] 沒有 Agent 之間的直接通信（都通過 team-lead）

### Launch Checklist

**監控**：
- [ ] 每 30 分鐘檢查一次內存使用
- [ ] 監控是否有 Agent 卡住（超過預期時間）
- [ ] 準備好 2 小時後手動檢查 timeout 機制是否生效

**信號**：
- [ ] 知道什麼樣的行為表示"卡住"
  ```
  ✓ Agent 在讀同一個文件超過 1 小時
  ✓ Agent 的內存持續增長 10% 以上
  ✓ Agent 沒有進展 30 分鐘以上
  ```

- [ ] 準備好的應急措施
  ```
  ✓ 能在 2 分鐘內關掉所有 Agent
  ✓ 知道怎麼清理內存
  ✓ 知道怎麼回滾到之前的狀態
  ```

---

## 第七部分：迭代和改進

**本文將根據實驗結果進行迭代**

每當我們啟動新的 Agent Team 時：

1. **記錄過程**
   - 啟動時間、Agent 行為、完成時間、內存使用
   - 任何卡住或異常情況

2. **在本文中更新**
   - 如果發現新的問題，添加到"常見錯誤"
   - 如果發現更好的 Prompt 寫法，更新示例
   - 如果 Timeout 時間不合適，調整建議值

3. **版本控制**
   - 每次大的更新，提升版本號（v1.0 → v1.1 → v2.0）
   - 在文件頂部記錄更新日期和內容

---

## 總結

**從失敗中學到的核心真理：**

1. **設計要簡單，依賴要明確** — 不要用複雜的 Provider Chain，用簡單的 Sequential Phase
2. **編碼依賴，不要寫文字** — `blockedBy` 和 `blocks` 必須填寫，隱式依賴是毒藥
3. **Prompt 要明確停止條件** — Agent 必須知道什麼時候應該停止
4. **Timeout 是必需的保險** — 沒有 timeout，Agent 可以無限期卡住
5. **交付物要結構化** — JSON 而不是自由文本，下一個 Agent 能解析
6. **有一個協調者（team-lead）** — 不要讓 Agent 之間直接通信

**下一步**：
- [ ] 用 v2 設計啟動 Quality Audit Team
- [ ] 記錄過程和結果
- [ ] 根據結果更新本文
- [ ] 建立標準範本（Task 和 Prompt 的 template）

---

**Last Updated**: 2026-03-17
**Next Review**: 2026-03-24 或完成一次 Agent Team 後
