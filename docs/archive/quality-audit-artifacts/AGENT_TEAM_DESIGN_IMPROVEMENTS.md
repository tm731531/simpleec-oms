# Agent Team 設計改進方案
## SimpleEC OMS 多次失敗根本原因分析與改進方案

**Document Status**: Professional Design Review
**Last Updated**: 2026-03-17
**Authors**: Claude Code + System Analysis
**Purpose**: 從 4 次 Agent Team 失敗中提煉根本原因，提供可行的改進方案與設計清單

---

## 第一部分：根本原因分析

### 失敗概況（2026-03-03 至 2026-03-17）

| 序號 | Team 名稱 | 啟動日期 | 失敗時間 | 失敗原因 | 症狀 |
|------|---------|---------|---------|---------|------|
| 1 | simpleec-oms-audit | 3/3 | 24h+ | 任務分配邏輯複雜 | Agent 互相等待死鎖 |
| 2 | simpleec-oms-bidirectional-audit | 3/10 | 24h+ | 雙向驗證設計模糊 | Agent 不知何時停止 |
| 3 | simpleec-oms-quality-audit | 3/16 | 24h+ | Task 分配給錯誤 Agent | 3 個 Agent 空轉，內存溢出 |
| 4 | simpleec-oms-quality-audit-v2 | 3/17 | 24h+ | Parent session 死亡 | 3 個 child agents 孤立 |

---

### 根本原因（而非症狀）

#### 1. **Session 生命週期管理缺失** 📌 CRITICAL

**現象**：第 4 次失敗，parent session 死亡導致 3 個 child agents 孤立無援

**根本原因**：
- Parent session 和 child agents 之間沒有心跳（heartbeat）機制
- 沒有檢測到 parent session 死亡的機制
- Child agents 無法主動探測 parent 是否還活著
- 沒有自動清理孤立 agents 的機制
- 沒有錯誤恢復流程（父進程死亡時）

**影響**：
```
時序圖：
─────────────────────────────────────────────
Parent Session (a284ec6b-251c-4a57...)
│
├─→ Architect Agent (in_progress)
├─→ Backend-QA Agent (in_progress)
└─→ Frontend-QA Agent (in_progress)
    ↑
    Parent 突然死亡（可能是超時、OOM、或人工中斷）
    │
    └─→ 三個 agents 不知道發生了什麼，繼續運行
        內存持續佔用，無法清理
```

**證據**：AGENT_TEAM_DESIGN_LESSONS.md 中記錄了類似行為（"內存持續增長"），但新設計 v2 沒有解決 session 生命週期問題。

---

#### 2. **隱式依賴關係編碼** 📌 CRITICAL

**現象**：第 3 次失敗，Task 被分配給錯誤的 Agent（應該分給 backend-qa 的被分給了 frontend-qa）

**根本原因**：
- QUALITY_AUDIT_PLAN_v2.md 中有**文字描述**的依賴關係，但沒有透過 `Task.blockedBy` / `Task.blocks` 編碼
- Task 系統無法強制執行依賴順序，完全依賴人工判斷（"讀一讀文檔，就知道誰應該做什麼"）
- 當分配邏輯出錯時，沒有自動檢測機制
- 任務分配與約束檢查沒有分離

**具體例子**（QUALITY_AUDIT_PLAN_v2.md）：
```markdown
Task #2 - Backend-QA
前置條件: Task #1 `completed` 或 `timeout` 後自動解鎖
```
❌ 問題：「前置條件」只寫在文檔裡，沒有在 `blockedBy` 欄位裡

✅ 應該是：
```json
{
  "id": 2,
  "subject": "Backend-QA: Schema、Kafka、Handler 完整性驗證",
  "blockedBy": [1],    // ← 明確編碼
  "blocks": [4],       // ← 明確編碼
  "status": "pending"
}
```

**為何關鍵**：
- Task 系統會**自動拒絕**將一個被 `blockedBy` 的任務分配給 Agent
- 如果 frontend-qa 嘗試接取 Task #2（被 blockedBy [1]），系統會檢查 Task #1 是否完成
- 完全避免人工錯誤

---

#### 3. **Prompt 清晰度不足——隱式停止條件** 📌 CRITICAL

**現象**：第 2 和 3 次失敗，Agent 完成任務後不知道應該停止

**根本原因**：
- QUALITY_AUDIT_PLAN_v2.md 中的 Prompt（Agent 分配欄）沒有**明確的停止條件**
- Agent 完成 Task #1 後，會查看是否還有其他工作（沒有明確告訴他停止）
- Agent 可能開始重新讀文件、重新分析、生成額外報告（導致內存持續增長）
- 沒有告訴 Agent "你完成後不應該繼續工作"

**QUALITY_AUDIT_PLAN_v2.md 中的 Prompt 缺陷**：
```markdown
If you complete Phase 1 and need to wait for Phase 4:
- You are DONE, do NOT start Task #4 until notified
- Do NOT contact backend-qa or frontend-qa
- Do NOT re-read files or generate extra reports
- Simply wait for team-lead notification when Phase 4 begins
```

❌ 問題：這些指令**寫在了文檔裡**，但在實際啟動 Agent Team 時，Agent 的真實 Prompt 是什麼？文檔說「你應該停止」，但沒有寫在分配給 Agent 的 Prompt 中。

**證據**：AGENT_TEAM_DESIGN_LESSONS.md §4（原因 2）明確指出這個問題，但 v2 設計沒有在"Agent 分配 & 配置"部分貼出完整的、每個 Agent 實際應該收到的 Prompt。

---

#### 4. **超時機制設計不完整** 📌 HIGH

**現象**：第 1-3 次失敗都卡 24+ 小時

**根本原因**：
- 提到了「2 小時 timeout」（QUALITY_AUDIT_PLAN_v2.md §同步機制），但**沒有具體的實現計劃**
- 不清楚誰來檢測 timeout（Team Lead? 系統自動?）
- 不清楚 timeout 後怎麼辦（強制標記為完成? 停止 Agent? 發送通知?）
- 沒有定義如何「檢查 agent 是否還在運行」（AGENT_TEAM_DESIGN_LESSONS.md §Launch Checklist 提到 "每 30 分鐘檢查一次"，但怎麼檢查？用什麼工具？）

**缺失的細節**：
```
❌ "2 小時 timeout，自動標記為 timeout"
   → 誰實現這個"自動"？
   → Agent 內部計時器？
   → 外部監控進程？
   → TaskUpdate 調用？

✅ "Team-lead 使用 CronJob 每 30 分鐘檢查一次
   - 獲取所有 in_progress tasks
   - 計算 (當前時間 - start_time)
   - 如果 > 2 小時，調用 TaskUpdate(status=timeout)
   - 發送通知給相關 agents"
```

---

#### 5. **顯式失敗恢復流程缺失** 📌 MEDIUM

**現象**：第 1-3 次失敗時不清楚應該怎麼恢復

**根本原因**：
- QUALITY_AUDIT_PLAN_v2.md 有"失敗恢復流程"章節，但**只是描述**，沒有**具體動作清單**
- 不清楚誰來決定"需要返工"（Team Lead? Architect?）
- 不清楚返工時是"重新執行 Task"還是"重新啟動整個 Team"
- 沒有自動恢復的機制（全部依賴人工干預）

**缺失的流程**：
```
✗ "修正問題後，重新執行 Task #1 或 Task #2"
  → "修正問題"由誰做？
  → 代碼修改？文檔更新？配置改變？
  → "重新執行"怎麼執行？TaskUpdate(status=pending)?
  → 既存 Agents 怎麼處理？Kill 掉重新啟動？

✓ "修正流程"應該有：
  1. 誰決定返工（Role）
  2. 返工涉及的代碼變更（Scope）
  3. 驗證修正是否完成（Checklist）
  4. Task 重置（TaskUpdate(status=pending, owner=null)）
  5. Agents 重新分配還是保留（Decision）
```

---

#### 6. **資源洩漏——孤立 Agents 無法清理** 📌 HIGH

**現象**：第 3 次失敗，3 個 agents 空轉 24+ 小時，內存從 380MB 漲到 440MB

**根本原因**：
- 當 Agent 卡住時，沒有自動清理機制
- Parent session 無法強制終止 child agents
- 沒有內存監控和告警
- 沒有"孤立 Agent"檢測機制（即 Agent 在無任務情況下持續運行 1+ 小時）

**缺失的防護**：
```
❌ Agent 無限期運行，內存持續增長
   380MB → 400MB → 420MB → 440MB → OOM

✅ 應該有：
   1. Agent 內部計時器：如果沒有分配新任務超過 30 分鐘，
      自動進入"idle 模式"（停止讀文件、停止分析）

   2. Parent session 監控器：
      - 每 10 分鐘檢查一次 in_progress agents 的狀態
      - 如果 Agent 內存增長 > 10% / 10min，發出告警
      - 如果 Agent 連續 idle 超過 1 小時，自動 kill

   3. Global timeout：
      - 即使有 blockedBy，也設定全局 timeout（6 小時）
      - 超過全局 timeout，強制 stop 所有 agents
```

---

### 設計層面的 5 個關鍵缺陷

| 缺陷 | 嚴重性 | 定位 | 影響 |
|------|-------|------|------|
| 1️⃣ Session 生命週期管理缺失 | CRITICAL | 系統層 | Parent 死亡 → Child agents 孤立 |
| 2️⃣ 隱式依賴關係（沒有編碼） | CRITICAL | Task 層 | 任務分配錯誤，無自動檢測 |
| 3️⃣ Prompt 停止條件模糊 | CRITICAL | Agent 層 | Agent 不知何時停止，持續消耗資源 |
| 4️⃣ Timeout 機制設計不完整 | HIGH | Agent 層 | 無法自動解鎖卡住的 agents |
| 5️⃣ 孤立 Agent 無法清理 | HIGH | 系統層 | 資源洩漏，導致 OOM |

---

## 第二部分：改進方案（5 大方向）

### 方案 1️⃣：Session 生命週期管理

#### 問題回顧
Parent session 死亡後，child agents 無法探測，持續佔用資源。

#### 改進設計

##### A. 心跳機制（Heartbeat）
```python
# Parent Session 實現（偽代碼）
class ParentSessionManager:
    def __init__(self, team_name):
        self.team_name = team_name
        self.heartbeat_file = f"~/.claude/teams/{team_name}/.heartbeat"
        self.last_heartbeat = time.time()

    def pulse(self):
        """每 30 秒更新一次心跳"""
        with open(self.heartbeat_file, 'w') as f:
            json.dump({
                'timestamp': time.time(),
                'parent_session_id': self.session_id,
                'status': 'alive'
            }, f)

    def start_heartbeat_thread(self):
        """啟動後台心跳線程"""
        def _pulse_loop():
            while True:
                self.pulse()
                time.sleep(30)
        thread = Thread(target=_pulse_loop, daemon=True)
        thread.start()

# Child Agent 實現（偽代碼）
class ChildAgentMonitor:
    def __init__(self, team_name):
        self.team_name = team_name
        self.heartbeat_file = f"~/.claude/teams/{team_name}/.heartbeat"

    def check_parent_alive(self):
        """每 1 分鐘檢查一次 parent 是否還活著"""
        try:
            with open(self.heartbeat_file, 'r') as f:
                data = json.load(f)
            time_since_pulse = time.time() - data['timestamp']

            if time_since_pulse > 120:  # 2 分鐘內沒有心跳
                return False  # Parent 已死亡
            return True
        except FileNotFoundError:
            return False  # 心跳文件不存在

    def on_parent_death(self):
        """Parent 死亡時的自動清理流程"""
        print("Parent session died. Initiating graceful shutdown...")

        # 步驟 1: 標記所有 in_progress tasks 為 timeout
        for task in self.get_assigned_tasks():
            TaskUpdate(task.id, status='timeout')

        # 步驟 2: 等待 1 分鐘，確保 tasks 保存
        time.sleep(60)

        # 步驟 3: 自動退出
        sys.exit(0)
```

##### B. Parent Session 監控器（Team-lead 的職責）
```markdown
**Team-lead 在 Parent Session 運行時的職責：**

1️⃣ 定期檢查 child agents 狀態（每 30 分鐘）
   ```bash
   # 檢查所有 in_progress agents
   curl http://localhost:3000/api/agents?status=in_progress
   ```

2️⃣ 檢測孤立 agents（任務超時但仍在運行）
   ```bash
   for agent in $(curl .../agents); do
       if agent.last_activity > 1_hour_ago && agent.status == in_progress:
           mark_task_timeout(agent.current_task)
           kill_agent(agent.id)  # 強制終止
   ```

3️⃣ 檢測內存洩漏
   ```bash
   if agent.memory_usage > baseline * 1.2:  # 增長超過 20%
       alert("Memory leak detected in agent: " + agent.name)
       # 決定是否 kill 和重啟
   ```

4️⃣ 定期保存 checkpoint
   ```bash
   # 每 1 小時保存一次當前狀態
   cp -r ~/.claude/tasks/[team_name] ~/.claude/tasks/[team_name].backup.$(date)
   ```
```

#### 實施檢查清單

- [ ] 實現 ParentSessionManager 的心跳機制（.heartbeat 文件）
- [ ] 實現 ChildAgentMonitor 的心跳檢測（每 1 分鐘檢查一次）
- [ ] 實現 Parent 死亡時的自動清理流程（標記 timeout + 退出）
- [ ] Team-lead 添加監控腳本（每 30 分鐘檢查一次）
- [ ] 添加內存監控和告警
- [ ] 測試：手動 kill parent session，驗證 child agents 是否正確清理

---

### 方案 2️⃣：顯式依賴編碼

#### 問題回顧
依賴關係只寫在文檔裡，沒有在 Task 系統中編碼，導致任務分配錯誤。

#### 改進設計

##### A. Task 系統強化
```json
// 啟動 Agent Team 時，必須為每個 Task 設定 blockedBy 和 blocks
{
  "id": 1,
  "subject": "Architecture Review (Task #1)",
  "owner": "architect",
  "status": "pending",
  "blockedBy": [],           // ← 我不等任何人
  "blocks": [2, 3],          // ← 我阻擋 Task #2 和 #3
  "timeout_seconds": 7200    // ← 2 小時 timeout
}

{
  "id": 2,
  "subject": "Backend-QA Data Layer Verification (Task #2)",
  "owner": "backend-qa",
  "status": "blocked",
  "blockedBy": [1],          // ← 我等 Task #1
  "blocks": [4],             // ← 我阻擋 Task #4
  "timeout_seconds": 7200
}

{
  "id": 3,
  "subject": "Frontend-QA UI Integration Verification (Task #3)",
  "owner": "frontend-qa",
  "status": "blocked",
  "blockedBy": [1],          // ← 我等 Task #1
  "blocks": [4],             // ← 我阻擋 Task #4
  "timeout_seconds": 7200
}

{
  "id": 4,
  "subject": "System Integration Verification (Task #4)",
  "owner": "architect",
  "status": "blocked",
  "blockedBy": [2, 3],       // ← 我等 Task #2 AND Task #3
  "blocks": [],              // ← 我是最後一個 task
  "timeout_seconds": 7200
}
```

##### B. Task 系統驗證規則
```python
class TaskDependencyValidator:
    """在啟動 Agent Team 前，驗證所有依賴關係"""

    @staticmethod
    def validate(tasks):
        errors = []

        # 檢查 1: 沒有循環依賴
        if has_circular_dependency(tasks):
            errors.append("❌ 循環依賴: Task A blocks B, B blocks A")

        # 檢查 2: 所有 blockedBy 的 task 都存在
        for task in tasks:
            for blocker_id in task.blockedBy:
                if not any(t.id == blocker_id for t in tasks):
                    errors.append(f"❌ Task #{task.id} 等待不存在的 Task #{blocker_id}")

        # 檢查 3: 沒有孤立的 task（unreachable）
        reachable = find_reachable_tasks(tasks)
        for task in tasks:
            if task.id not in reachable:
                errors.append(f"❌ Task #{task.id} 孤立（無法從起點到達）")

        # 檢查 4: 有明確的起點和終點
        start_tasks = [t for t in tasks if not t.blockedBy]
        end_tasks = [t for t in tasks if not t.blocks]
        if not start_tasks or not end_tasks:
            errors.append("❌ 沒有明確的起點或終點")

        # 檢查 5: 超時時間已設定
        for task in tasks:
            if not hasattr(task, 'timeout_seconds') or task.timeout_seconds == 0:
                errors.append(f"❌ Task #{task.id} 沒有設定 timeout_seconds")

        if errors:
            print("\n".join(errors))
            return False

        print("✅ 所有依賴關係驗證通過")
        return True
```

##### C. 強制執行規則
```markdown
**在分配 Task 給 Agent 前，TaskSystem 必須檢查：**

1. Task 的 blockedBy 列表中的所有 task 是否都已 completed?
   - NO → 拒絕分配（status 保持 blocked）
   - YES → 允許分配（status 變 pending，可被 Agent 領取）

2. Agent 嘗試 TaskUpdate(id=X, owner=null) 時，
   檢查 Task #X 的 blockedBy 是否都完成？
   - NO → 拒絕標記完成（返回 HTTP 409 Conflict）
   - YES → 允許標記完成，自動檢查是否有 task 在等 #X

3. Task 完成時，自動解鎖被它 block 的所有 tasks
   ```
   Task #1 completed
   → 檢查 Task #1.blocks = [2, 3]
   → 對於 Task #2: blockedBy = [1] → 1 已完成 → 狀態改為 pending
   → 對於 Task #3: blockedBy = [1] → 1 已完成 → 狀態改為 pending
   → Task #2 和 #3 現在對任何 Agent 可見
   ```
```

#### 實施檢查清單

- [ ] 在 TeamCreate 時強制收集 task 的 blockedBy/blocks 信息
- [ ] 實現 TaskDependencyValidator，在啟動前執行
- [ ] Task 系統強制執行：Task 完成前檢查 blockedBy 都已完成
- [ ] 自動解鎖機制：Task 完成時，自動更新所有被它 block 的 tasks
- [ ] 禁止直接修改 Task 的 blockedBy/blocks（只能在初始化時設定）
- [ ] 添加 Task 依賴圖可視化（GraphQL query or REST endpoint）
- [ ] 測試：創建一個複雜的依賴圖，驗證分配順序正確

---

### 方案 3️⃣：Prompt 清晰度——明確停止條件

#### 問題回顧
Agent 完成任務後不知道應該停止，持續讀文件和重新分析，導致內存增長。

#### 改進設計

##### A. Prompt 樣板（Architecture Review Example）
```markdown
# You are the ARCHITECT on simpleec-oms-quality-audit-v2 team

## 你的職責
你負責 2 個階段的工作：
- **Phase 1 (Task #1)**: 架構與 API 設計審查（0-2 小時）
- **Phase 4 (Task #4)**: 系統整合驗證（4-6 小時，在 Task #2 和 #3 完成後）

## ⏱️ 時間表
```
時間      你的狀態          具體工作
─────────────────────────────────────────
0-2h      IN_PROGRESS      Task #1: 審查架構 & API
                           生成 findings JSON
                           標記 task 完成
2-4h      WAITING          等待 Task #2 和 #3 完成
          (不工作)          不讀文件、不分析、不生成報告
4-6h      IN_PROGRESS      Task #4: 整合所有 findings
                           生成最終報告
6h+       DONE             全部完成，等待 team-lead 通知下一步
```

## 📝 Phase 1 詳細執行步驟

### 輸入檔案
- `/docs/1-ARCHITECTURE/DESIGN_v2.md`（系統架構）
- `/docs/2-API/ADMIN_API.md`, `/docs/2-API/USER_API.md`（API 契約）
- `/docs/3-EVENT-FLOW/CORE_CONTRACTS.md`（事件契約）
- `/docs/4-SCHEMA/SCHEMA.md`（資料庫 Schema）

### 你的任務（Phase 1）
1. 讀上述 4 個文件，理解系統整體設計
2. 檢查以下 3 個方面：
   - ✅ 11 個模組的架構邊界是否清晰？
   - ✅ API 文檔承諾的欄位是否在 Schema 中存在？
   - ✅ Kafka Header/Body 約定是否一致？
3. 識別任何架構層的不一致
4. 生成 JSON findings（見下方格式）
5. **標記 Task #1 為 completed**

### 交付物格式（必須是 JSON）
```json
{
  "task_id": 1,
  "status": "completed",
  "phase": "architecture_review",
  "findings": {
    "api_schema_alignment_score": 85,
    "critical_issues": [
      {
        "issue": "Field type mismatch",
        "impact": "HIGH",
        "location": "API_ADMIN.md:line 45 vs SCHEMA.md:table orders",
        "fix_effort_hours": 2
      }
    ],
    "p2_issues": [
      {
        "issue": "Missing field documentation",
        "impact": "MEDIUM",
        "location": "USER_API.md",
        "fix_effort_hours": 1
      }
    ],
    "architecture_readiness": true,
    "ready_for_backend_qa": true,
    "notes_for_next_phase": "API design is solid, but schema needs minor alignment"
  }
}
```

## ⏹️ 停止條件【重要】

### Phase 1 完成後（2 小時內）

**✅ 你應該做的**：
1. 生成上述 JSON findings
2. 調用 TaskUpdate(id=1, status='completed') 並包含 findings
3. **停止所有工作**

**❌ 你絕對不應該做的**：
- ❌ 重新讀文件（即使感覺有疑問）
- ❌ 開始 Phase 4（即使有時間）
- ❌ 聯繫 backend-qa 或 frontend-qa（通過他們的可見性來推斷進度）
- ❌ 生成額外的報告或分析
- ❌ 檢查其他 tasks 的狀態
- ❌ 持續訪問檔案系統（會增加內存消耗）

**你應該進入"待命模式"**：
- 不讀任何檔案
- 不執行任何計算
- 不調用任何 API
- 只有一件事：等待 team-lead 的明確通知

### 如何知道 Phase 4 應該開始？

**ONLY 以下情況下開始 Phase 4**：
1. 你收到來自 team-lead 的明確訊息：`"Phase 4 begins now"`
2. 該訊息包含 Task #2 和 #3 的 findings（JSON 格式）
3. 你收到 TaskUpdate 通知，告訴你 Task #4 已解鎖（status 改為 pending）

**如果沒有收到上述通知**：
- 不要主動開始 Phase 4
- 不要去檢查 Task #2 和 #3 是否完成（即使已經過了 4 小時）
- 等待 team-lead 的明確指示

### Timeout 保護

如果 2 小時後還沒完成 Phase 1：
- **自動 timeout 機制會啟動**
- 你不需要做任何事，TaskSystem 會自動標記為 timeout
- 後續階段會自動開始（不會被 Task #1 阻擋）

如果超過 6 小時（包括 Phase 1 和 Phase 4）還沒完成全部工作：
- **整個 Team 會被全局 timeout**
- 所有 agents 會被自動終止
- team-lead 會收到通知並決定是否重啟

## 📬 與其他 Agents 的通訊規則

**你不應該主動聯繫其他 agents，因為**：
- 他們可能還在忙自己的 task
- 他們的進度表可能和你不同
- 直接通訊容易導致依賴不清晰

**唯一的例外**：
- 如果你在 Phase 1 發現 CRITICAL 問題（即系統無法運行）
- 則通知 team-lead: "Phase 1 發現 CRITICAL 問題：[描述]"
- team-lead 會決定是否中斷其他 phases 進行修正

## 📊 成功標準

**Phase 1 成功** ✅：
- JSON findings 已生成，包含以下欄位：
  - `api_schema_alignment_score` (0-100)
  - `critical_issues` (至少 0 個)
  - `p2_issues` (至少 0 個)
  - `ready_for_backend_qa` (true 或 false)
- Task #1 狀態已標記為 completed

**Phase 4 成功** ✅（稍後詳述）：
- 整合了 Task #2 和 #3 的 findings
- 生成最終系統健康度報告
- Task #4 狀態標記為 completed

## 有問題嗎？

如果你在執行過程中遇到以下情況：
1. 無法讀取某個檔案 → 通知 team-lead
2. 文件內容模糊或矛盾 → 標記在 findings 中的 `notes_for_next_phase`
3. 需要更多時間 → 無需主動延期，timeout 機制會自動處理
4. 發現其他 agents 的 task 有錯誤 → 不要修改，通知 team-lead

---
**Last Updated**: 2026-03-17
**Version**: v2.1-prompt-template
```

##### B. 不同角色的 Prompt 模版
```markdown
## Backend-QA Prompt Template

You are the BACKEND-QA on simpleec-oms-quality-audit-v2 team.

**你的職責**：
- **唯一的 Task**: Task #2 - 數據層驗證（2-4 小時）

**前置條件**：
- Task #1 必須先完成（否則你收不到任務）
- 你會在 Task #1 完成時自動被分配 Task #2

**時間表**：
```
2h-4h     IN_PROGRESS      Task #2: 驗證 Schema、Kafka、Handler
4h+       WAITING          等待 Task #3 完成（不工作）
          DONE             全部完成
```

**執行步驟**（見 QUALITY_AUDIT_PLAN_v2.md §Task #2）：
1. 讀取指定檔案
2. 驗證 3 個方面（Schema、Kafka、Handler）
3. 生成 JSON findings
4. 標記 Task #2 為 completed

**⏹️ 停止條件**：
- ❌ 不聯繫 architect 或 frontend-qa
- ❌ 不檢查 Task #3 的進度
- ❌ Task #2 完成後立即停止
- ✅ 等待 Task #4 自動開始（無需你參與）

---

## Frontend-QA Prompt Template

You are the FRONTEND-QA on simpleec-oms-quality-audit-v2 team.

**你的職責**：
- **唯一的 Task**: Task #3 - UI 層驗證（2-4 小時）

**前置條件**：
- Task #1 必須先完成

**時間表**：
```
2h-4h     IN_PROGRESS      Task #3: 驗證 User App、Admin App
4h+       WAITING          等待 Task #2 完成（不工作）
          DONE             全部完成
```

**執行步驟**（見 QUALITY_AUDIT_PLAN_v2.md §Task #3）
1. 讀取指定檔案
2. 驗證 UI 層的 4 個方面
3. 生成 JSON findings
4. 標記 Task #3 為 completed

**⏹️ 停止條件**：
- ❌ 不聯繫 architect 或 backend-qa
- ❌ 不檢查 Task #2 的進度
- ❌ Task #3 完成後立即停止
- ✅ 等待 Task #4 自動開始

---

## Team-Lead Prompt Template

You are the TEAM-LEAD (Haiku 4.5) on simpleec-oms-quality-audit-v2 team.

**你的職責**（主要是監控和協調，NOT 做技術驗證）：
1. 定期監控所有 agents 的狀態（每 30 分鐘）
2. 檢測超時和故障
3. 強制執行 timeout 機制
4. 在 Phase 轉換時發送明確通知
5. 收集 findings 並生成摘要報告

**時間表**：
```
0-2h      監控 Task #1 (architect 在做)
2-4h      檢查 Task #1 是否完成
          監控 Task #2 & #3（並行）
4-6h      檢查 Task #2 和 #3 是否都完成
          通知 architect 開始 Task #4
          監控 Task #4
6h+       收集最終 findings
          生成整體報告
```

**具體動作**：

### 每 30 分鐘檢查一次
```python
for agent in [architect, backend_qa, frontend_qa]:
    status = get_agent_status(agent)

    # 檢查 1: 是否超時？
    if status.elapsed_time > status.timeout_seconds:
        mark_task_timeout(agent.current_task)
        send_notification(agent, "Your task has timed out, stopping work")

    # 檢查 2: 內存洩漏？
    if status.memory_usage > baseline * 1.2:
        alert(f"Memory leak in {agent.name}: {status.memory_usage}")

    # 檢查 3: 是否卡住？
    if status.last_activity > 1_hour_ago and status.in_progress:
        alert(f"Agent {agent.name} appears stuck")
```

### Phase 轉換時發送明確通知
```python
# 當 Task #1 完成時
if task[1].status == 'completed':
    # 自動解鎖 Task #2 和 #3（已由 TaskSystem 做）
    send_message(backend_qa, "Task #2 is now available. Begin when ready.")
    send_message(frontend_qa, "Task #3 is now available. Begin when ready.")

# 當 Task #2 和 #3 都完成時
if task[2].status == 'completed' and task[3].status == 'completed':
    # 自動解鎖 Task #4
    send_message(architect,
        "Phase 4 begins now.\n"
        "Task #2 findings:\n{task[2].findings}\n\n"
        "Task #3 findings:\n{task[3].findings}\n\n"
        "Please integrate these and generate final report.")
```

**成功標準**：
- 所有 tasks 在 6 小時內完成（或超時）
- 沒有 agents 內存洩漏
- 沒有 agents 被卡住超過 1 小時
- 所有 findings JSON 都是有效格式
- 最終報告已生成
```

#### 實施檢查清單

- [ ] 為每個角色編寫完整的 Prompt 模版
- [ ] 模版中明確列出"⏹️ 停止條件"（哪些事 MUST NOT DO）
- [ ] 模版中明確列出時間表
- [ ] 模版中明確列出交付物格式（JSON schema）
- [ ] 模版中明確列出如何接收下一個 phase 的通知
- [ ] Team-lead 的 Prompt 中明確列出監控動作（偽代碼）
- [ ] 在啟動 Agent Team 時，直接複製完整 Prompt（不要讓 Agent 去讀文件推斷）
- [ ] 測試：啟動 Team 後 2 小時檢查 agents 是否已停止工作

---

### 方案 4️⃣：Timeout 機制完整實現

#### 問題回顧
Timeout 概念存在於文檔，但沒有具體的實現計劃。

#### 改進設計

##### A. 三層 Timeout 機制
```python
class TimeoutManager:
    """多層次的 timeout 保護"""

    # Layer 1: Task-level timeout（每個 task 獨立）
    TASK_TIMEOUT = 2 * 3600  # 2 小時

    # Layer 2: Phase-level timeout（某個階段超過預期）
    PHASE_TIMEOUT = 4 * 3600  # 4 小時

    # Layer 3: Global timeout（整個 team 超過預期）
    GLOBAL_TIMEOUT = 8 * 3600  # 8 小時

    def __init__(self, team_name):
        self.team_name = team_name
        self.start_time = time.time()

    # ========== Layer 1: Task Timeout ==========
    def check_task_timeout(self, task):
        """檢查單個 task 是否超時"""
        elapsed = time.time() - task.start_time

        if elapsed > self.TASK_TIMEOUT:
            return True
        return False

    def handle_task_timeout(self, task):
        """Task 超時的處理流程"""
        print(f"Task #{task.id} timeout after {self.TASK_TIMEOUT}s")

        # 步驟 1: 標記為 timeout（不是 failed）
        TaskUpdate(task.id, status='timeout')

        # 步驟 2: 如果有任務被它 block，自動解鎖
        for blocked_task_id in task.blocks:
            blocked_task = get_task(blocked_task_id)
            # 移除該 task 的 blocker
            blocked_task.blockedBy.remove(task.id)

            # 如果 blockedBy 列表為空，解鎖
            if not blocked_task.blockedBy:
                TaskUpdate(blocked_task_id, status='pending')

        # 步驟 3: 通知 team-lead
        send_message(team_lead,
            f"Task #{task.id} ({task.subject}) timed out after {self.TASK_TIMEOUT}s")

        # 步驟 4: Terminate 執行該 task 的 agent（如果還在跑）
        if task.owner:
            kill_agent(task.owner)

    # ========== Layer 2: Phase Timeout ==========
    def get_current_phase(self):
        """根據 in_progress 任務推斷當前 phase"""
        in_progress = [t for t in all_tasks if t.status == 'in_progress']
        if not in_progress:
            return None

        # 簡單策略：根據 task.id 推斷 phase（假設 id 1 = phase 1, id 2-3 = phase 2）
        min_task_id = min(t.id for t in in_progress)
        return {1: 'phase1', 2: 'phase2', 3: 'phase2', 4: 'phase4'}.get(min_task_id)

    def check_phase_timeout(self):
        """檢查當前 phase 是否超時"""
        phase = self.get_current_phase()
        if not phase:
            return False

        # Phase 的預期最長時間
        phase_limits = {
            'phase1': 2.5 * 3600,      # Phase 1: 2.5 小時
            'phase2': 4.5 * 3600,      # Phase 2 (2&3 並行): 4.5 小時
            'phase4': 7 * 3600          # Phase 4: 7 小時
        }

        elapsed_in_phase = time.time() - self.get_phase_start_time(phase)

        if elapsed_in_phase > phase_limits.get(phase, float('inf')):
            return True
        return False

    def handle_phase_timeout(self):
        """Phase 超時的處理流程"""
        phase = self.get_current_phase()
        print(f"Phase {phase} timeout, forcing transition")

        # 步驟 1: 強制標記所有 in_progress tasks 為 timeout
        for task in all_tasks:
            if task.status == 'in_progress':
                self.handle_task_timeout(task)

        # 步驟 2: 如果是 Phase 1, 自動進入 Phase 2
        # 如果是 Phase 2, 自動進入 Phase 4
        # （由 Task blockedBy 機制自動處理）

        # 步驟 3: 通知 team-lead
        send_message(team_lead,
            f"Phase {phase} exceeded time limit, forcing transition")

    # ========== Layer 3: Global Timeout ==========
    def check_global_timeout(self):
        """檢查整個 team 是否超過全局 timeout"""
        elapsed = time.time() - self.start_time
        return elapsed > self.GLOBAL_TIMEOUT

    def handle_global_timeout(self):
        """全局超時 → 全部 stop"""
        print("Global timeout reached, shutting down entire team")

        # 步驟 1: 標記所有 in_progress 和 blocked tasks 為 timeout
        for task in all_tasks:
            if task.status in ['in_progress', 'blocked', 'pending']:
                TaskUpdate(task.id, status='timeout')

        # 步驟 2: Kill 所有 agents
        for agent in all_agents:
            kill_agent(agent.id)

        # 步驟 3: 通知 team-lead 進行最終決定（重啟？放棄？）
        send_message(team_lead,
            f"Global timeout reached ({self.GLOBAL_TIMEOUT}s). "
            f"All agents stopped. Please decide: restart or abort.")
```

##### B. 監控迴圈（Team-lead 實施）
```python
class TimeoutMonitor:
    """持續監控 timeout，與 Team-lead 協調"""

    def __init__(self, team_name):
        self.team_name = team_name
        self.timeout_mgr = TimeoutManager(team_name)
        self.check_interval = 30  # 每 30 秒檢查一次

    def start_monitoring(self):
        """啟動監控迴圈"""
        while True:
            try:
                # 檢查 1: Task-level timeout
                for task in get_all_tasks(self.team_name):
                    if task.status == 'in_progress':
                        if self.timeout_mgr.check_task_timeout(task):
                            self.timeout_mgr.handle_task_timeout(task)

                # 檢查 2: Phase-level timeout
                if self.timeout_mgr.check_phase_timeout():
                    self.timeout_mgr.handle_phase_timeout()

                # 檢查 3: Global timeout
                if self.timeout_mgr.check_global_timeout():
                    self.timeout_mgr.handle_global_timeout()
                    break  # 全局 timeout 後停止監控迴圈

                # 等待下一個檢查
                time.sleep(self.check_interval)

            except Exception as e:
                # 監控本身失敗時的處理
                print(f"❌ Timeout monitor error: {e}")
                send_message(team_lead, f"⚠️ Timeout monitor crashed: {e}")
                # 繼續重試，不要停止監控
                time.sleep(60)
```

##### C. 啟動 Team 時的初始化
```bash
# 啟動 Agent Team 時
def start_team(team_name, agent_configs):
    # 步驟 1: 創建 Team
    TeamCreate(team_name)

    # 步驟 2: 創建 Tasks 並驗證依賴
    tasks = create_tasks()
    if not TaskDependencyValidator.validate(tasks):
        print("❌ Task dependency validation failed")
        return False

    # 步驟 3: 啟動 Team-lead Agent
    team_lead = spawn_agent('team-lead', model='haiku-4.5')

    # 步驟 4: 啟動 Timeout Monitor（獨立進程）
    monitor = TimeoutMonitor(team_name)
    monitor_process = Process(target=monitor.start_monitoring)
    monitor_process.start()

    # 步驟 5: 啟動其他 Agents
    for config in agent_configs:
        spawn_agent(config.name, model=config.model, prompt=config.prompt)

    print(f"✅ Team '{team_name}' started with {len(agent_configs)} agents")
    print(f"   Timeouts: Task=2h, Phase=4.5h, Global=8h")
    print(f"   Monitor process PID: {monitor_process.pid}")

    return True
```

#### 實施檢查清單

- [ ] 實現 TimeoutManager 的三層 timeout 邏輯
- [ ] 實現 TimeoutMonitor 的監控迴圈
- [ ] 在 handle_task_timeout 中實現自動解鎖 logic
- [ ] 測試：創建一個 2 秒的 task timeout，驗證是否自動標記為 timeout
- [ ] 測試：創建一個 3 秒的 phase timeout，驗證是否正確進行 phase 轉換
- [ ] 測試：創建一個 5 秒的 global timeout，驗證是否全部 stop
- [ ] 添加 Timeout monitor 的日誌記錄
- [ ] 在 Team-lead Prompt 中提及 timeout 機制，讓 Agent 知道有自動保護

---

### 方案 5️⃣：孤立 Agent 自動清理

#### 問題回顧
Agent 卡住時無法清理，持續佔用內存，最終 OOM。

#### 改進設計

##### A. Agent 健康檢測
```python
class AgentHealthMonitor:
    """監測每個 agent 的健康狀態"""

    def __init__(self, team_name):
        self.team_name = team_name
        self.baseline_memory = {}  # agent_name → baseline_memory_mb
        self.inactivity_timeout = 3600  # 1 小時無活動 → 視為卡住

    def get_agent_health(self, agent_name):
        """檢查 agent 的健康狀態"""
        metrics = get_agent_metrics(agent_name)  # 假設有這個 API

        return {
            'agent_name': agent_name,
            'status': metrics.status,
            'memory_mb': metrics.memory_mb,
            'memory_growth': self._calc_memory_growth(agent_name, metrics.memory_mb),
            'last_activity': metrics.last_activity_time,
            'inactivity_seconds': time.time() - metrics.last_activity_time,
            'is_stuck': self._is_stuck(agent_name, metrics)
        }

    def _calc_memory_growth(self, agent_name, current_memory_mb):
        """計算內存增長百分比"""
        baseline = self.baseline_memory.get(agent_name, current_memory_mb)
        if baseline == 0:
            return 0
        return ((current_memory_mb - baseline) / baseline) * 100

    def _is_stuck(self, agent_name, metrics):
        """判斷 agent 是否卡住"""
        inactivity = time.time() - metrics.last_activity_time

        # 條件 1: 連續無活動超過 1 小時
        if inactivity > self.inactivity_timeout:
            return True

        # 條件 2: 內存增長超過 20% / 30分鐘
        memory_growth = self._calc_memory_growth(agent_name, metrics.memory_mb)
        if memory_growth > 20:
            return True

        # 條件 3: 任務應該在 2 小時內完成，但已經 3 小時還在 in_progress
        current_task = get_task_by_owner(agent_name)
        if current_task and current_task.status == 'in_progress':
            elapsed = time.time() - current_task.start_time
            if elapsed > 3 * 3600:  # 超過 3 小時
                return True

        return False

    def handle_stuck_agent(self, agent_name):
        """Agent 卡住時的處理流程"""
        print(f"❌ Agent '{agent_name}' is stuck, initiating cleanup...")

        # 步驟 1: 通知 team-lead
        send_message(team_lead,
            f"⚠️ Agent '{agent_name}' is stuck. Taking action: "
            f"marking current task as timeout and killing agent.")

        # 步驟 2: 標記當前 task 為 timeout
        current_task = get_task_by_owner(agent_name)
        if current_task and current_task.status == 'in_progress':
            TimeoutManager.handle_task_timeout(current_task)

        # 步驟 3: Kill agent
        kill_agent(agent_name)

        # 步驟 4: 清理資源
        cleanup_agent_resources(agent_name)

        # 步驟 5: 清除 task 所有權（允許其他 agent 或重新分配）
        if current_task:
            TaskUpdate(current_task.id, owner=None, status='pending')

    def monitor_agents(self):
        """持續監控所有 agents 的健康狀態"""
        check_interval = 60  # 每 60 秒檢查一次

        while True:
            try:
                agents = get_all_agents(self.team_name)

                for agent in agents:
                    health = self.get_agent_health(agent.name)

                    # 記錄健康狀態
                    log_health_metrics(self.team_name, health)

                    # 檢查是否卡住
                    if health['is_stuck']:
                        self.handle_stuck_agent(agent.name)

                    # 發送告警
                    if health['memory_growth'] > 15:
                        send_alert(f"⚠️ Agent {agent.name} memory growth: {health['memory_growth']:.1f}%")

                time.sleep(check_interval)

            except Exception as e:
                print(f"❌ Agent health monitor error: {e}")
                time.sleep(60)
```

##### B. Agent 內部安全閥（防止自己洩漏）
```python
# 在每個 Agent 的 Prompt 中添加以下指令

## 自動保護機制（Agent 內部實施）

**你有內建的保護機制，防止無限期運行**：

1. **Idle Timeout**: 如果你 30 分鐘內沒有完成任何工作（沒有讀文件、沒有寫報告），
   自動進入 idle 模式並停止所有計算。

2. **Memory Limit**: 如果你的內存使用超過 500MB，自動停止當前工作並退出。

3. **Task Timeout**: 如果你的當前 task 超過 2 小時，系統會自動標記為 timeout
   並終止你的執行。

4. **Global Timeout**: 如果整個 team 運行超過 8 小時，所有 agents 會被強制終止。

你不需要擔心這些——它們是自動的，不會因為你在做工作就激發。
```

##### C. 資源監控儀表板（Team-lead 可視化）
```python
class ResourceDashboard:
    """實時監控 Team 的資源使用"""

    def generate_status_report(self, team_name):
        """生成當前 team 的資源狀態報告"""

        agents = get_all_agents(team_name)
        tasks = get_all_tasks(team_name)

        report = {
            'timestamp': time.time(),
            'team_name': team_name,
            'agents': [],
            'tasks': [],
            'alerts': []
        }

        # 收集 agent 信息
        for agent in agents:
            health = AgentHealthMonitor().get_agent_health(agent.name)
            report['agents'].append({
                'name': agent.name,
                'status': health['status'],
                'memory_mb': health['memory_mb'],
                'memory_growth_pct': health['memory_growth'],
                'inactivity_seconds': health['inactivity_seconds'],
                'is_stuck': health['is_stuck']
            })

            if health['is_stuck']:
                report['alerts'].append(f"❌ Agent {agent.name} is stuck")

        # 收集 task 信息
        for task in tasks:
            elapsed = time.time() - task.start_time if task.status == 'in_progress' else 0
            report['tasks'].append({
                'id': task.id,
                'subject': task.subject,
                'status': task.status,
                'owner': task.owner,
                'elapsed_seconds': elapsed,
                'blockedBy': task.blockedBy,
                'blocks': task.blocks
            })

        return report

    def print_dashboard(self, report):
        """以表格形式打印儀表板"""

        print(f"\n{'=' * 80}")
        print(f"TEAM STATUS: {report['team_name']} @ {time.ctime(report['timestamp'])}")
        print(f"{'=' * 80}\n")

        # 告警
        if report['alerts']:
            print("⚠️  ALERTS:")
            for alert in report['alerts']:
                print(f"   {alert}")
            print()

        # Agents 狀態
        print("AGENTS:")
        print(f"  {'Name':<15} {'Status':<12} {'Memory':<10} {'Growth':<10} {'Inactive':<10} {'Stuck':<6}")
        print(f"  {'-'*63}")
        for agent in report['agents']:
            print(f"  {agent['name']:<15} {agent['status']:<12} "
                  f"{agent['memory_mb']:<10.0f}MB {agent['memory_growth_pct']:<9.1f}% "
                  f"{agent['inactivity_seconds']:<10.0f}s {'YES' if agent['is_stuck'] else 'NO':<6}")
        print()

        # Tasks 狀態
        print("TASKS:")
        print(f"  {'#':<3} {'Subject':<35} {'Status':<12} {'Owner':<12} {'Elapsed':<10}")
        print(f"  {'-'*72}")
        for task in report['tasks']:
            owner = task['owner'] if task['owner'] else '(unassigned)'
            print(f"  {task['id']:<3} {task['subject']:<35} {task['status']:<12} "
                  f"{owner:<12} {task['elapsed_seconds']:.0f}s")
        print(f"  {'-'*72}\n")
```

#### 實施檢查清單

- [ ] 實現 AgentHealthMonitor 的監控邏輯
- [ ] 實現 _is_stuck() 方法的 3 個條件
- [ ] 實現 handle_stuck_agent() 的清理流程
- [ ] 在 Team-lead 中啟動 AgentHealthMonitor（與 TimeoutMonitor 平行）
- [ ] 實現 ResourceDashboard 的狀態報告
- [ ] 添加自動告警機制（內存增長 > 15%, 無活動 > 30 分鐘）
- [ ] 測試：創建一個卡住的 agent，驗證是否被自動檢測和清理
- [ ] 添加 kill agent 失敗時的降級方案（強制 kill, SIGKILL）

---

## 第三部分：Agent Team 設計清單

### ✅ 用於下次設計的檢查清單

在啟動任何新的 Agent Team 前，複製並檢查此清單：

```markdown
# Agent Team 設計清單 v2.1

**Team Name**: _______________
**Start Date**: _______________
**Lead**: _______________

## 📋 Pre-Launch Phase（啟動前）

### 系統檢查
- [ ] 記憶體充足（至少 8GB 可用）
- [ ] 沒有舊的 Agent Team 在執行
- [ ] Task 目錄清空（~/.claude/tasks/ 中無相關文件）
- [ ] Heartbeat 監控機制已啟用
- [ ] 備份既存 critical data

### Task 設計
- [ ] 每個 Task 有唯一的 ID（1, 2, 3, ...）
- [ ] 每個 Task 有清晰的 `subject` 和 `description`
- [ ] **每個 Task 的 `blockedBy` 和 `blocks` 已明確填寫**
- [ ] 運行 TaskDependencyValidator：✅ 通過
  ```bash
  python validate_tasks.py tasks.json
  ```
- [ ] 沒有循環依賴（用視覺化工具檢查）
- [ ] 有明確的起點（blockedBy = []）和終點（blocks = []）
- [ ] **每個 Task 有 `timeout_seconds` 設定**（建議 2-4 小時）

### Prompt 設計
- [ ] 每個 Agent 的 Prompt 已完整編寫（不是來自文檔的參考）
- [ ] Prompt 包含 4 個必需部分：
  - [ ] 責任陳述（我做什麼）
  - [ ] 時間表（預期時長）
  - [ ] **⏹️ 停止條件（明確列出 ❌ 不應做）**
  - [ ] 交付物格式（結構化 JSON）
- [ ] Prompt 明確說明"如何接收下一個 phase 的通知"
- [ ] Prompt 禁止了以下行為：
  - [ ] ❌ 聯繫其他 agents（除了報錯給 team-lead）
  - [ ] ❌ 重新讀文件或重新分析
  - [ ] ❌ 生成額外報告
  - [ ] ❌ 檢查其他 tasks 的進度
- [ ] Team-lead Prompt 包含監控動作（偽代碼）

### 交付物定義
- [ ] 所有 deliverables 都是結構化的（JSON）
- [ ] 每個 JSON 都包含 `status` 欄位
- [ ] 每個 JSON 都包含"完整性指標"（e.g., `ready_for_next_phase: true/false`）
- [ ] 下一個 Agent 能直接解析上一個 Agent 的 JSON（無需人工翻譯）

### 依賴和同步
- [ ] 有明確的 "team-lead" 角色（負責監控和協調）
- [ ] Team-lead 知道具體的監控動作（不是模糊的"監控"）
- [ ] 有明確的"Phase 轉換"機制（誰通知誰，怎麼通知）
- [ ] **沒有 Agent-to-Agent 的直接通信**（都通過 team-lead）
- [ ] 失敗恢復流程已明確（誰決定返工，怎麼重新執行）

### Timeout 和故障保護
- [ ] Task 級別 timeout：✅ 已設定
- [ ] Phase 級別 timeout：✅ 已計算
- [ ] Global timeout：✅ 已計算（通常 = 所有 Task timeouts 的總和 + 30%）
- [ ] Timeout 時的自動解鎖機制：✅ 已實現
- [ ] Timeout 時的通知機制：✅ 已實現
- [ ] Agents 不知道自己什麼時候會被 timeout（自動發生）

### 資源管理
- [ ] 孤立 Agent 的自動檢測機制：✅ 已實現
- [ ] Agent 卡住（無活動 1 小時）時的自動清理：✅ 已實現
- [ ] 內存洩漏的檢測和告警：✅ 已實現
- [ ] 資源監控儀表板：✅ 可用

---

## 🚀 Launch Phase（啟動）

### 初始化
- [ ] 執行 TeamCreate 創建 Team
- [ ] 執行 TaskCreate 創建所有 Tasks
- [ ] 執行 TaskDependencyValidator.validate()
- [ ] 啟動 TimeoutMonitor（獨立進程）
- [ ] 啟動 AgentHealthMonitor（獨立進程）
- [ ] 啟動 Team-lead Agent
- [ ] 啟動其他 Agents

### 即時監控（前 2 小時）
- [ ] 每 5 分鐘查看一次日誌
  ```bash
  tail -f ~/.claude/logs/teams/[team_name].log
  ```
- [ ] 每 5 分鐘查看一次任務狀態
  ```bash
  curl http://localhost:3000/api/tasks?team=[team_name]
  ```
- [ ] 每 10 分鐘查看一次資源儀表板
  ```bash
  python dashboard.py [team_name]
  ```
- [ ] 預備應急措施
  - [ ] 知道怎麼 kill 所有 agents（SIGTERM）
  - [ ] 知道怎麼強制 kill（SIGKILL）
  - [ ] 知道怎麼回滾到上一個備份

### 定期檢查（2-6 小時）
- [ ] 每 30 分鐘檢查一次內存使用
  - [ ] 預警值：單個 agent > 1GB
  - [ ] 危險值：單個 agent > 1.5GB
- [ ] 每 30 分鐘檢查一次 task 進度
  - [ ] 應該看到 task 在依賴順序中推進
  - [ ] 不應該看到無進展 > 30 分鐘
- [ ] 每 1 小時檢查一次日誌錯誤
  - [ ] 搜索 `ERROR`, `CRITICAL`, `timeout`
  - [ ] 確認這些是預期的（不是新的故障）

### Timeout 驗證（在達到預期 timeout 時）
- [ ] 在 Task timeout 時：驗證是否自動標記為 timeout
- [ ] 在 Phase timeout 時：驗證是否進行 phase 轉換
- [ ] 在 Global timeout 時：驗證是否全部停止

---

## 📊 Post-Launch Phase（結束後）

### 數據收集
- [ ] 收集所有任務的 findings（JSON）
- [ ] 收集執行時間和資源使用統計
- [ ] 收集所有錯誤日誌
- [ ] 儲存完整的執行紀錄

### 分析
- [ ] 總執行時間 vs. 預期時間（是否符合預算？）
- [ ] 內存使用高峰（是否有洩漏？）
- [ ] Task 完成順序（是否符合依賴關係？）
- [ ] 發生的問題（哪些 timeout? 哪些 agents 卡住？）

### 經驗提取
- [ ] 記錄本次遇到的新問題
- [ ] 記錄本次成功的做法
- [ ] 如果有失敗，根本原因是什麼？
- [ ] 更新本清單（添加新的檢查項）

### 文檔更新
- [ ] 在 AGENT_TEAM_DESIGN_IMPROVEMENTS.md 中記錄經驗
- [ ] 更新 Prompt 模版（如果發現改進空間）
- [ ] 更新 timeout 值（如果估計不準確）

---

## 🎯 Success Criteria

Agent Team 運行成功需要滿足：

✅ **Functional**: 所有 Tasks 在預期時間內完成（或超時但有控制）
✅ **Safe**: 沒有 Agents 無控制地運行超過 1 小時
✅ **Clean**: 運行結束後沒有孤立 agents 或洩漏資源
✅ **Observable**: 執行過程中能實時監控進度和資源
✅ **Recoverable**: 發生故障時有明確的恢復流程

---

**Last Updated**: 2026-03-17
**Version**: v2.1
```

---

## 第四部分：Prompt 編寫模式指南

### ✅ 正確的 Prompt 寫法

#### 結構模版
```markdown
# [Agent Name] — [Team Name]

## 你的角色
一句話描述：_________________

## 你的責任
- Task #X: _________________ (預期時長)
- Task #Y: _________________ (預期時長)
（不超過 3 個 tasks）

## 📅 時間表
```
時間範圍    狀態           具體工作
────────────────────────────────────
X-Yh       IN_PROGRESS    Task #X: ...
                          Task #X: ...
Yh+        WAITING        等待下一 phase（不工作）
           DONE           所有完成
```
```

## 🎯 Task #X 詳細執行

### 輸入檔案
- `/path/to/file1.md`
- `/path/to/file2.md`

### 你的工作
1. 讀上述檔案
2. 執行 3 個檢查：
   - ✅ Check 1: ...
   - ✅ Check 2: ...
   - ✅ Check 3: ...
3. 生成交付物（見下方格式）
4. 標記 Task 完成

### 交付物格式
```json
{
  "task_id": X,
  "status": "completed",
  "findings": {
    "score": 0-100,
    "critical_issues": [
      {"issue": "...", "impact": "HIGH", "location": "...", "fix_hours": 2}
    ],
    "ready_for_next_phase": true
  }
}
```

## ⏹️ 停止條件【CRITICAL】

### 完成 Task #X 後
✅ 你應該：
1. 生成交付物 JSON
2. 標記 Task 完成
3. **立即停止工作**

❌ 絕對不應該：
- ❌ 重新讀文件（即使有疑問）
- ❌ 開始下一個 Task
- ❌ 聯繫其他 Agents
- ❌ 繼續分析或生成額外報告
- ❌ 檢查其他 Tasks 的狀態

你應該進入"待命模式"（idle）：
- 不讀任何檔案
- 不執行任何計算
- 只有一件事：等待 team-lead 的下一個通知

### 如何知道下一個 Phase 開始？
✅ 唯一的信號：
- 你收到來自 team-lead 的明確訊息：`"Phase Y begins. Read Task #Y."`
- 你收到該訊息中其他 Agents 的 findings（JSON 格式）

❌ 不要：
- ❌ 主動檢查是否有新任務
- ❌ 假設時間到了就自動開始
- ❌ 因為沒人阻止你就自作聰明開始

## ⏱️ Timeout 保護
- 你有 **2 小時** 完成 Task #X
- 2 小時後，系統會自動標記為 timeout
- 你不需要監控時間，自動處理

## 與其他 Agents 通訊
**不應主動聯繫**（他們可能在忙）

**唯一例外**：如果發現 CRITICAL 問題（無法工作的）：
- 通知 team-lead：`"CRITICAL: [描述]"`
- Team-lead 會決定是否中斷其他 phases

## 有問題？
- 無法讀檔案 → 通知 team-lead
- 文件模糊 → 標記在 findings JSON 中
- 需要更多時間 → 無需主動延期，timeout 機制會處理

---
**Success**: JSON 交付物 + Task marked completed
**Version**: v2.1-template
```

#### 反例：❌ 不要這樣寫
```markdown
❌ "Review the system and generate a report with findings"
   → 太模糊，Agent 不知道什麼時候完成

❌ "After completing this task, check if other tasks are done
   and then proceed with the next phase"
   → 隱式依賴，Agent 怎麼知道其他 tasks 何時完成？

❌ "Verify everything is correct and ensure alignment"
   → 循環工作，Agent 可以無限期重複檢查

❌ "Generate a comprehensive report with all findings"
   → 無限期的工作，什麼時候算"完整"？

❌ "Contact backend-qa to confirm data layer status"
   → Agent-to-Agent 直接通信，導致不可預測的行為
```

---

### ✅ 正確的 Task 配置

#### 結構模版
```json
{
  "id": 1,
  "subject": "Architecture Review (Task #1)",
  "description": "Review system architecture and API contract consistency",
  "owner": "architect",
  "status": "pending",
  "blockedBy": [],          // ← 明確編碼，我不等任何人
  "blocks": [2, 3],         // ← 明確編碼，我阻擋 Task #2 和 #3
  "timeout_seconds": 7200,  // ← 2 小時
  "deliverable": {
    "type": "json",
    "schema": {
      "task_id": "number",
      "status": "string",
      "findings": {
        "api_schema_alignment_score": "number 0-100",
        "critical_issues": "array",
        "ready_for_backend_qa": "boolean"
      }
    }
  }
}
```

#### 驗證檢查清單
```python
def validate_task(task):
    """驗證 task 設定是否完整"""

    assert task['id'] is not None, "Task must have id"
    assert task['subject'] is not None, "Task must have subject"
    assert isinstance(task['blockedBy'], list), "blockedBy must be a list"
    assert isinstance(task['blocks'], list), "blocks must be a list"
    assert task['timeout_seconds'] > 0, "timeout_seconds must be > 0"
    assert task['status'] in ['pending', 'blocked'], "invalid status"

    # 檢查依賴是否有效
    all_task_ids = get_all_task_ids()
    for dep_id in task['blockedBy']:
        assert dep_id in all_task_ids, f"blockedBy task #{dep_id} doesn't exist"
    for block_id in task['blocks']:
        assert block_id in all_task_ids, f"blocks task #{block_id} doesn't exist"

    # 檢查是否有循環依賴
    assert not has_circular_dependency(task), "Circular dependency detected"

    # 檢查交付物格式是否明確
    assert 'deliverable' in task, "Task must define expected deliverable"
    assert 'schema' in task['deliverable'], "Deliverable must have schema"

    return True
```

---

## 總結

### 核心改進方向
| 方向 | 問題 | 解決方案 |
|------|------|--------|
| 1️⃣ Session 管理 | Parent 死亡 → child 孤立 | 心跳機制 + 自動清理 |
| 2️⃣ 依賴編碼 | 任務分配錯誤 | Task.blockedBy/blocks + Validator |
| 3️⃣ Prompt 清晰度 | Agent 不知何時停止 | 明確停止條件 + 禁止行為列表 |
| 4️⃣ Timeout 實現 | 無法解鎖卡住的 agents | 三層 timeout 機制 + 監控迴圈 |
| 5️⃣ 孤立清理 | 資源洩漏 | 健康檢測 + 自動 kill |

### 實施優先順序
1. **✅ 優先級 CRITICAL** — 方案 1, 2, 3（影響系統穩定性）
   - 實施時間：1-2 週
   - 驗證時間：1 週

2. **✅ 優先級 HIGH** — 方案 4, 5（防止無限期卡住）
   - 實施時間：1 週
   - 驗證時間：3 天

3. **✅ 優先級 MEDIUM** — 監控儀表板 & 設計清單
   - 實施時間：3 天
   - 可視化和文檔

### 下次 Agent Team 設計的第一步
1. 複製本文件中的"Agent Team 設計清單"
2. 填寫所有 ✅ 檢查項
3. 運行 TaskDependencyValidator
4. 啟動 TimeoutMonitor 和 AgentHealthMonitor
5. 使用標準的 Prompt 模版
6. **不要跳過任何檢查項**

---

**文檔版本**: v2.1-professional-review
**最後更新**: 2026-03-17
**下次審查**: 2026-03-31 或完成第一次成功的 Agent Team 後
**維護者**: Claude Code + System Analysis
