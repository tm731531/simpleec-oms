# v3 Quality Audit - 完整 2 小時學習路線圖

**總耗時**: 120 分鐘（包括實踐應用）
**難度**: 中等（需要理解 4 層系統架構）
**成果**: 完整理解 v3 設計，準備啟動

---

## 📍 你的位置

```
現在: 決定採用完整方式 (Option C)
目標: 2 小時後完全理解 v3，可以啟動
路徑: 遞進式學習 + 實踐驗證
```

---

## ⏱️ 時間分配

```
0:00-0:15   → Phase 1: 根本原因診斷 (15 min)
0:15-0:35   → Phase 2: 設計背景理解 (20 min)
0:35-0:55   → Phase 3: 改進方案詳解 (20 min)
0:55-1:15   → Phase 4: 完整計畫審視 (20 min)
1:15-1:35   → Phase 5: 實施清單驗證 (20 min)
1:35-2:00   → Phase 6: 最終確認 & 啟動準備 (25 min)
```

---

## 📚 Phase 1: 根本原因診斷 (15 分鐘)

**目標**: 理解為什麼 v1-v4 全部失敗

### 讀這個文件
📄 `/home/tom/.claude/projects/-home-tom-ONEEC/memory/permission-mode-fix.md`

### 關鍵問題
1. **為什麼 Agent 卡住？** — Permission mode + Workspace trust
2. **三層阻塞機制是什麼？** — Trust prompt → Permission prompt → Missing config
3. **為什麼設計改進無法幫助？** — 解決了錯誤的問題層級

### 學習檢查點
- [ ] 理解三層阻塞的區別
- [ ] 知道為什麼過往設計改進沒用
- [ ] 理解「預防措施」已經實施

### 5 分鐘速記
```
問題: Agent 在第一個工具調用時卡住（看不到終端無法回應）
層次 1: Workspace Trust Prompt 需要互動確認 ✅ 已預防
層次 2: Tool Permission 在 default mode 需要確認 ✅ 已預防
層次 3: 無項目級別 settings.json ✅ 已預防

結論: 根本問題已解決，現在可以談論「執行品質」
```

---

## 📚 Phase 2: 設計背景理解 (20 分鐘)

**目標**: 理解 v1-v4 的失敗史和 v3 如何避免

### A. 失敗教訓 (10 分鐘)
📄 `/home/tom/ONEEC/simpleec-oms/docs/AGENT_TEAM_DESIGN_LESSONS.md`

**讀這部分**:
- §Root Cause Analysis: 4 次失敗分類
- §Critical Design Principles（拾遺）

**關鍵概念**:
```
v1 失敗: Task 協調混亂
v2 失敗: 隱式依賴，timeout 無細節
v3 失敗: Session 監視缺失
v4 失敗: Permission mode（已解決 ✅）

→ v3 的設計改進針對 v1-v3 的教訓
```

### B. 設計改進方案 (10 分鐘)
📄 `/home/tom/ONEEC/simpleec-oms/docs/AGENT_TEAM_DESIGN_IMPROVEMENTS.md`

**讀這部分**:
- §Part 1: 6 個根本改進方向（標題 + 摘要）
- §Part 2: 實施細節（快速掃過）

**6 大改進**:
```
#1 顯式依賴編碼       → Task.blockedBy/blocks
#2 清晰停止條件       → Prompt 添加禁止清單
#3 Session 監視       → Heartbeat + 孤立檢測
#4 Timeout 實現       → 30 分鐘檢查邏輯
#5 Agent 協調         → 標準化消息格式
#6 交付物格式         → JSON Schema 定義
```

### 學習檢查點
- [ ] 知道 v1-v4 為什麼失敗（大類別）
- [ ] 理解 6 大改進如何對應失敗原因
- [ ] 認識 v3 的核心策略

---

## 📚 Phase 3: 改進方案詳解 (20 分鐘)

**目標**: 理解 v2 vs v3 的具體差異

### A. GAP 分析 (10 分鐘)
📄 `/home/tom/ONEEC/simpleec-oms/docs/QUALITY_AUDIT_GAP_ANALYSIS.md`

**讀這部分**:
- §Summary: 6 個缺陷分類（CRITICAL/HIGH/MEDIUM）
- 跳過詳細説明，只看摘要

**6 個缺陷速覽**:
```
缺陷 #1 (CRITICAL): 隱式依賴 → 編碼為 JSON
缺陷 #2 (CRITICAL): 無停止條件 → 添加禁止清單
缺陷 #3 (CRITICAL): 無 Session 監視 → 30s heartbeat
缺陷 #4 (HIGH):     Timeout 無細節 → 30min 檢查
缺陷 #5 (HIGH):     Agent 協調隱式 → 標準化流程
缺陷 #6 (MEDIUM):   交付物格式粗略 → JSON Schema
```

### B. v2 vs v3 對照 (10 分鐘)
📄 `/home/tom/ONEEC/simpleec-oms/docs/QUALITY_AUDIT_v3_SUMMARY.txt`

**讀這部分**:
- §v2 → v3 的改進矩陣（表格）
- §核心改進點（完整）
- §實施優先級

**改進矩陣簡化版**:
```
v2                          →  v3 (改進)
─────────────────────────────────────────
結構: Sequential ✓          →  保留 ✓
依賴: 隱式（文字）          →  🔴 顯式（JSON）
Prompt: 基本                →  🔴 添加停止條件
Timeout: 說了沒細節        →  🔴 30min 檢查邏輯
Session: 無監視             →  🟢 Heartbeat + 孤立檢測
協調: 隱式                   →  🔴 標準化流程
交付物: 粗略                 →  🔴 詳細 Schema
```

### 學習檢查點
- [ ] 明白 6 個缺陷各是什麼
- [ ] 知道每個缺陷的改進方案
- [ ] 理解改進的優先級（馬上做 vs 後續驗證）

---

## 📚 Phase 4: 完整計畫審視 (20 分鐘)

**目標**: 理解 v3 的具體執行計畫

### A. 計畫概覽 (5 分鐘)
📄 `/home/tom/ONEEC/simpleec-oms/docs/QUALITY_AUDIT_PLAN_v3.md`

**讀這部分**:
- §核心設計原則
- §【四階段順序執行】的標題和簡介

**4 個階段快速速記**:
```
Phase 1 (Architect, Task #1):  架構層驗證（2h）
  ├─ 檢查：11 模塊邊界、16 Kafka Topics、API-Schema 對齊
  ├─ 交付：JSON with critical_issues, p2_issues, ready_for_backend_qa
  └─ 依賴：blockedBy=[], blocks=[2,3]

Phase 2 (Backend-QA, Task #2): 數據層驗證（2h）
  ├─ 檢查：19 表、Kafka Topics、Handler Registry
  ├─ 交付：JSON with schema_integrity, kafka_config, data_risk
  └─ 依賴：blockedBy=[1], blocks=[4]

Phase 3 (Frontend-QA, Task #3): 前端層驗證（2h）
  ├─ 檢查：User App 6 頁、Admin App、API 整合
  ├─ 交付：JSON with completeness %, api_integration correctness
  └─ 依賴：blockedBy=[1], blocks=[4]

Phase 4 (Architect, Task #4):  端到端整合（2h）
  ├─ 檢查：三層發現是否衝突、數據流完整性、critical 問題
  ├─ 交付：JSON with overall_system_health, recommendations
  └─ 依賴：blockedBy=[2,3], blocks=[]
```

### B. 同步機制 & Timeout 保護 (5 分鐘)
📄 同一文件，§【同步機制 & Timeout 保護】

**關鍵點**:
```
Timeline:
  H0    H2    H4    H6    H8
  ├─────┤
  [Task 1]|timeout
        ├─────┤
        [Task 2]|timeout
        ├─────┤
        [Task 3]|timeout
              ├──────────┤
              [Task 4: Integration]

Timeout 規則:
- 每個 Task 最多 2 小時
- 超時自動標記 timeout，不阻止下一階段
- Team-lead 每 30 min 檢查一次
- Timeout ≠ 失敗，只是狀態轉遷
```

### C. Agent 分配與 Prompt 要求 (5 分鐘)
📄 同一文件，§【Agent 分配 & 配置】

**Agent 配置**:
```
architect    | Opus 4.6  | Task #1 & #4 (架構決策)
backend-qa   | Opus 4.6  | Task #2 (數據驗證)
frontend-qa  | Opus 4.6  | Task #3 (UI 驗證)
team-lead    | Haiku 4.5 | 協調 + 監控
```

**Prompt 必須包含**:
1. 責任清晰度 ✓
2. 停止條件明確 ✓
3. 依賴關係編碼 ✓
4. 超時保護 ✓
5. Agent 間協調 ✓

### 學習檢查點
- [ ] 理解 4 個階段的次序和依賴
- [ ] 知道每個 Task 的輸入和輸出
- [ ] 明白 Timeout 的實現邏輯
- [ ] 認識 4 個 Agent 的角色分配

---

## 📚 Phase 5: 實施清單驗證 (20 分鐘)

**目標**: 理解啟動前的檢查和啟動流程

### A. 啟動前檢查 (10 分鐘)
📄 `/home/tom/ONEEC/simpleec-oms/docs/QUALITY_AUDIT_IMPLEMENTATION_CHECKLIST.md`

**讀這部分**:
- §Pre-Launch Verification (5 大類別）
- 跳過詳細檢查項，只看類別和摘要

**5 大檢查類別**:
```
1️⃣ Environmental Setup
   - 正確的工作目錄
   - Settings 已配置
   - 資源充足

2️⃣ Task Design & Dependency
   - 4 個 Task 已建立
   - blockedBy/blocks 已設定
   - 無循環依賴

3️⃣ Prompt Quality
   - 4 個 Prompts 已複製
   - 都有停止條件
   - Team-lead 有監視邏輯

4️⃣ Final Readiness Check
   - 配置驗證
   - 文件驗證
   - 權限驗證

5️⃣ Launch Workflow
   - Step 1: 驗證前置
   - Step 2: 建立 Team
   - Step 3: 建立 Tasks
   - Step 4: 生成 Agents
   - Step 5: 分配並啟動
```

### B. 監控指標 (5 分鐘)
📄 同一文件，§Monitoring Metrics & KPIs

**綠燈指標**（系統正常）:
```
✅ 無 "Enter to confirm" 提示
✅ 無 permission_request pending
✅ Agent 日誌持續更新
✅ T+10min 出現 progress.log
✅ T+30min 出現交付物 JSON
```

**紅燈指標**（需要介入）:
```
❌ Agent 卡住 > 5 min 無日誌
❌ 出現 permission_request
❌ 出現 "denied permission"
❌ 任何 tmux 窗格卡住
```

### 學習檢查點
- [ ] 知道啟動前的 5 大檢查類別
- [ ] 認識監控的綠燈和紅燈指標
- [ ] 理解啟動的 5 個步驟

---

## 📚 Phase 6: 最終確認 & 啟動準備 (25 分鐘)

**目標**: 準備立即啟動

### A. Agent Prompts 快速審視 (10 分鐘)
📄 `/home/tom/ONEEC/simpleec-oms/docs/QUALITY_AUDIT_AGENT_PROMPTS.md`

**讀這部分**:
- §Prompt #1: Architect Agent（完整 Prompt）
- §Prompt #4: Team-Lead Agent（完整 Prompt）

**關鍵點**:
```
Architect Prompt:
  ✅ 有「2 個大階段」的說明
  ✅ 有「Stop Conditions」的明確指示
  ✅ 交付物格式是 JSON Schema

Team-Lead Prompt:
  ✅ 有「30 秒 heartbeat」邏輯
  ✅ 有「30 分鐘 timeout 檢查」邏輯
  ✅ 有「孤立 Agent 檢測」邏輯
  ✅ 有「自動解鎖」邏輯
```

### B. 最終檢查清單 (10 分鐘)

**你現在應該確認**:

| 項目 | 檢查 |
|------|------|
| **根本原因** | ✅ 理解 Permission mode 是根本原因 |
| **預防措施** | ✅ settings.json 已創建 |
| **4 大改進** | ✅ 依賴編碼、停止條件、Session 監視、Timeout 邏輯 |
| **4 個階段** | ✅ 架構 → 數據 → 前端 → 整合 |
| **4 個 Agents** | ✅ Architect, Backend-QA, Frontend-QA, Team-Lead |
| **4 個 Tasks** | ✅ Task #1-4，blockedBy/blocks 已設定 |
| **Prompts** | ✅ 4 份 Prompts，包含停止條件和監視邏輯 |
| **檢查清單** | ✅ 5 大類別，35+ 檢查項 |
| **監控指標** | ✅ 知道綠燈和紅燈指標 |
| **資源** | ✅ 8.9GB RAM 充足（需 2.8GB） |

### C. 啟動準備 (5 分鐘)

**下一步**:
1. 確認以上 10 項檢查全部 ✅
2. 選擇「立即啟動」或「再讀一遍某個部分」
3. 告訴我你已準備好

**如果有任何不確定**:
- 不確定 Phase X → 重新讀該 Phase 的文件
- 不確定具體步驟 → 查看 FILES_MANIFEST.txt 中的「QUICK LINKS」

---

## ✨ 你現在應該理解

### 層級 1: 為什麼 v1-v4 失敗
```
根本原因: Permission Mode 卡死（已解決 ✅）
上層原因: 設計缺陷 → v3 解決 6 個缺陷
```

### 層級 2: v3 的核心策略
```
4 個階段 Sequential Phase
      ↓
依賴明確編碼 (blockedBy/blocks)
      ↓
Prompt 有停止條件
      ↓
Team-Lead 自動監視和解鎖
      ↓
預期 6-8 小時完成，成功率 ≥ 75%
```

### 層級 3: 具體的執行細節
```
Task #1 (2h) → Task #2 & #3 (2h 並行) → Task #4 (2h)
      ↑                    ↑
    Timeout                Timeout
   自動標記              自動解鎖
   不阻止下一個        下一個繼續
```

### 層級 4: 實施驗證
```
啟動前: 5 大檢查類別
執行中: 綠燈 / 紅燈指標
完成後: 評估系統健康度
```

---

## 🎯 現在該做什麼？

### 你已完成
- ✅ 理解根本原因和預防措施
- ✅ 理解 v1-v4 的失敗史
- ✅ 理解 v3 的 6 大改進
- ✅ 理解 4 階段的執行計畫
- ✅ 理解啟動的檢查清單
- ✅ 理解監控的指標

### 下一步選項

**A) 立即啟動**
```
我已經理解所有內容，現在就開始建立 Team。
```

**B) 再詳細檢查某個部分**
```
我想再讀一遍 [Phase X] 的 [某個部分]
```

**C) 實踐演練**
```
先讓我模擬一下啟動流程，確保我知道該按什麼順序執行命令
```

---

## 📖 如果你需要快速查詢

| 我想... | 查看... |
|--------|--------|
| 知道根本原因 | memory/permission-mode-fix.md |
| 理解設計背景 | AGENT_TEAM_DESIGN_LESSONS.md |
| 看具體改進 | AGENT_TEAM_DESIGN_IMPROVEMENTS.md |
| 查完整計畫 | QUALITY_AUDIT_PLAN_v3.md |
| 找啟動檢查 | QUALITY_AUDIT_IMPLEMENTATION_CHECKLIST.md |
| 複製 Prompts | QUALITY_AUDIT_AGENT_PROMPTS.md |
| 快速總結 | QUALITY_AUDIT_v3_SUMMARY.txt |
| 全面導航 | docs/0-START/QUALITY_AUDIT_v3_MASTER_INDEX.md |

---

## 時間到！⏰

**你花了約 2 小時，現在：**

✅ 理解為什麼 v1-v4 全部失敗
✅ 知道 v3 如何解決這些問題
✅ 可以講出 4 個階段、4 個 Agent、6 大改進
✅ 認識監控指標，知道什麼是正常 vs 異常
✅ 準備啟動

---

## 🚀 我準備好了！

請告訴我以下任一個：

1. **「我已經完全理解，現在開始啟動」**
   → 我會步步引導你執行 TeamCreate, TaskCreate, Agent 生成

2. **「我要再確認一下 [具體部分]」**
   → 告訴我是哪部分，我為你詳細解釋

3. **「我要演練一下啟動流程」**
   → 我會模擬一遍，讓你看清楚每個步驟

---

**檔案位置**: `/home/tom/ONEEC/simpleec-oms/.claude/COMPLETE_LEARNING_PATH.md`
**生成時間**: 2026-03-18 02:00 AM UTC+8
**狀態**: ✅ 就位，等待你的下一個決定
