# User App 設計文檔

**日期**: 2026-02-21
**狀態**: 已批准
**目標**: 商家業務數據管理平台 + 非同步平台同步系統

---

## 1. 整體架構

### 1.1 系統流程

```
用戶操作（修改賣場數量/價格等）
  ↓
Vue3 前端 (白色系UI)
  ↓ Optimistic UI 更新 (50 → 80 同步中...)
Spring Boot API
  ↓ 發送事件到 Kafka
  ├─ SHOPEE_SELLPACK_TOPIC
  ├─ MOMO_SELLPACK_TOPIC
  └─ PCHOME_SELLPACK_TOPIC (各通路各自的 topic)
  ↓
Channel Job (各自消費)
  ├─ Shopee Job: 呼叫 Shopee API
  ├─ MOMO Job: 呼叫 MOMO API
  └─ PChome Job: 呼叫 PChome API
  ↓
更新數據庫 + 回報狀態
  ↓
UI 輪詢刷新 (每 5 分鐘)
  ↓
顯示最終狀態 (✓ 已同步 @14:32:15)
```

### 1.2 技術棧

- **前端框架**: Vue 3 + Vite 5
- **UI 組件**: Element Plus
- **狀態管理**: Pinia
- **路由**: Vue Router
- **HTTP 客戶端**: Axios
- **認證**: JWT Token (localStorage 存儲)
- **數據刷新**: 5 分鐘輪詢 + Optimistic Update
- **通訊**: 相對路徑 API (/api/user)，由 Cloudflare 反向代理轉發

---

## 2. 數據模型

### 2.1 Product（商品）— 簡單

```typescript
interface Product {
  id: string
  sku: string
  name: string
  quantity: number        // 總庫存
  price: number
  image: string
  description: string
  status: 'active' | 'inactive'
  createdAt: string
  updatedAt: string
}
```

### 2.2 SellPack（賣場）— 複雜，包含同步狀態

```typescript
interface SellPack {
  id: string
  productId: string       // 基於哪個商品
  platformId: string      // 屬於哪個通路（動態取得）
  platformName: string    // 通路名稱（"Shopee", "MOMO" 等）

  // 當前狀態
  quantity: number
  price: number
  image: string
  description: string
  listingStatus: 'active' | 'inactive'

  // 操作追蹤（核心特性）
  syncStatus: {
    status: 'pending' | 'syncing' | 'completed' | 'failed'
    operation: 'QUANTITY_UPDATE' | 'PRICE_UPDATE' | 'LISTING_UPDATE' | 'IMAGE_UPDATE' | 'DESC_UPDATE'
    oldValue?: any
    newValue?: any
    startTime?: string
    completedTime?: string
    error?: string
    retryCount: number
  }

  createdAt: string
  updatedAt: string
  lastSyncAt?: string
}

// UI 呈現格式
// 原數量: 80
// 新數量: 100 (+20)
// 狀態: ⏳ 同步中 (已等待 2:34)
```

### 2.3 Order（訂單）— 含同步狀態

```typescript
interface Order {
  id: string
  orderNumber: string
  platform: string        // 來自哪個通路
  totalAmount: number
  status: 'pending' | 'confirmed' | 'shipped' | 'completed' | 'cancelled'

  // 同步追蹤（出貨、取消操作）
  syncStatus: {
    operation: 'SHIPMENT' | 'CANCEL'
    status: 'pending' | 'syncing' | 'completed' | 'failed'
    startTime?: string
    completedTime?: string
    error?: string
  }

  createdAt: string
  updatedAt: string
}
```

### 2.4 Refund（退貨）— 含同步狀態

```typescript
interface Refund {
  id: string
  orderId: string
  platform: string
  amount: number
  reason: string

  syncStatus: {
    status: 'pending' | 'syncing' | 'completed' | 'failed'
    startTime?: string
    completedTime?: string
    error?: string
  }

  createdAt: string
  updatedAt: string
}
```

---

## 3. 需要同步的 Fast 操作清單

### 3.1 SellPack Operations
- [x] 數量更新 → QUANTITY_UPDATE
- [x] 價格更新 → PRICE_UPDATE
- [x] 上架/下架 → LISTING_UPDATE
- [x] 圖片更新 → IMAGE_UPDATE
- [x] 說明更新 → DESC_UPDATE

### 3.2 Order Operations
- [x] 出貨 (Shipment) → SHIPMENT
- [x] 取消 (Cancel) → CANCEL

### 3.3 Refund Operations
- [x] 退貨所有操作 → REFUND_*

---

## 4. UI 模塊結構 (8 大頁面)

### 4.1 登入頁面 (LoginPage)
- 郵箱 + 密碼表單
- 獲取 JWT Token
- 存儲到 localStorage + Pinia auth store

### 4.2 儀表板 (DashboardPage)
- 訂單統計卡片 (總數、待出貨、已完成)
- 營收統計
- 通路分布圖表
- 最近操作日誌

### 4.3 商品管理 (ProductPage)
- 商品列表 (CRUD)
- 搜尋 + 分頁
- 無同步狀態（商品本身不需要同步）

### 4.4 通路管理 (ChannelPage)
- 已連接通路列表
- 通路賬號配置
- 同步日誌

### 4.5 賣場管理 (SellPackPage) ⭐ 核心
- 賣場列表
- 每個賣場顯示:
  ```
  SKU: ABC-001 | 商品名稱
  原數量: 80 → 新數量: 100 (+20)
  原價格: $50 → 新價格: $55 (+$5)
  狀態: ⏳ 同步中... (2:34) 或 ✓ 已同步 @14:32:15
  操作: [編輯] [重試] [日誌]
  ```
- 批量操作（批量更新數量/價格）
- 實時操作歷史

### 4.6 訂單管理 (OrderPage)
- 訂單列表（按平台/時間篩選）
- 訂單詳情
- 出貨狀態 + 同步追蹤
- 取消訂單 (含同步)
- 訂單日誌

### 4.7 出貨管理 (ShipmentPage)
- 待出貨訂單列表
- 批量出貨
- 物流追蹤
- 出貨狀態同步

### 4.8 退貨管理 (RefundPage)
- 退貨列表
- 退貨流程管理
- 退款處理
- 退貨同步狀態

---

## 5. 關鍵特性

### 5.1 動態通路管理
- **無硬編碼通路**: 通路列表從 API 動態獲取
- **靈活擴展**: 新增通路時自動支援

### 5.2 同步狀態追蹤系統 ⭐
- **Optimistic UI**: 操作後立即顯示目標值 + 同步中標示
- **實時時間戳**: 顯示操作時間 + 已等待時間
- **平台級粒度**: 每個操作對應各個通路的獨立 Job
- **失敗重試**: 失敗時允許手動/自動重試
- **操作歷史**: 每個操作都有完整審計軌跡

### 5.3 UI 風格
- **白色系**: 區別於 Admin App（藍色系）
- **專業感**: 適合商家操作的業務系統
- **響應式設計**: 支援各種屏幕尺寸

### 5.4 數據刷新策略
- **5 分鐘輪詢**: 自動刷新所有頁面數據
- **用戶手動刷新**: 按鈕刷新單個列表
- **WebSocket 預留**: 架構支持日後升級到實時推送

---

## 6. API 交互流程示例

### 操作: 更新賣場數量 80 → 100

```
[前端]                          [後端 API]              [Kafka]           [Job]
  |                                |                     |                |
用戶輸入 100 (Optimistic Update)
  |
顯示 80 → 100 (+20) [同步中...]
  |
發送 PUT /api/user/sellpack/{id}
  ├── quantity: 100               ←|
                                   |← 驗證
                                   |← 發送 3 個事件:
                                   |   SHOPEE_SELLPACK_UPDATE
                                   |   MOMO_SELLPACK_UPDATE      →| Shopee Job |→ Shopee API
                                   |   PCHOME_SELLPACK_UPDATE    →| MOMO Job   |→ MOMO API
                                   |                              →| PChome Job |→ PChome API
                                   |← 立即返回 201 OK
                                   |  (syncStatus: pending)
  |← 響應 (立即返回)
  |
輪詢 GET /api/user/sellpack/{id}
  ├─ 首次: syncStatus.status = "syncing"
  ├─ 再次: syncStatus.status = "completed"
  |         syncStatus.completedTime = "2026-02-21T14:32:15Z"
  |
UI 更新為 ✓ 已同步 @14:32:15
```

---

## 7. 實施優先級

**Phase 1 (MVP)**:
1. LoginPage + 認證系統
2. DashboardPage (基本統計)
3. ProductPage (簡單 CRUD)
4. SellPackPage (含同步追蹤) ⭐

**Phase 2**:
5. OrderPage + ShipmentPage
6. RefundPage
7. ChannelPage

**Phase 3**:
8. 高級功能 (批量操作、WebSocket、圖表優化)

---

## 8. 技術債務 & 優化方向

- [ ] WebSocket 升級（日後替代輪詢）
- [ ] 同步狀態緩存（減少 API 調用）
- [ ] 離線模式支援
- [ ] 多語言支援

---

**設計完成**: 2026-02-21 14:45 UTC
**批准人**: User
**下一步**: 執行 writing-plans 建立實施計畫
