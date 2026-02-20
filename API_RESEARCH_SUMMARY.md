# SimpleEC OMS — 平台 API 研究摘要

> 基於 `/home/tom/ONEEC/ONEEC/drive-download-20241121T142851Z-001/參考資料/API文件/` 的實際 API 文檔
> 更新日期：2026-02-20
> 文檔時間點：2024 年 11 月

---

## 1. 支持的平台列表（17 個）

### 1.1 台灣主要電商平台

| 排序 | 平台 | 文檔狀態 | API 版本 | 複雜度 | 優先級 |
|------|------|--------|---------|--------|--------|
| **1** | **MOMO** | ✅ 完整（多版本） | 2024/09 最新 | 中 | **MVP** |
| **2** | **PChome** | ✅ 完整 | EC 廠商 API | 中 | **MVP** |
| **3** | **Cyberbiz** | ✅ 資料夾 | 不詳 | 低-中 | **MVP** |
| **4** | **蝦皮 Shopee** | ⚠️ 基本 | API 網址 | 高 | **MVP** |
| **5** | **露天 Ruten** | ✅ 完整（多版本） | 2024/07 最新 | 中 | Phase 2 |
| **6** | **Yahoo 購物中心** | ⚠️ 部分 | ProductAttributes | 中 | Phase 2 |
| **7** | **Yahoo 超級商城** | ✅ 資料夾 | 不詳 | 中 | Phase 2 |
| **8** | **樂天 Rakuten** | ✅ 完整 | Open API v2 | 中 | Phase 2 |

### 1.2 SaaS / 自建平台

| 平台 | 文檔狀態 | API 版本 | 複雜度 | 優先級 |
|------|--------|---------|--------|--------|
| **Shopify** | ✅ 資料夾 | GraphQL + REST | 高 | Phase 3 |
| **Shopline** | ✅ 完整 + 流程說明 | REST | 中 | Phase 2 |
| **EasyStore** | ✅ 資料夾 | 不詳 | 低-中 | Phase 3 |
| **91APP** | ✅ 完整（4 個 API） | REST | 中-高 | Phase 2 |

### 1.3 小眾 / 國際平台

| 平台 | 文檔狀態 | 備註 | 優先級 |
|------|--------|------|--------|
| **博客來** | ✅ 資料夾 | 台灣最大書店 | Phase 3 |
| **東森** | ✅ 資料夾 | 開路電視購物 | Phase 3 |
| **Friday** | ✅ 資料夾 | 遠傳購物 | Phase 3 |
| **iOpenMall** | ✅ 資料夾 | 亞洲 B2B2C | Phase 3 |
| **韓國 Coupang** | ✅ 資料夾 | 國際擴展用 | Phase 4+ |

---

## 2. MVP 推薦平台（優先順序）

### 2.1 為什麼選這 4 個？

```
可行性評估：
  ✅ Cyberbiz  — API 最簡潔，完美測試用例
  ✅ MOMO      — 台灣銷量最高，多版本文檔
  ✅ PChome    — 台灣第二大，API 清楚
  ✅ Shopee    — 新興主力，但 API 複雜需要投入

不在 MVP 中（Phase 2+）：
  ⏳ 露天、Yahoo、樂天 — 市場佔有率下降
  ⏳ Shopify   — 跨境複雜，GraphQL 需要學習
  ⏳ 91APP     — 自建用戶，量級小
```

### 2.2 MVP 工作量重新估算

```
原估計（5 個平台，8 週）：
  Phase 3 (Weeks 4-6)：Momo + Shopee + Cyberbiz

新估計（實際 17 個平台）：
  Phase 3 (Weeks 4-6)：Cyberbiz (基準) + MOMO + PChome
  Phase 2 Extension：Shopee (複雜，額外投入)

改進方案：
  ✅ Phase 3：Cyberbiz + MOMO (2 週)
  ✅ Phase 4：PChome + Shopee (2 週)
  → 總計 4 週 (原 3 週)
```

---

## 3. 各平台 API 特性總結

### 3.1 MOMO（台灣最大）

```
文檔：MOMO API文件_20240925/ （最新版本）
複雜度：中
特點：
  ✅ 訂單 API 完整
  ✅ 配送狀態定義清楚 (SCM訂單配送狀態定義及說明.pdf)
  ✅ 有 Sample.xlsx (MOMO_Sample.xlsx)
  ✅ 多版本文檔（2021-2024），API 在演變

關鍵字段：
  - orderID, orderId（名稱不一致）
  - productID / productName
  - buyerInfo（配送地址）
  - paymentInfo
  - orderStatus

建議：
  → 第 2 個 Adapter（先做 Cyberbiz）
  → 注意版本差異（2024 最新 vs 舊版本）
```

### 3.2 PChome（台灣第二大）

```
文檔：PChome EC轉單廠商 API/
複雜度：中
特點：
  ✅ 專門為廠商設計
  ✅ 單一格式（不像 MOMO 多版本）

關鍵點：
  → 可能無規格概念（見原 ADAPTER_TESTING_STRATEGY.md）
  → 訂單結構簡化

建議：
  → 第 3 個 Adapter
  → 作為「簡化版」API 測試用例
```

### 3.3 Cyberbiz（推薦作 MVP 基準）

```
文檔位置：cyberbiz/
複雜度：低-中（相對）
特點：
  ✅ 規格完整但相對簡潔
  ✅ 適合作為「規範」測試用例
  ✅ 設計優於其他平台（推測）

建議：
  → 第 1 個實現（Week 1-2）
  → 作為架構驗證的基準
  → 後續平台按照 Cyberbiz 模式調整
```

### 3.4 蝦皮 Shopee（高複雜度）

```
文檔：API網址與相關資料.docx （文檔不完整）
複雜度：高
特點：
  ⚠️ 文檔最少（僅網址）
  ⚠️ 複雜的規格映射系統
  ⚠️ 可能需要自行從 API 文檔網站抓取

已知問題（見 ADAPTER_TESTING_STRATEGY.md）：
  - tier_variation 複雜
  - 動態定價
  - 銷售屬性多維

建議：
  → 不在 MVP 中
  → 作為 Phase 2 的第一個挑戰
  → 預留額外時間（2+ 週）
```

### 3.5 露天 Ruten（衰退平台）

```
文檔：Ruten API 廠商串接規格書_* （多版本）
複雜度：中
版本：
  - 訂單 API：2024/06/30 最新
  - 商品 API：2024/07/03 最新

特點：
  ✅ 文檔完整（與 MOMO 同等）
  ⚠️ 平台衰退，市場佔有率下降

建議：
  → Phase 2（不在 MVP）
  → 優先順序：露天 < Yahoo < 樂天
```

### 3.6 Shopline（SaaS 電商）

```
文檔：Shopline/ （API 文檔 + 流程說明 txt）
複雜度：中
特點：
  ✅ 詳細的流程說明（步驟 1-12）
  ✅ 出貨、取貨、退貨完整流程
  ✅ 訂單狀態變遷清楚

特殊性：
  - 自建平台（非多商家市集）
  - 物流整合更深（步驟文檔詳細）

建議：
  → Phase 2（自建平台專用 Adapter）
  → 架構不同（單一店鋪 vs 多商家）
```

### 3.7 91APP（多功能電商）

```
文檔：91APP/ （4 個 API 文檔）
複雜度：中-高
API 種類：
  1. 會員 API
  2. 商店資料 API
  3. 點數系統 API
  4. 商店模組 API

特點：
  ⚠️ 功能數量多（不只訂單）
  ⚠️ 需要整合會員系統

建議：
  → Phase 2（自建平台）
  → 量級小，優先順序較低
```

---

## 4. API 複雜度分類

### 4.1 簡單 (Easy) — Adapter 1 週內完成

```
特徵：
  - 訂單結構平面化
  - 規格字段少
  - 無複雜的狀態轉換

平台：
  ✅ Cyberbiz（推薦基準）
  ✅ PChome（簡化版）
  ✅ EasyStore （推測）
```

### 4.2 中等 (Medium) — Adapter 2 週完成

```
特徵：
  - 訂單結構層級多（商品、配送、支付）
  - 規格有簡單映射
  - 多版本 API 要維護

平台：
  ✅ MOMO （推薦第 2 個）
  ✅ Shopline （自建但結構清楚）
  ✅ 露天、Yahoo、樂天
```

### 4.3 複雜 (Hard) — Adapter 3+ 週

```
特徵：
  - 複雜的規格系統（多維度）
  - 動態定價
  - 複雜的訂單狀態機
  - 文檔不完整

平台：
  ⚠️ Shopee （推薦作為學習挑戰）
  ⚠️ Shopify （GraphQL + REST）
  ⚠️ 91APP （多 API 整合）
```

---

## 5. 實施建議

### 5.1 重新規劃的 MVP 時間表

```
Week 1：基礎設施 (不變)
Week 2-3：Handlers (不變)
Week 4-5：Cyberbiz Adapter ← 從簡單開始
Week 6-7：MOMO Adapter ← 台灣主力
Week 8：PChome Adapter ← 簡化版驗證
Week 9-10：Shopee Adapter ← 複雜挑戰（額外）

或者（保留 8 週）：
Week 1：基礎設施
Week 2-3：Handlers
Week 4：Cyberbiz (集中做)
Week 5：MOMO
Week 6：PChome
Week 7-8：整合 + 優化 + Shopee preview
```

### 5.2 文件優先級

| 優先級 | 立即讀 | 原因 |
|--------|--------|------|
| **P0** | MOMO_Sample.xlsx | 實際訂單格式 |
| **P0** | Cyberbiz API 文檔 | MVP 基準 |
| **P0** | MOMO API文件_20240925 | 最新格式 |
| **P1** | PChome EC API | 第三個 Adapter |
| **P1** | Shopline 流程說明 | 自建平台學習 |
| **P2** | Shopee API 網址 | 長期規劃 |

### 5.3 測試固定值來源

```
有現成的 fixture 可用：
  ✅ MOMO_Sample.xlsx → 轉換為 JSON fixtures
  ⚠️ 其他平台 → 需要自行建立（或從 API sandbox）

建議：
  1. 解析 MOMO_Sample.xlsx，提取實際訂單格式
  2. 轉換為 JSON fixtures/momo/*.json
  3. 作為 Unit Tests 的基礎資料
  4. 其他平台依照模式建立
```

---

## 6. 架構調整建議

### 6.1 現有 IMPLEMENTATION_PLAN.md 需更新

```
修改項：
  ❌ Phase 3 計劃改為：Cyberbiz → MOMO → PChome
  ✅ 工作量重新估算（Shopee 延後到 Phase 2）
  ✅ 建議增加「API 文檔研究」為 Phase 1 的一部分
```

### 6.2 新增建議：API 研究週期

```
Phase 0（準備週，Week 0）：
  1. 整理所有 17 個平台的 API 文檔
  2. 建立 API 對應表（字段映射）
  3. 分類平台複雜度
  4. 選擇 MVP 平台（3-4 個）

好處：
  ✅ 避免中途發現 API 變化導致返工
  ✅ 預估工作量更準確
  ✅ 團隊對全景有認識
```

---

## 7. 文檔位置參考

```bash
# 本地 API 文檔根目錄
API_ROOT="/home/tom/ONEEC/ONEEC/drive-download-20241121T142851Z-001/參考資料/API文件/各家API/已做的"

# MVP 平台文檔
CYBERBIZ="${API_ROOT}/cyberbiz"
MOMO="${API_ROOT}/MOMO"
PCHOME="${API_ROOT}/PChome EC轉單廠商 API"

# 查看 MOMO Sample
open "${MOMO}/MOMO_Sample.xlsx"

# 查看最新 MOMO API
open "${MOMO}/MOMO API文件_20240925"
```

---

## 8. 下一步行動

### 立即（今天）

```bash
# 1. 複製 API 文檔到項目
cp -r "${API_ROOT}" ./api-research/

# 2. 檢查 Cyberbiz API 文檔
ls -la ./api-research/cyberbiz/

# 3. 提取 MOMO 樣本資料
python3 extract_momo_sample.py ./api-research/MOMO/MOMO_Sample.xlsx
```

### 本週

```
□ 評估 17 個平台的實施成本
□ 更新 IMPLEMENTATION_PLAN.md（新時間表）
□ 建立 API 對應表（字段映射）
□ 確認 MVP 平台是 Cyberbiz + MOMO + PChome
```

### 計劃開始前

```
□ 取得 Shopee API 文檔（目前只有網址）
□ 確認各平台沙盒環境 API 金鑰可用性
□ 決定是否要 Phase 0（API 研究週）
```

---

**文件日期**：2026-02-20
**基於實際資源**：2024 年 11 月的平台 API 文檔
**狀態**：待 Team Review
