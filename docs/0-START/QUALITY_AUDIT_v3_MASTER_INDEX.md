# SimpleEC OMS Quality Audit v3 - 完整文件索引 & 執行手冊

**生成日期**: 2026-03-18
**狀態**: ✅ 所有文件就位，可立即執行
**權限配置**: ✅ `/home/tom/ONEEC/simpleec-oms/.claude/settings.json` 已創建

---

## 📋 核心文件清單

### 🎯 必讀文件（按執行順序）

| # | 文件名 | 行數 | 用途 | 優先級 |
|---|--------|------|------|--------|
| 1 | **QUALITY_AUDIT_PLAN_v3.md** | 437 | 完整計畫：4個階段、4個任務、依賴關係 | 🔴 必讀 |
| 2 | **QUALITY_AUDIT_AGENT_PROMPTS.md** | 1,035 | 4個 Agent 的完整 Prompts（複製到 TeamCreate） | 🔴 必讀 |
| 3 | **QUALITY_AUDIT_IMPLEMENTATION_CHECKLIST.md** | 632 | 啟動前驗證清單 + 7個驗證階段 | 🔴 必讀 |
| 4 | **QUALITY_AUDIT_v3_README.md** | - | v3 高層概述和改進重點 | 🟡 參考 |
| 5 | **permission-mode-fix.md** (memory) | - | 根本原因診斷 + 預防措施 | 🔴 必讀 |

### 📚 背景文件（理解設計）

| # | 文件名 | 行數 | 用途 |
|---|--------|------|------|
| 6 | AGENT_TEAM_DESIGN_LESSONS.md | 846 | v1-v4 失敗原因分析 |
| 7 | AGENT_TEAM_DESIGN_IMPROVEMENTS.md | 1,617 | 6個根本改進方向 |
| 8 | QUALITY_AUDIT_PLAN_v2.md | - | v2 原始計畫（對比參考） |
| 9 | QUALITY_AUDIT_GAP_ANALYSIS.md | - | v2→v3 缺口分析 |
| 10 | QUALITY_AUDIT_v3_SUMMARY.txt | - | 簡短總結 |

### 🔧 系統相關文件

| # | 文件名 | 位置 | 用途 |
|---|--------|------|------|
| 11 | settings.json | `/home/tom/ONEEC/simpleec-oms/.claude/settings.json` | ✅ 已創建：bypassPermissions 配置 |
| 12 | CLAUDE.md | `/home/tom/ONEEC/simpleec-oms/CLAUDE.md` | SimpleEC OMS 專案指南 |

---

## 📍 文件位置速查

### 完整路徑列表
```bash
# 核心文件位置
/home/tom/ONEEC/simpleec-oms/docs/
├── QUALITY_AUDIT_PLAN_v3.md                      ← 核心計畫
├── QUALITY_AUDIT_AGENT_PROMPTS.md                ← Agent Prompts
├── QUALITY_AUDIT_IMPLEMENTATION_CHECKLIST.md     ← 啟動檢查
├── QUALITY_AUDIT_v3_README.md                    ← v3 概述
├── QUALITY_AUDIT_v3_SUMMARY.txt                  ← 簡短摘要
├── QUALITY_AUDIT_GAP_ANALYSIS.md                 ← v2→v3 缺口
├── QUALITY_AUDIT_PLAN_v2.md                      ← 參考：v2 計畫
├── AGENT_TEAM_DESIGN_LESSONS.md                  ← 背景：失敗原因
├── AGENT_TEAM_DESIGN_IMPROVEMENTS.md             ← 背景：改進方向
├── AGENT_TEAM_IMPROVEMENTS_EXECUTIVE_SUMMARY.md  ← 背景：摘要

# 權限配置
/home/tom/ONEEC/simpleec-oms/.claude/
└── settings.json                                 ← ✅ 已創建

# 記憶文件
/home/tom/.claude/projects/-home-tom-ONEEC/memory/
├── permission-mode-fix.md                        ← 根本原因診斷
├── MEMORY.md                                     ← 項目狀態更新
```

---

## 🚀 執行路線圖

### Phase 0: 準備（已完成 ✅）

- [x] 診斷根本原因：Permission Mode + Workspace Trust
- [x] 創建 `/home/tom/ONEEC/simpleec-oms/.claude/settings.json`
- [x] 接受 Workspace Trust (執行 `git status`)
- [x] 驗證配置有效

### Phase 1: 啟動 Agent Team

**命令**:
```bash
cd /home/tom/ONEEC/simpleec-oms

# 創建 Team（在當前目錄，繼承 settings.json）
TeamCreate team_name="simpleec-oms-quality-audit-v3"

# 建立 4 個任務（參考 QUALITY_AUDIT_PLAN_v3.md 的 Task 定義）
TaskCreate ... (4 times)
```

**參考文件**: QUALITY_AUDIT_PLAN_v3.md §架構設計原則

### Phase 2: 生成 Agent

**要複製的 Prompts 來源**: QUALITY_AUDIT_AGENT_PROMPTS.md

```bash
# 4 個 Agents
Agent("architect", type="opus", prompt=..., team="simpleec-oms-quality-audit-v3")
Agent("backend-qa", type="opus", prompt=..., team="simpleec-oms-quality-audit-v3")
Agent("frontend-qa", type="opus", prompt=..., team="simpleec-oms-quality-audit-v3")
Agent("team-lead", type="haiku", prompt=..., team="simpleec-oms-quality-audit-v3")
```

**參考文件**: QUALITY_AUDIT_AGENT_PROMPTS.md

### Phase 3: 分配任務並監控

**分配邏輯**:
- Task #1 → architect (無依賴，立即開始)
- Task #2 → backend-qa (blockedBy=[1])
- Task #3 → frontend-qa (blockedBy=[1])
- Task #4 → architect (blockedBy=[2,3])

**監控指標** (見 QUALITY_AUDIT_IMPLEMENTATION_CHECKLIST.md 驗證階段 5-7):
- 第 10 分鐘：第一個 progress log
- 第 20-30 分鐘：第一個交付物
- 第 2 小時：Task #1 完成或 timeout
- 第 4 小時：Task #2 & #3 並行
- 第 6 小時：Task #4 開始
- 第 8 小時：全部完成

---

## 📖 如何使用本索引

### 場景 1: 「我想快速瞭解 v3 是什麼」
1. 讀 **QUALITY_AUDIT_v3_README.md** (5 分鐘)
2. 讀 **QUALITY_AUDIT_v3_SUMMARY.txt** (2 分鐘)
3. 讀本索引的 **執行路線圖** (3 分鐘)

**耗時**: ~10 分鐘

### 場景 2: 「我要準備啟動 v3」
1. 讀 **QUALITY_AUDIT_PLAN_v3.md** 完整版 (20 分鐘)
2. 讀 **QUALITY_AUDIT_AGENT_PROMPTS.md** 並複製到 Agent 定義 (15 分鐘)
3. 使用 **QUALITY_AUDIT_IMPLEMENTATION_CHECKLIST.md** 驗證檢查 (10 分鐘)
4. 執行 TeamCreate → 4 Tasks → 4 Agents

**耗時**: ~45 分鐘準備 + 執行命令

### 場景 3: 「我要理解為什麼會失敗多次」
1. 讀 **/memory/permission-mode-fix.md** (診斷) (10 分鐘)
2. 讀 **AGENT_TEAM_DESIGN_LESSONS.md** (背景) (20 分鐘)
3. 讀 **AGENT_TEAM_DESIGN_IMPROVEMENTS.md** (改進) (20 分鐘)
4. 對比 **QUALITY_AUDIT_PLAN_v2.md** vs **QUALITY_AUDIT_PLAN_v3.md**

**耗時**: ~50 分鐘

### 場景 4: 「執行中遇到問題」
1. 檢查 **QUALITY_AUDIT_IMPLEMENTATION_CHECKLIST.md** 驗證階段 (診斷)
2. 如果卡在 permission 提示 → 檢查 `/home/tom/ONEEC/simpleec-oms/.claude/settings.json`
3. 如果 Agent 不動 → 查看 memory/permission-mode-fix.md 中的「驗證指標」
4. 如果任務依賴混亂 → 回到 QUALITY_AUDIT_PLAN_v3.md 的「四階段」重新檢查

---

## ✅ 啟動前檢查清單

必須全部 ✅ 才能啟動：

- [x] 診斷完成：permission mode 是根本原因
- [x] 預防措施已實施：settings.json 已創建
- [x] 權限配置驗證：`bypassPermissions` 已設定
- [x] Workspace Trust 已接受（執行過命令）
- [ ] 讀過 QUALITY_AUDIT_PLAN_v3.md 完整版
- [ ] 準備好 QUALITY_AUDIT_AGENT_PROMPTS.md 的 4 個 Prompts
- [ ] 準備好 QUALITY_AUDIT_IMPLEMENTATION_CHECKLIST.md 的驗證清單
- [ ] 確認有 4 個 Agents 的預算（~2.8GB RAM）
- [ ] 確認工作目錄是 `/home/tom/ONEEC/simpleec-oms/`

---

## 🔗 快速連結

### 核心執行文件（必讀）
- 📄 [完整計畫](./QUALITY_AUDIT_PLAN_v3.md) — 4 個階段、4 個任務、依賴關係
- 📄 [Agent Prompts](./QUALITY_AUDIT_AGENT_PROMPTS.md) — 複製到 Agent 定義
- 📄 [啟動檢查](./QUALITY_AUDIT_IMPLEMENTATION_CHECKLIST.md) — 7 個驗證階段

### 背景理解文件
- 📄 [v3 概述](./QUALITY_AUDIT_v3_README.md) — 高層改進
- 📄 [v3 簡述](./QUALITY_AUDIT_v3_SUMMARY.txt) — 1 頁摘要
- 📄 [v2→v3 缺口](./QUALITY_AUDIT_GAP_ANALYSIS.md) — 6 個改進點
- 📄 [設計教訓](./AGENT_TEAM_DESIGN_LESSONS.md) — v1-v4 失敗原因
- 📄 [設計改進](./AGENT_TEAM_DESIGN_IMPROVEMENTS.md) — 詳細方案

### 根本原因診斷
- 📄 [Permission Mode Fix](file:///home/tom/.claude/projects/-home-tom-ONEEC/memory/permission-mode-fix.md) — 三層阻塞 + 預防措施

### 權限配置
- 📄 [settings.json](file:///home/tom/ONEEC/simpleec-oms/.claude/settings.json) — ✅ 已創建

---

## 📊 文件依賴關係

```
執行順序：
├─ 根本原因診斷 (permission-mode-fix.md)
│  └─ 設計背景 (LESSONS → IMPROVEMENTS)
│     └─ 計畫文件 (PLAN_v2 → v3)
│        ├─ 缺口分析 (GAP_ANALYSIS)
│        ├─ 完整計畫 (PLAN_v3) ← 啟動前必讀
│        ├─ Agent Prompts (AGENT_PROMPTS) ← 啟動時必備
│        └─ 檢查清單 (CHECKLIST) ← 執行時必參考

讀取優先級：
🔴 必讀（啟動前）:
  1. QUALITY_AUDIT_PLAN_v3.md
  2. QUALITY_AUDIT_AGENT_PROMPTS.md
  3. QUALITY_AUDIT_IMPLEMENTATION_CHECKLIST.md
  4. memory/permission-mode-fix.md

🟡 應讀（理解背景）:
  5. QUALITY_AUDIT_v3_README.md
  6. AGENT_TEAM_DESIGN_LESSONS.md
  7. AGENT_TEAM_DESIGN_IMPROVEMENTS.md

🟢 可選（對比參考）:
  8. QUALITY_AUDIT_PLAN_v2.md
  9. QUALITY_AUDIT_GAP_ANALYSIS.md
```

---

## 💾 文件統計

| 類別 | 文件數 | 總行數 | 大小 |
|------|--------|--------|------|
| **核心執行** | 3 | 2,104 | ~50KB |
| **背景理解** | 5 | ~3,500 | ~80KB |
| **系統配置** | 2 | - | - |
| **合計** | 10 | ~5,600 | ~130KB |

---

## 🎯 下一步

**準備好啟動了嗎?** 選擇一個選項：

### 選項 A: 快速啟動（信任文件）
1. ✅ 已經讀過 PLAN_v3 和 PROMPTS
2. 直接執行 TeamCreate + 4 Tasks + 4 Agents
3. 開始監控

### 選項 B: 標準啟動（完整準備）
1. 📖 按照本索引的場景 2 讀 45 分鐘
2. ✅ 完成啟動前檢查清單
3. 執行 TeamCreate + 任務分配

### 選項 C: 審慎啟動（完整理解）
1. 📖 按照本索引的場景 3 讀 50 分鐘
2. 📖 額外閱讀設計背景文件
3. ✅ 完成啟動前檢查清單
4. 執行 TeamCreate + 任務分配

**我準備好了，讓我開始吧** ← 請告訴我你選擇哪個選項！

---

**文檔版本**: v3.0
**最後更新**: 2026-03-18 01:50 AM UTC+8
**狀態**: ✅ 所有相關文件已收攏，系統就位
