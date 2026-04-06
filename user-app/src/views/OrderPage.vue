<template>
  <div>
    <h2>訂單管理</h2>
    <el-card>
      <OrderTable :refresh="refreshCount" @ship="handleShip" @cancel="handleCancel" @view="handleView" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Order } from '../types'
import { orderAPI } from '../api/order'
import OrderTable from '../components/OrderTable.vue'

const refreshCount = ref(0)

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

function handleView(order: Order) {
  ElMessage.info('訂單詳情將在後續版本實現')
}
</script>
