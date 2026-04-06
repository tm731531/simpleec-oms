<template>
  <div>
    <el-input
      v-model="searchKeyword"
      placeholder="搜尋賣場 (SKU/名稱)"
      style="margin-bottom: 15px; width: 300px"
      @input="handleSearch"
    />

    <el-table :data="sellpacks" style="width: 100%" v-loading="loading">
      <el-table-column label="商品資訊" min-width="200">
        <template #default="{ row }">
          <div>
            <div style="font-weight: bold">{{ row.sku }}</div>
            <div style="font-size: 12px; color: #666">{{ row.productId }}</div>
          </div>
        </template>
      </el-table-column>

      <el-table-column label="通路" width="100" prop="channelId" />

      <el-table-column label="庫存" width="100">
        <template #default="{ row }">{{ row.quantity }}</template>
      </el-table-column>

      <el-table-column label="價格" width="100">
        <template #default="{ row }">${{ row.sellingPrice }}</template>
      </el-table-column>

      <el-table-column label="同步狀態" width="120">
        <template #default="{ row }">
          <el-tag :type="getSyncStatusType(row.syncStatus)">
            {{ getSyncStatusText(row.syncStatus) }}
          </el-tag>
        </template>
      </el-table-column>

      <el-table-column label="操作" width="120">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="$emit('edit', row)">編輯</el-button>
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
import { sellpackAPI } from '../api/sellpack'
import { SellPack } from '../types'
import { getSyncStatusText, getSyncStatusType } from '../utils/syncStatus'

const props = defineProps<{
  refresh: number
}>()

const emit = defineEmits<{
  edit: [sellpack: SellPack]
  retry: [sellpack: SellPack]
}>()

const sellpacks = ref<SellPack[]>([])
const loading = ref(false)
const searchKeyword = ref('')
const currentPage = ref(1)
const pageSize = ref(20)
const total = ref(0)
const refreshInterval = ref<NodeJS.Timeout | null>(null)

onMounted(() => {
  loadData()
  startPolling()
})

watch(() => props.refresh, () => {
  loadData()
})

async function loadData() {
  loading.value = true
  try {
    const response: any = await sellpackAPI.list(currentPage.value - 1, pageSize.value)
    sellpacks.value = response.data ?? []
    total.value = response.pagination?.total ?? 0
  } catch (error) {
    console.error('Failed to load sellpacks:', error)
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  currentPage.value = 1
  loadData()
}

function startPolling() {
  refreshInterval.value = setInterval(() => {
    loadData()
  }, 5 * 60 * 1000) // 5 minutes
}
</script>
