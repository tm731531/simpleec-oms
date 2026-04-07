<template>
  <div>
    <h2>訂單管理</h2>
    <el-card>
      <OrderTable :refresh="refreshCount" @ship="handleShip" @cancel="handleCancel" @view="handleView" />
    </el-card>

    <!-- 訂單詳情 Dialog -->
    <el-dialog v-model="detailVisible" title="訂單詳情" width="700px">
      <div v-if="detailLoading" v-loading="true" style="height: 200px" />
      <template v-else-if="detailOrder">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="訂單編號">{{ detailOrder.orderNumber }}</el-descriptions-item>
          <el-descriptions-item label="通路訂單號">{{ detailOrder.channelOrderId }}</el-descriptions-item>
          <el-descriptions-item label="通路">{{ detailOrder.platform }}</el-descriptions-item>
          <el-descriptions-item label="狀態">
            <el-tag :type="getStatusType(detailOrder.status)">{{ getStatusText(detailOrder.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="訂單金額">{{ detailOrder.totalAmount }}</el-descriptions-item>
          <el-descriptions-item label="運費">{{ detailOrder.shippingFee ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="折扣">{{ detailOrder.discountAmount ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="付款方式">{{ detailOrder.paymentMethod ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="配送方式">{{ detailOrder.shippingMethod ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="付款時間">{{ formatTime(detailOrder.paidAt) }}</el-descriptions-item>
          <el-descriptions-item label="出貨時間">{{ formatTime(detailOrder.shippedAt) }}</el-descriptions-item>
          <el-descriptions-item label="通路建立時間">{{ formatTime(detailOrder.channelCreatedAt) }}</el-descriptions-item>
        </el-descriptions>

        <el-divider>買家資訊</el-divider>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="姓名">{{ detailOrder.buyerName ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="電話">{{ detailOrder.buyerPhone ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="Email">{{ detailOrder.buyerEmail ?? '-' }}</el-descriptions-item>
        </el-descriptions>

        <template v-if="detailOrder.items && (detailOrder.items as any[]).length">
          <el-divider>商品明細</el-divider>
          <el-table :data="detailOrder.items as any[]" size="small">
            <el-table-column prop="sku" label="SKU" width="120" />
            <el-table-column prop="productName" label="商品名稱" />
            <el-table-column prop="variantName" label="規格" width="100" />
            <el-table-column prop="quantity" label="數量" width="60" />
            <el-table-column prop="unitPrice" label="單價" width="90" />
            <el-table-column label="小計" width="90">
              <template #default="{ row }">{{ row.subtotal ?? calcSubtotal(row) }}</template>
            </el-table-column>
          </el-table>
        </template>

        <template v-if="statusLogs.length">
          <el-divider>狀態紀錄</el-divider>
          <el-timeline>
            <el-timeline-item
              v-for="log in statusLogs"
              :key="log.id"
              :timestamp="formatTime(log.createdAt)"
            >
              {{ log.fromStatus }} → {{ log.toStatus }}
              <span v-if="log.note" style="color:#909399; margin-left:8px">{{ log.note }}</span>
            </el-timeline-item>
          </el-timeline>
        </template>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Order } from '../types'
import { orderAPI } from '../api/order'
import OrderTable from '../components/OrderTable.vue'

const refreshCount = ref(0)
const detailVisible = ref(false)
const detailLoading = ref(false)
const detailOrder = ref<any>(null)
const statusLogs = ref<any[]>([])

async function handleShip(order: Order) {
  ElMessageBox.prompt('請輸入物流單號', '出貨', {
    confirmButtonText: '確定',
    cancelButtonText: '取消'
  }).then(async ({ value }) => {
    try {
      await orderAPI.ship(order.id, value)
      ElMessage.success('出貨已送出同步')
      refreshCount.value++
    } catch (error) {
      ElMessage.error('出貨失敗')
    }
  }).catch(() => {})
}

async function handleCancel(order: Order) {
  ElMessageBox.confirm('確定要取消訂單?', '警告', {
    confirmButtonText: '確定',
    cancelButtonText: '取消',
    type: 'warning'
  }).then(async () => {
    try {
      await orderAPI.cancel(order.id)
      ElMessage.success('取消已送出同步')
      refreshCount.value++
    } catch (error) {
      ElMessage.error('取消失敗')
    }
  }).catch(() => {})
}

async function handleView(order: Order) {
  detailOrder.value = null
  statusLogs.value = []
  detailVisible.value = true
  detailLoading.value = true
  try {
    const [orderRes, logsRes] = await Promise.all([
      orderAPI.get(order.id),
      orderAPI.getStatusLogs(order.id)
    ])
    detailOrder.value = orderRes
    statusLogs.value = (logsRes as any) ?? []
  } catch (error) {
    ElMessage.error('載入訂單詳情失敗')
  } finally {
    detailLoading.value = false
  }
}

function getStatusType(status: string) {
  const map: Record<string, string> = {
    pending: 'warning', confirmed: 'info', shipped: 'primary',
    completed: 'success', cancelled: 'danger'
  }
  return map[status] || 'info'
}

function getStatusText(status: string) {
  const map: Record<string, string> = {
    pending: '待確認', confirmed: '已確認', shipped: '已出貨',
    completed: '已完成', cancelled: '已取消'
  }
  return map[status] || status
}

function calcSubtotal(row: any) {
  const price = row.unit_price ?? row.unitPrice
  if (price != null && row.quantity != null) return (price * row.quantity).toFixed(2)
  return '-'
}

function formatTime(t: any) {
  if (!t) return '-'
  return new Date(t).toLocaleString('zh-TW')
}
</script>
