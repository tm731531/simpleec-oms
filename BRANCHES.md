# SimpleEC OMS - 分支管理指南

> 最後更新：2026-02-20
> 所有 feature 分支已創建，團隊可立即開始實作

---

## 📊 分支總覽

```
main (穩定版本)
  ↓ merge PR
implementation (主開發分支)
  ↓
  ├─ chore/shopee-verification-prep ............ Week 1 (Feb 20-28)
  ├─ feature/easy-tier-shopify ................ Week 3 (人員 A)
  ├─ feature/easy-tier-easystore ............. Week 3-4 (人員 B)
  ├─ feature/easy-tier-others ................. Week 5-6
  ├─ feature/shopee-channeljob ............... Week 8 (人員 C)
  ├─ feature/order-upsert-handler ............ Week 8 (人員 D)
  ├─ feature/momo-integration ................ Week 9 (人員 G)
  └─ chore/performance-testing ............... Week 8-9 (人員 F)
```

---

## 🎯 分支詳細說明

### Week 1: 驗證準備

#### **chore/shopee-verification-prep**
- **時間**: Feb 20-28 (今天開始)
- **負責人**: 架構師/Lead 開發
- **目標**: 完成 Shopee Round 1 & 2 驗證
- **交付物**:
  - ✅ SHOPEE_VERIFICATION_CHECKLIST_20260220.md (100% 填寫)
  - ✅ SHOPEE_STATUS_MAPPING.md
  - ✅ SHOPEE_COMPLEXITY_ASSESSMENT.md
  - ✅ 其他 API 文檔整理
- **工作流**:
  ```bash
  git checkout chore/shopee-verification-prep
  # 填寫驗證清單
  git add . && git commit -m "Complete Shopee Round 1 verification"
  # 準備 PR 到 implementation
  ```

---

### Week 3-4: Easy Tier 並行開發

#### **feature/easy-tier-shopify** (人員 A)
- **時間**: Week 3 (Mar 3-9)
- **目標**: Shopify Mode A 實裝
- **任務**:
  - [ ] ShopifyChannelAdapter 實現
  - [ ] ShopifyOrderListHandler 實現
  - [ ] ShopifyReturnListHandler 實現
  - [ ] 單元測試 (> 80%)
  - [ ] 集成測試
- **進度檢查點**: Day 3 (架構完成), Day 5 (完成並測試)
- **工作流**:
  ```bash
  git checkout feature/easy-tier-shopify
  # 實現代碼
  git add . && git commit -m "Implement Shopify ChannelAdapter"
  # 每日推送進度
  git push origin feature/easy-tier-shopify
  ```

#### **feature/easy-tier-easystore** (人員 B)
- **時間**: Week 3-4 (Mar 3-16, 同步進行)
- **目標**: easystore Mode A 實裝（復用 Shopify 模式）
- **任務**:
  - [ ] EasystoreChannelAdapter 實現
  - [ ] 批量 API 處理 (50 orders/call)
  - [ ] 單元測試 + 集成測試
- **進度檢查點**: Day 1 (架構設計), Day 4 (完成)
- **與 Shopify 的依賴**:
  - 參考 Shopify 代碼結構
  - 復用 OrderUpsertHandler 和 Kafka Message

#### **feature/easy-tier-others**
- **時間**: Week 5-6 (Mar 17-30)
- **目標**: Cyberbiz 等其他 Easy Tier 平台
- **任務**:
  - 基於 Cyberbiz Round 1 驗證結果
  - 實現其他 Easy Tier ChannelAdapter
- **依賴**: Cyberbiz API 驗證完成 (Feb 28)

---

### Week 8-9: Hard Tier 實裝

#### **feature/shopee-channeljob** (人員 C)
- **時間**: Week 8 (Apr 7-13, Day 1-3)
- **目標**: Shopee Mode B ChannelJob 實現
- **任務**:
  - [ ] ShopeeOrderListHandler (列表 API)
  - [ ] ShopeeOrderDetailHandler (詳情 API)
  - [ ] Redis hash 去重機制 (SHA256 + TTL)
  - [ ] 單元測試 (> 85%)
- **進度檢查點**:
  - Day 1: ListHandler 架構完成
  - Day 2: DetailHandler + Redis 實現
  - Day 3: 單元測試完成
- **工作流**:
  ```bash
  git checkout feature/shopee-channeljob
  # Day 1
  git add . && git commit -m "Implement ShopeeOrderListHandler"
  git push
  # Day 2
  git add . && git commit -m "Implement ShopeeOrderDetailHandler + Redis dedup"
  git push
  # Day 3
  git add . && git commit -m "Add unit tests for ChannelJob"
  git push
  ```

#### **feature/order-upsert-handler** (人員 D)
- **時間**: Week 8 (Apr 7-13, Day 3-5)
- **目標**: OrderUpsertHandler 核心實現
- **任務**:
  - [ ] OrderUpsertHandler 架構
  - [ ] SKU 查詢邏輯 (channelProductId → Product)
  - [ ] Product 不存在處理
  - [ ] 訂單 INSERT/UPDATE + 事務管理
  - [ ] 幂等性檢查
  - [ ] 單元測試 (> 85%)
- **進度檢查點**:
  - Day 3: 架構 + SKU 查詢
  - Day 4: INSERT/UPDATE + 幂等性
  - Day 5: 完整單元測試
- **與 ChannelJob 的依賴**:
  - 消費 ORDER_UPSERT 消息
  - 使用 OMS Order Schema (來自 ChannelJob)

#### **feature/momo-integration** (人員 G)
- **時間**: Week 9 (Apr 14-18, Day 1-2)
- **目標**: Momo Mode B 快速實現（復用 Shopee 模式）
- **任務**:
  - [ ] Momo Mode 確認 (推測 Mode B)
  - [ ] MomoOrderListHandler (復用 ListHandler 模式)
  - [ ] MomoOrderDetailHandler (復用 DetailHandler 模式)
  - [ ] 單元測試
- **進度檢查點**:
  - Day 1: 架構完成
  - Day 2: 完整實現和測試
- **與 Shopee 的不同**:
  - Momo API 字段映射
  - Status 映射規則 (基於 Momo Round 1 驗證)
- **工作流**:
  ```bash
  git checkout feature/momo-integration
  # 參考 feature/shopee-channeljob 的代碼
  # 快速實現（預期 2-3 天）
  ```

---

### Week 8-9: 測試與性能

#### **chore/performance-testing** (人員 F)
- **時間**: Week 8 Day 5 - Week 9 Day 2
- **目標**: 性能基準測試和優化
- **任務**:
  - [ ] Week 8 Day 5: 負載測試環境準備 (JMeter)
  - [ ] Week 9 Day 1: 基準測試 (10/50/100 orders/min)
  - [ ] Week 9 Day 2: 瓶頸分析 + 優化建議
- **目標指標**:
  - ✅ > 50 orders/min (Mode B, 2x API call)
  - ✅ P99 latency < 5s
  - ✅ 錯誤率 < 0.1%
- **工作流**:
  ```bash
  git checkout chore/performance-testing
  # 準備 JMeter 測試用例
  git add . && git commit -m "Add JMeter performance test suite"
  # 記錄基準測試結果
  git add . && git commit -m "Record baseline performance metrics"
  ```

---

## 🔄 分支工作流程

### 1️⃣ 開始工作

```bash
# 確保本地 implementation 是最新的
git checkout implementation
git pull origin implementation

# 切到對應的 feature 分支
git checkout feature/easy-tier-shopify

# 確保分支是最新的
git pull origin feature/easy-tier-shopify
```

### 2️⃣ 每日提交

```bash
# 編寫代碼
# ... 編輯文件 ...

# 提交更改
git add <files>
git commit -m "具體的提交信息（中文）"

# 推送到遠端
git push origin feature/easy-tier-shopify
```

### 3️⃣ 完成功能

```bash
# 確保代碼已推送
git push origin feature/easy-tier-shopify

# 創建 Pull Request（在 GitHub 上）
# 1. 打開 PR 頁面
# 2. 標題: "Implement Shopify ChannelAdapter"
# 3. 描述: 包含完成項目清單、測試覆蓋率、性能測試結果
# 4. 請求代碼 review（指派架構師）

# 基於 review 反饋修改
# ... 修改代碼 ...
git add . && git commit -m "Address review feedback: ..."
git push origin feature/easy-tier-shopify

# 獲得批准後合併
# → 在 GitHub 點擊「Squash and merge」
# → 合併到 implementation
```

### 4️⃣ 同步最新代碼

```bash
# 如果 implementation 有新的更改
git fetch origin
git rebase origin/implementation feature/easy-tier-shopify
git push -f origin feature/easy-tier-shopify
```

---

## 📋 PR 模板

```markdown
## 描述
[簡短說明這個 PR 做了什麼]

## 完成項目清單
- [x] ShopifyChannelAdapter 實現
- [x] ShopifyOrderListHandler 實現
- [x] 單元測試 (覆蓋率 85%)
- [x] 代碼 review ready

## 測試結果
- 單元測試: PASS (20/20)
- 集成測試: PASS (Shopify sandbox)
- 性能: 平均延遲 < 500ms

## 相關文檔
- docs/PLATFORM_MAPPING.md - OMS Schema
- PLAN/06-EXECUTION-PLAN.md - Week 3 計劃
```

---

## 📊 進度追蹤

### Week 1 (Feb 20-28)
```
chore/shopee-verification-prep
├─ Feb 20: 架構師開始驗證
├─ Feb 23: Round 1 完成
├─ Feb 24-28: Round 2 整理
└─ Feb 28: 合併到 implementation
```

### Week 3-4 (Mar 3-16)
```
feature/easy-tier-shopify (人員 A)         feature/easy-tier-easystore (人員 B)
├─ Mar 3: 架構設計                        ├─ Mar 3: 架構設計（復用 Shopify）
├─ Mar 5: Adapter 實現完成                ├─ Mar 6: Adapter 實現完成
├─ Mar 7: Handler 實現完成                ├─ Mar 8: Handler 實現完成
├─ Mar 8: 測試完成                        ├─ Mar 10: 測試完成
└─ Mar 9: PR 到 implementation            └─ Mar 16: PR 到 implementation
```

### Week 8-9 (Apr 7-18)
```
feature/shopee-channeljob (C)        feature/order-upsert-handler (D)      chore/perf-testing (F)
├─ Apr 7: ListHandler 架構           ├─ Apr 10: 架構完成                    ├─ Apr 11: 環境準備
├─ Apr 9: DetailHandler + Redis      ├─ Apr 11: SKU 查詢實現               ├─ Apr 14: 基準測試
├─ Apr 11: 單元測試完成             ├─ Apr 12: INSERT/UPDATE               ├─ Apr 15: 瓶頸分析
└─ Apr 13: PR 到 implementation      ├─ Apr 13: 幂等性 + 測試完成           └─ Apr 18: 優化建議
                                     └─ Apr 13: PR 到 implementation

feature/momo-integration (G)
├─ Apr 14: 架構設計
├─ Apr 15: ListHandler + DetailHandler
├─ Apr 16: 測試完成
└─ Apr 18: PR 到 implementation
```

---

## 🚨 常見問題

### Q: 我應該何時合併到 implementation？
**A**: 當以下條件滿足時：
- ✅ 所有任務項完成
- ✅ 單元測試覆蓋率 > 85%
- ✅ 代碼 review 批准
- ✅ 集成測試通過
- ✅ 無 merge conflicts

### Q: 如果兩個分支有衝突怎麼辦？
**A**: 先合併早期分支，然後晚期分支基於最新的 implementation rebase：
```bash
git fetch origin
git rebase origin/implementation
# 解決衝突
git push -f origin <your-branch>
```

### Q: 可以直接修改 implementation 嗎？
**A**: **不建議**。所有工作應該在 feature 分支完成，然後通過 PR 合併。這樣可以：
- 保持代碼審查
- 保持清晰的歷史記錄
- 並行開發不互相干擾

---

## 🎯 下一步

1. **今天** (Feb 20):
   ```bash
   git checkout chore/shopee-verification-prep
   # 開始填寫驗證清單
   ```

2. **Feb 24-28**:
   ```bash
   git checkout chore/shopee-verification-prep
   # 完成 Round 2 文檔
   git push && 創建 PR 到 implementation
   ```

3. **Mar 2-3** (Week 3 前):
   ```bash
   # 架構師 review Shopify/easystore 設計
   # 更新 feature/easy-tier-shopify 和 feature/easy-tier-easystore
   ```

4. **Mar 3** (Week 3 開始):
   ```bash
   # 人員 A: git checkout feature/easy-tier-shopify
   # 人員 B: git checkout feature/easy-tier-easystore
   # 並行開發
   ```

---

**祝你實裝順利！** 🚀

如有任何問題，查看 PLAN/06-EXECUTION-PLAN.md 或 docs/README.md
