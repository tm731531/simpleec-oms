<template>
  <div>
    <h2>出貨管理</h2>
    <el-card>
      <ShipmentTable :refresh="refreshCount" @ship="handleShip" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Shipment } from '../api/shipment'
import { orderAPI } from '../api/order'
import ShipmentTable from '../components/ShipmentTable.vue'

const refreshCount = ref(0)

async function handleShip(shipment: Shipment) {
  ElMessageBox.prompt('請輸入物流單號', '出貨', {
    confirmButtonText: '確定',
    cancelButtonText: '取消'
  }).then(async ({ value }) => {
    try {
      await orderAPI.ship(shipment.orderId, value)
      ElMessage.success('出貨已送出同步')
      refreshCount.value++
    } catch (error) {
      ElMessage.error('出貨失敗')
    }
  }).catch(() => {})
}
</script>
