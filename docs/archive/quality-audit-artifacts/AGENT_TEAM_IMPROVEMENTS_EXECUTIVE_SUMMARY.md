# Agent Team 設計改進方案 — 執行總結

**完整文檔**: `/home/tom/ONEEC/simpleec-oms/docs/AGENT_TEAM_DESIGN_IMPROVEMENTS.md` (1616 行)

---

## 🎯 一句話總結

SimpleEC OMS 的 4 次 Agent Team 失敗（3/3 ~ 3/17）都源於**系統層、Task 層、Agent 層的根本設計缺陷**，而非代碼問題。本文提供完整的診斷和可行的解決方案。

---

## 📊 4 次失敗的根本原因（不是症狀）

| 失敗 | 日期 | 真正的根本原因 | 表面症狀 |
|------|------|---|---|
| 1️⃣ simpleec-oms-audit | 3/3 | Task 依賴只寫文字，沒有編碼 | Agent 互相等待死鎖 |
| 2️⃣ bidirectional-audit | 3/10 | Prompt 沒有明確停止條件 | Agent 無限期讀文件，內存爆 |
| 3️⃣ quality-audit | 3/16 | 沒有 timeout 機制 + 沒有健康檢測 | 3 個 agents 空轉 24h，OOM |
| 4️⃣ quality-audit-v2 | 3/17 | 沒有 session 生命週期管理 | Parent 死亡 → child 孤立 |

---

## 🔴 5 個關鍵缺陷

### 1. **Session 生命週期管理缺失** [CRITICAL]
- ❌ Parent session 死亡 → child agents 不知道
- ❌ 沒有心跳（heartbeat）機制
- ❌ 沒有自動清理孤立 agents 的方法

### 2. **隱式依賴關係編碼** [CRITICAL]
- ❌ Task 依賴只寫在文檔裡，沒有在 `Task.blockedBy/blocks` 中編碼
- ❌ 任務分配完全依賴人工判斷
- ❌ 無法自動檢測任務分配錯誤

### 3. **Prompt 清晰度不足** [CRITICAL]
- ❌ 沒有明確的"停止條件"
- ❌ Agent 完成任務後不知道應該停止
- ❌ 持續讀文件 → 內存增長 → OOM

### 4. **Timeout 機制不完整** [HIGH]
- ❌ 提到了 timeout，但沒有實現計劃
- ❌ 不清楚誰檢測 timeout、怎麼檢測
- ❌ Timeout 後的自動解鎖流程不清楚

### 5. **孤立 Agent 無法清理** [HIGH]
- ❌ 沒有"卡住檢測"機制
- ❌ 沒有自動 kill 功能
- ❌ 資源持續洩漏

---

## ✅ 5 個改進方案

### 方案 1️⃣：Session 生命週期管理
**解決**：Parent 死亡 → child 孤立

**方案內容**：
- 实现 `.heartbeat` 文件機制（Parent 每 30 秒更新）
- Child agents 每 1 分鐘檢查 parent 是否還活著
- Parent 死亡時自動執行清理流程
- Team-lead 持續監控所有 agents 的健康狀態

**實施難度**：⭐⭐ 中等 | **預期時間**：3-5 天

---

### 方案 2️⃣：顯式依賴編碼
**解決**：任務分配錯誤、分配邏輯複雜

**方案內容**：
- 強制為每個 Task 填寫 `blockedBy` 和 `blocks`
- 實現 `TaskDependencyValidator`（啟動前驗證）
- 禁止分配被 `blockedBy` 的任務
- Task 完成時自動解鎖被它 block 的任務

**實施難度**：⭐ 簡單 | **預期時間**：2-3 天

**好處**：
```
之前：
  Task 分配給誰？
  → 讀一遍文檔，人工判斷... → 常常出錯

之後：
  if task.blockedBy 都完成 and owner == null:
      可以分配給任何 Agent
  else:
      拒絕分配（TaskSystem 自動檢查）
```

---

### 方案 3️⃣：Prompt 清晰度——明確停止條件
**解決**：Agent 無法判斷何時停止，持續消耗資源

**方案內容**：
- 使用標準 Prompt 樣板（包含時間表、停止條件）
- 明確列出"❌ 絕對不應該做的事"
- 交付物必須是結構化 JSON（不是自由文本）
- 明確"如何接收下一個 phase 的通知"

**實施難度**：⭐⭐ 中等 | **預期時間**：2 天

**樣板包含**：
```markdown
## ⏹️ 停止條件【CRITICAL】

完成 Task #X 後：
✅ 生成交付物 JSON
✅ 標記 Task 完成
✅ 立即停止（進入待命模式）

❌ 絕對不應該：
  ❌ 重新讀文件
  ❌ 開始下一 Task
  ❌ 聯繫其他 Agents
  ❌ 生成額外報告
  ❌ 持續分析
```

---

### 方案 4️⃣：Timeout 機制完整實現
**解決**：無法自動解鎖卡住的 agents

**方案內容**：
- **三層 timeout**：
  - Task 層：2 小時
  - Phase 層：4 小時
  - Global 層：8 小時
- **自動監控迴圈**：Team-lead 每 30 秒檢查一次
- **自動解鎖**：Task timeout 時自動解鎖被它 block 的任務
- **自動通知**：Timeout 發生時通知 team-lead

**實施難度**：⭐⭐ 中等 | **預期時間**：3-4 天

**效果**：
```
之前：Agent 卡 24+ 小時，沒人知道
之後：
  - 2 小時後自動標記 timeout
  - 自動解鎖 Task #2, #3 等
  - Team-lead 收到通知
  - 8 小時後全部 stop，不會無限期運行
```

---

### 方案 5️⃣：孤立 Agent 自動清理
**解決**：資源洩漏、OOM

**方案內容**：
- **健康檢測**：每 60 秒檢查一次 agent 健康
- **卡住檢測**：3 個條件
  - 無活動超過 1 小時
  - 內存增長 > 20% / 30min
  - Task 超過 3 小時還在 in_progress
- **自動清理**：卡住時自動 kill agent + 清理資源
- **實時儀表板**：可視化監控所有 agents 的狀態

**實施難度**：⭐⭐ 中等 | **預期時間**：3 天

---

## 📋 Agent Team 設計清單

在啟動任何新 Agent Team 前，必須完成以下檢查：

### Pre-Launch（啟動前）
- [ ] Task 依賴驗證（運行 TaskDependencyValidator）
- [ ] Prompt 檢查（包含停止條件和交付物格式）
- [ ] Timeout 值計算（Task/Phase/Global）
- [ ] 監控機制準備（Heartbeat, Health check, Dashboard）

### Launch（啟動時）
- [ ] 所有 Tasks 的 blockedBy/blocks 已填寫
- [ ] 每個 Agent 的 Prompt 已完整編寫（不是來自文檔參考）
- [ ] TimeoutMonitor 已啟動
- [ ] AgentHealthMonitor 已啟動
- [ ] 備份機制已準備好

### Post-Launch（運行中）
- [ ] 每 5 分鐘查看日誌和任務進度
- [ ] 每 30 分鐘檢查內存使用
- [ ] 預備應急措施（怎麼快速 kill all agents）

### 完整清單文檔
見：`AGENT_TEAM_DESIGN_IMPROVEMENTS.md` §第三部分

---

## 🚀 實施路線圖

### Week 1: 方案 1, 2, 3（CRITICAL）
- [ ] 實現 Session heartbeat 機制
- [ ] 實現 Task 依賴編碼和驗證
- [ ] 編寫 Prompt 樣板和停止條件

**時間投入**：2-3 天開發 + 2 天測試

### Week 2: 方案 4, 5（HIGH）
- [ ] 實現三層 timeout 機制
- [ ] 實現 agent 健康檢測和自動清理
- [ ] 實現監控儀表板

**時間投入**：2 天開發 + 1 天測試

### Week 3: 驗證和文檔
- [ ] 啟動第一個新的 Agent Team（用改進後的設計）
- [ ] 完整記錄執行過程
- [ ] 更新設計清單（基於經驗）

---

## 📚 如何使用這份文檔

### 1. **設計新 Agent Team 時** 📋
   複製 `AGENT_TEAM_DESIGN_IMPROVEMENTS.md` §第三部分的設計清單
   逐項檢查，不要跳過任何一項

### 2. **編寫 Prompt 時** ✍️
   參考 §第四部分的 Prompt 樣板
   直接複製樣板，填入具體內容

### 3. **啟動 Team 時** 🚀
   執行 §第四部分的 Prompt 編寫模式
   使用標準的 Task 配置格式

### 4. **運行過程中遇到問題** 🔧
   查看 §第一部分的根本原因分析
   了解可能的原因，應用對應的方案

### 5. **完成後改進** 📈
   在 §第三部分的設計清單中添加新的檢查項
   更新 Prompt 樣板（如有發現更好的做法）

---

## 📞 關鍵文檔交叉參考

| 文檔 | 用途 |
|------|------|
| `AGENT_TEAM_DESIGN_IMPROVEMENTS.md` | 完整的改進方案（本文檔的完整版本） |
| `AGENT_TEAM_DESIGN_LESSONS.md` | 之前失敗中提煉的教訓（背景文檔） |
| `QUALITY_AUDIT_PLAN_v2.md` | 最後一次失敗的設計文檔（參考） |

---

## 🎯 成功標準

下一次 Agent Team 運行成功的標誌：

✅ **功能性**：所有 Tasks 在預期時間內完成（或有控制地超時）
✅ **安全性**：沒有 Agents 無控制地運行超過 1 小時
✅ **清潔性**：運行結束後沒有孤立 agents 或資源洩漏
✅ **可觀測性**：執行過程中能實時監控進度和資源
✅ **可恢復性**：發生故障時有明確的恢復流程

---

## 📝 最後的話

這 4 次失敗不是浪費——它們是寶貴的學習機會。本文檔將這些教訓編碼成**系統性的改進方案**，確保下一次 Agent Team 不會重複同樣的錯誤。

**最重要的是**：不要跳過任何檢查項。設計清單存在的原因就是為了防止遺漏。

---

**文檔版本**: v1.0-executive-summary
**生成日期**: 2026-03-17
**完整文檔**: `/home/tom/ONEEC/simpleec-oms/docs/AGENT_TEAM_DESIGN_IMPROVEMENTS.md`
