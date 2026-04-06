<template>
  <div>
    <el-input
      v-model="searchKeyword"
      placeholder="搜尋訂單編號"
      style="margin-bottom: 15px; width: 300px"
      @input="handleSearch"
    />

    <el-table :data="orders" style="width: 100%" v-loading="loading">
      <el-table-column prop="orderNumber" label="訂單編號" width="150" />
      <el-table-column prop="platform" label="通路" width="100" />
      <el-table-column prop="totalAmount" label="金額" width="100" />
      <el-table-column prop="status" label="狀態" width="120">
        <template #default="{ row }">
          <el-tag :type="getStatusType(row.status)">
            {{ getStatusText(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="channelOrderId" label="通路訂單號" width="150" />
      <el-table-column label="操作" width="180">
        <template #default="{ row }">
          <el-button
            v-if="row.status === 'confirmed'"
            link type="primary" size="small"
            @click="$emit('ship', row)"
          >
            出貨
          </el-button>
          <el-button
            v-if="['pending', 'confirmed'].includes(row.status)"
            link type="danger" size="small"
            @click="$emit('cancel', row)"
          >
            取消
          </el-button>
          <el-button link type="default" size="small" @click="$emit('view', row)">詳情</el-button>
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
import { orderAPI } from '../api/order'
import { Order } from '../types'

const props = defineProps<{
  refresh: number
  filterStatus?: string
}>()

const emit = defineEmits<{
  ship: [order: Order]
  cancel: [order: Order]
  view: [order: Order]
}>()

const orders = ref<Order[]>([])
const loading = ref(false)
const searchKeyword = ref('')
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
    const response: any = await orderAPI.list(currentPage.value, pageSize.value, undefined, props.filterStatus)
    orders.value = response.data ?? []
    total.value = response.pagination?.total ?? 0
  } catch (error) {
    console.error('Failed to load orders:', error)
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  currentPage.value = 1
  loadData()
}

function getStatusType(status: string) {
  const map: Record<string, string> = {
    pending: 'info',
    confirmed: 'warning',
    shipped: 'primary',
    completed: 'success',
    cancelled: 'danger'
  }
  return map[status] || 'info'
}

function getStatusText(status: string) {
  const map: Record<string, string> = {
    pending: '待確認',
    confirmed: '已確認',
    shipped: '已出貨',
    completed: '已完成',
    cancelled: '已取消'
  }
  return map[status] || status
}
</script>
