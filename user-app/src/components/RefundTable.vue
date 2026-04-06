<template>
  <div>
    <el-table :data="refunds" style="width: 100%" v-loading="loading">
      <el-table-column label="訂單編號" width="150">
        <template #default="{ row }">{{ row.orderId }}</template>
      </el-table-column>
      <el-table-column prop="channelId" label="通路" width="100" />
      <el-table-column prop="refundAmount" label="退款金額" width="120" />
      <el-table-column prop="reason" label="原因" min-width="150" />
      <el-table-column label="狀態" width="120">
        <template #default="{ row }">
          <el-tag :type="getStatusType(row.returnStatus)">
            {{ getStatusText(row.returnStatus) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150">
        <template #default="{ row }">
          <el-button
            v-if="row.returnStatus === 'pending'"
            link type="primary" size="small"
            @click="$emit('approve', row)"
          >
            同意
          </el-button>
          <el-button
            v-if="row.returnStatus === 'pending'"
            link type="danger" size="small"
            @click="$emit('reject', row)"
          >
            拒絕
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
import { refundAPI } from '../api/refund'
import { Refund } from '../types'

const props = defineProps<{
  refresh: number
}>()

const emit = defineEmits<{
  approve: [refund: Refund]
  reject: [refund: Refund]
}>()

const refunds = ref<Refund[]>([])
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
    const response: any = await refundAPI.list(currentPage.value, pageSize.value)
    refunds.value = response.data ?? []
    total.value = response.pagination?.total ?? 0
  } catch (error) {
    console.error('Failed to load refunds:', error)
  } finally {
    loading.value = false
  }
}

function getStatusType(status: string) {
  const map: Record<string, string> = {
    pending: 'warning',
    syncing: 'info',
    completed: 'success',
    failed: 'danger'
  }
  return map[status] || 'info'
}

function getStatusText(status: string) {
  const map: Record<string, string> = {
    pending: '待處理',
    syncing: '同步中',
    completed: '已完成',
    failed: '失敗'
  }
  return map[status] || status
}
</script>
