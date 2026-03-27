# 動態訂單狀態選項實現計畫

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**目標**：讓前端訂單狀態篩選選項從後端 OrderStatusEnum 動態獲取，自動與後端同步。

**架構**：
- 後端新增公開 REST API 端點 `/api/enums/order-statuses`，從 OrderStatusEnum 動態生成完整狀態列表
- 前端創建 Composable (useOrderStatuses.ts) 初始化時調用該端點，儲存到 Pinia store
- 所有使用狀態篩選的組件（OrderTable.vue、ShipmentTable.vue 等）替換硬編碼選項為動態綁定

**技術棧**：
- 後端：Java Spring Boot 3.5, Spring Web
- 前端：Vue 3, TypeScript, Pinia, Element Plus
- API：REST

---

## 後端實現

### Task 1：在 OrderStatusEnum 添加 getLabel() 方法

**文件**：
- Modify: `simpleec-common/src/main/java/com/simpleec/common/enums/OrderStatusEnum.java`

**步驟 1：檢查現有 OrderStatusEnum 結構**

讀取文件：
```
cat simpleec-common/src/main/java/com/simpleec/common/enums/OrderStatusEnum.java
```

預期：看到 7 個狀態常量（PENDING, CONFIRMED, READY_TO_SHIP, SHIPPING, SHIPPED, COMPLETED, CANCELLED）

**步驟 2：添加中文標籤和 getLabel() 方法**

在 enum 中添加：
```java
PENDING("PENDING", "待支付", "訂單待支付"),
CONFIRMED("CONFIRMED", "已確認", "訂單已確認"),
READY_TO_SHIP("READY_TO_SHIP", "待出貨", "訂單準備出貨"),
SHIPPING("SHIPPING", "出貨中", "訂單出貨中"),
SHIPPED("SHIPPED", "已出貨", "訂單已出貨"),
COMPLETED("COMPLETED", "已完成", "訂單已完成"),
CANCELLED("CANCELLED", "已取消", "訂單已取消");

private final String code;
private final String label;
private final String description;

OrderStatusEnum(String code, String label, String description) {
    this.code = code;
    this.label = label;
    this.description = description;
}

public String getLabel() {
    return label;
}

public String getDescription() {
    return description;
}
```

**步驟 3：編譯驗證**

```bash
cd /home/tom/ONEEC/simpleec-oms
./gradlew simpleec-common:compileJava
```

預期：BUILD SUCCESSFUL

**步驟 4：提交**

```bash
git add simpleec-common/src/main/java/com/simpleec/common/enums/OrderStatusEnum.java
git commit -m "feat: Add label and description to OrderStatusEnum"
```

---

### Task 2：創建 EnumController API 端點

**文件**：
- Create: `simpleec-api/src/main/java/com/simpleec/api/controller/EnumController.java`
- Create: `simpleec-api/src/main/java/com/simpleec/api/vo/StatusOptionVO.java`

**步驟 1：創建 StatusOptionVO 數據傳輸對象**

```java
// simpleec-api/src/main/java/com/simpleec/api/vo/StatusOptionVO.java
package com.simpleec.api.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class StatusOptionVO {
    private String code;
    private String label;
    private String description;
}
```

**步驟 2：創建 EnumController**

```java
// simpleec-api/src/main/java/com/simpleec/api/controller/EnumController.java
package com.simpleec.api.controller;

import com.simpleec.api.vo.StatusOptionVO;
import com.simpleec.common.enums.OrderStatusEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/enums")
public class EnumController {

    /**
     * 獲取所有訂單狀態選項
     * 無需認證（公開端點）
     */
    @GetMapping("/order-statuses")
    public Map<String, Object> getOrderStatuses() {
        log.debug("Fetching order status options");

        List<StatusOptionVO> statuses = Arrays.stream(OrderStatusEnum.values())
            .map(status -> new StatusOptionVO(
                status.getCode(),
                status.getLabel(),
                status.getDescription()
            ))
            .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("data", statuses);

        log.debug("Returning {} order statuses", statuses.size());
        return response;
    }
}
```

**步驟 3：編譯驗證**

```bash
./gradlew simpleec-api:compileJava
```

預期：BUILD SUCCESSFUL

**步驟 4：測試端點（啟動應用後）**

```bash
curl -s http://localhost:8082/api/enums/order-statuses | jq .
```

預期：
```json
{
  "data": [
    {
      "code": "PENDING",
      "label": "待支付",
      "description": "訂單待支付"
    },
    ...
  ]
}
```

**步驟 5：提交**

```bash
git add simpleec-api/src/main/java/com/simpleec/api/controller/EnumController.java
git add simpleec-api/src/main/java/com/simpleec/api/vo/StatusOptionVO.java
git commit -m "feat: Add /api/enums/order-statuses endpoint"
```

---

## 前端實現

### Task 3：創建 useOrderStatuses Composable

**文件**：
- Create: `user-app/src/composables/useOrderStatuses.ts`

**步驟 1：創建 Composable**

```typescript
// user-app/src/composables/useOrderStatuses.ts
import { ref, onMounted } from 'vue'
import { useOrderStore } from '../stores/order'

export interface StatusOption {
  code: string
  label: string
  description: string
}

export function useOrderStatuses() {
  const orderStore = useOrderStore()
  const loading = ref(false)
  const error = ref<string | null>(null)

  const fetchStatuses = async () => {
    if (orderStore.statuses.length > 0) {
      // 已載入，無需重新取得
      return
    }

    loading.value = true
    error.value = null

    try {
      const response = await fetch('/api/enums/order-statuses')
      if (!response.ok) {
        throw new Error(`Failed to fetch statuses: ${response.statusText}`)
      }

      const data = await response.json()
      orderStore.setStatuses(data.data as StatusOption[])
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Unknown error'
      console.error('Error fetching order statuses:', err)
    } finally {
      loading.value = false
    }
  }

  onMounted(() => {
    fetchStatuses()
  })

  return {
    statuses: () => orderStore.statuses,
    loading,
    error,
  }
}
```

**步驟 2：在 Pinia store 中添加狀態管理**

編輯 `user-app/src/stores/order.ts`（如果不存在則創建）：

```typescript
// user-app/src/stores/order.ts
import { defineStore } from 'pinia'
import { ref } from 'vue'

export interface StatusOption {
  code: string
  label: string
  description: string
}

export const useOrderStore = defineStore('order', () => {
  const statuses = ref<StatusOption[]>([])

  const setStatuses = (newStatuses: StatusOption[]) => {
    statuses.value = newStatuses
  }

  return {
    statuses,
    setStatuses,
  }
})
```

**步驟 3：在 main.ts 初始化 useOrderStatuses**

編輯 `user-app/src/main.ts`，在應用掛載時調用：

```typescript
// 在 app.mount() 之前添加
import { useOrderStatuses } from './composables/useOrderStatuses'
const { } = useOrderStatuses()
```

或更好的方式是在 App.vue 的 setup 中調用：

```vue
<script setup lang="ts">
import { useOrderStatuses } from '@/composables/useOrderStatuses'

// 應用啟動時載入狀態
useOrderStatuses()
</script>
```

**步驟 4：npm install 驗證（如果有新依賴）**

```bash
cd user-app
npm install
```

**步驟 5：提交**

```bash
git add src/composables/useOrderStatuses.ts
git add src/stores/order.ts
git commit -m "feat: Add useOrderStatuses composable and order store"
```

---

### Task 4：更新 OrderTable.vue

**文件**：
- Modify: `user-app/src/components/OrderTable.vue:35-39`（硬編碼選項）
- Modify: `user-app/src/components/OrderTable.vue:script setup`（導入 composable）

**步驟 1：在 script setup 中導入 useOrderStatuses**

添加到 `<script setup lang="ts">` 部分：

```typescript
import { useOrderStatuses } from '@/composables/useOrderStatuses'

const { statuses } = useOrderStatuses()
```

**步驟 2：替換硬編碼的 el-option**

舊代碼（行 35-39）：
```vue
<el-option label="全部狀態" value="" />
<el-option label="待支付" value="PENDING" />
<el-option label="待出貨" value="READY_TO_SHIP" />
<el-option label="出貨中" value="SHIPPING" />
<el-option label="已出貨" value="SHIPPED" />
<el-option label="已完成" value="COMPLETED" />
<el-option label="已取消" value="CANCELLED" />
```

新代碼：
```vue
<el-option label="全部狀態" value="" />
<el-option
  v-for="status in statuses()"
  :key="status.code"
  :label="status.label"
  :value="status.code"
/>
```

**步驟 3：驗證編譯**

```bash
cd user-app
npm run build
```

預期：編譯成功

**步驟 4：提交**

```bash
git add src/components/OrderTable.vue
git commit -m "feat: Use dynamic status options in OrderTable"
```

---

### Task 5：更新 ShipmentTable.vue

**步驟 1-4**：與 Task 4 相同，但針對 `ShipmentTable.vue`

**提交**：
```bash
git add src/components/ShipmentTable.vue
git commit -m "feat: Use dynamic status options in ShipmentTable"
```

---

### Task 6：更新 Dashboard.vue

**步驟 1-4**：與 Task 4 相同，但針對 `Dashboard.vue`（可能有多個狀態篩選）

**提交**：
```bash
git add src/views/Dashboard.vue
git commit -m "feat: Use dynamic status options in Dashboard"
```

---

### Task 7：更新 OrderPage.vue

**步驟 1-4**：與 Task 4 相同，但針對 `OrderPage.vue`

**提交**：
```bash
git add src/views/OrderPage.vue
git commit -m "feat: Use dynamic status options in OrderPage"
```

---

### Task 8：更新 ShipmentPage.vue

**步驟 1-4**：與 Task 4 相同，但針對 `ShipmentPage.vue`

**提交**：
```bash
git add src/views/ShipmentPage.vue
git commit -m "feat: Use dynamic status options in ShipmentPage"
```

---

### Task 9：驗證所有更新

**步驟 1：編譯完整前端**

```bash
cd user-app
npm run build
```

預期：BUILD SUCCESSFUL，無 TypeScript 錯誤

**步驟 2：啟動應用並測試**

```bash
# 後端
cd /home/tom/ONEEC/simpleec-oms
./gradlew clean build -x test

# Docker 啟動
docker compose up -d --build

# 等待容器啟動
sleep 30

# 測試 API
curl -s http://localhost:8082/api/enums/order-statuses | jq .
```

預期：
- API 返回 7 個狀態
- 前端訪問 http://localhost:8080 正常加載
- OrderTable 等組件的狀態篩選下拉列表正常顯示動態選項

**步驟 3：測試篩選功能**

在瀏覽器中：
1. 打開 OrderTable 頁面
2. 點擊狀態篩選下拉菜單
3. 驗證顯示：待支付、已確認、待出貨、出貨中、已出貨、已完成、已取消
4. 選擇一個狀態，驗證篩選功能正常

**步驟 4：提交**

```bash
git add -A
git commit -m "test: Verify dynamic status options across all components"
```

---

## 最終驗證清單

- [ ] OrderStatusEnum 有 getLabel() 和 getDescription()
- [ ] EnumController 返回正確的 JSON 格式
- [ ] useOrderStatuses Composable 成功載入狀態
- [ ] Pinia store 正確儲存狀態
- [ ] OrderTable.vue 顯示動態選項
- [ ] ShipmentTable.vue 顯示動態選項
- [ ] Dashboard.vue 顯示動態選項
- [ ] OrderPage.vue 顯示動態選項
- [ ] ShipmentPage.vue 顯示動態選項
- [ ] 篩選功能正常工作
- [ ] 無硬編碼狀態選項
- [ ] 所有組件編譯成功
- [ ] API 端點無需認證即可訪問

---

## 相關文件

- 設計文檔：`docs/plans/2026-02-25-dynamic-status-options-design.md`
- OrderStatusEnum：`simpleec-common/src/main/java/com/simpleec/common/enums/OrderStatusEnum.java`
- EnumController：`simpleec-api/src/main/java/com/simpleec/api/controller/EnumController.java`
- useOrderStatuses：`user-app/src/composables/useOrderStatuses.ts`
- 受影響的 Vue 組件：OrderTable.vue、ShipmentTable.vue、Dashboard.vue、OrderPage.vue、ShipmentPage.vue

