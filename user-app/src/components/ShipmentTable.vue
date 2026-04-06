<template>
  <div>
    <el-table :data="orders" style="width: 100%" v-loading="loading">
      <el-table-column prop="shipmentNo" label="出貨單號" width="150" />
      <el-table-column prop="channelId" label="通路" width="100" />
      <el-table-column prop="carrier" label="物流商" width="100" />
      <el-table-column label="出貨狀態" width="150">
        <template #default="{ row }">
          <el-tag :type="row.status === 'shipped' ? 'success' : 'warning'">
            {{ row.status === 'shipped' ? '已出貨' : '待出貨' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="trackingNumber" label="追蹤號碼" width="150" />
      <el-table-column label="操作" width="150">
        <template #default="{ row }">
          <el-button
            v-if="row.status === 'confirmed'"
            link type="primary" size="small"
            @click="$emit('ship', row)"
          >
            出貨
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="currentPage"
      v-model:page-size="pageSize"
      :page-sizes="[10, 20, 50]"
      :total="total"
      layout="total, sizes, prev, pager, next"
      style="margin-top: 20px; text-align: right"
      @current-page-change="loadData"
      @page-size-change="loadData"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onMounted } from 'vue'
import { shipmentAPI, Shipment } from '../api/shipment'

const props = defineProps<{
  refresh: number
}>()

const emit = defineEmits<{
  ship: [shipment: Shipment]
}>()

const orders = ref<Shipment[]>([])
const loading = ref(false)
const currentPage = ref(1)
const pageSize = ref(20)
const total = ref(0)

onMounted(() => {
  loadData()
})

watch(() => props.refresh, () => {
  loadData()
})

async function loadData() {
  loading.value = true
  try {
    const response: any = await shipmentAPI.list(currentPage.value, pageSize.value)
    orders.value = response.data ?? []
    total.value = response.pagination?.total ?? 0
  } catch (error) {
    console.error('Failed to load pending shipments:', error)
  } finally {
    loading.value = false
  }
}
</script>
