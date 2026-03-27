# 動態狀態選項設計文檔

**日期**：2026-02-25
**主題**：前端狀態篩選選項動態與後端 Enum 同步
**狀態**：已批准

## 概述

前端的訂單狀態篩選選項（OrderTable.vue、ShipmentTable.vue 等）目前硬編碼，導致新增狀態時需要修改前端代碼。本設計通過創建專用的 Enum API 端點，讓前端動態獲取完整的狀態列表，與後端 OrderStatusEnum 保持一致。

## 問題陳述

- 前端狀態選項硬編碼在 Vue 組件中（el-option）
- 後端 OrderStatusEnum 新增或修改狀態時，前端無法自動同步
- 前端和後端的狀態列表容易出現不一致

## 解決方案

### 1. 後端 API 端點

**新增公開端點**：`GET /api/enums/order-statuses`

**無需認證**（公開）

**響應格式**：
```json
{
  "data": [
    {
      "code": "PENDING",
      "label": "待支付",
      "description": "訂單待支付"
    },
    {
      "code": "CONFIRMED",
      "label": "已確認",
      "description": "訂單已確認"
    },
    {
      "code": "READY_TO_SHIP",
      "label": "待出貨",
      "description": "訂單準備出貨"
    },
    {
      "code": "SHIPPING",
      "label": "出貨中",
      "description": "訂單出貨中"
    },
    {
      "code": "SHIPPED",
      "label": "已出貨",
      "description": "訂單已出貨"
    },
    {
      "code": "COMPLETED",
      "label": "已完成",
      "description": "訂單已完成"
    },
    {
      "code": "CANCELLED",
      "label": "已取消",
      "description": "訂單已取消"
    }
  ]
}
```

**實現方式**：
- 後端在 `OrderStatusEnum.java` 添加 `getLabel()` 方法（中文標籤）
- 創建 EnumController，路由 `/api/enums/order-statuses`
- 使用流式處理從 enum 動態構建響應

### 2. 前端實現

**創建 Composable**：`hooks/useOrderStatuses.ts`
- 在應用初始化時調用一次（app.ts 或 main.ts）
- 結果存儲到全局狀態（Pinia store 或 provide/inject）

**更新組件**：
- OrderTable.vue
- ShipmentTable.vue
- Dashboard.vue
- OrderPage.vue
- ShipmentPage.vue
- RefundTable.vue

使用動態綁定替換硬編碼的 `<el-option>`：

```vue
<el-select v-model="selectedStatus" placeholder="篩選狀態">
  <el-option
    v-for="status in orderStatuses"
    :key="status.code"
    :label="status.label"
    :value="status.code"
  />
</el-select>
```

### 3. 數據流

```
OrderStatusEnum（後端）
  ↓ (GET /api/enums/order-statuses)
  ↓
API 端點
  ↓ (HTTP Response)
  ↓
前端 Composable (useOrderStatuses)
  ↓ (inject/provide 或 Pinia)
  ↓
Vue 組件 (OrderTable, ShipmentTable, ...)
  ↓ (動態綁定)
  ↓
el-select options
```

## 優勢

✅ **自動同步**：後端 enum 改變時，前端自動生效（無需修改代碼）
✅ **流式處理**：不硬編碼，動態從 enum 生成，符合"流處理"理念
✅ **完整集合**：顯示所有可能的狀態，即使當前沒有該狀態的訂單
✅ **易於維護**：單一來源（後端 enum），避免不一致
✅ **多語言支持**：標籤由後端決定，可輕鬆支持多語言

## 實施步驟

### 後端
1. 在 OrderStatusEnum 添加 `getLabel()` 方法
2. 創建 EnumController 和相應的 endpoint
3. 單元測試驗證端點返回正確數據

### 前端
1. 創建 hooks/useOrderStatuses.ts
2. 在 main.ts 初始化調用（使用 Pinia store 或 provide）
3. 更新所有 6 個使用狀態篩選的組件
4. 移除硬編碼的狀態選項
5. 測試動態選項是否正確顯示

## 測試計畫

- [ ] 後端端點返回 7 個狀態
- [ ] 前端成功調用 API 並獲得數據
- [ ] OrderTable.vue 顯示動態選項
- [ ] ShipmentTable.vue 顯示動態選項
- [ ] 其他組件正常運行
- [ ] 篩選功能正常工作
- [ ] 在線添加新狀態時前端自動更新

## 相關文件

- `OrderStatusEnum.java` - 後端狀態枚舉
- `EnumController.java` - 新增 API 控制器（待創建）
- `hooks/useOrderStatuses.ts` - 前端 Composable（待創建）
- `OrderTable.vue`、`ShipmentTable.vue` 等 - 前端組件（待更新）
