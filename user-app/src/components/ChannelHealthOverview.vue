<template>
  <div class="channel-health-overview">
    <div class="header">
      <h3 style="margin: 0">通路健康狀態</h3>
      <el-button
        type="primary"
        :icon="Refresh"
        :loading="loading"
        @click="fetchHealthOverview"
      >
        重新整理
      </el-button>
    </div>

    <el-table
      v-loading="loading"
      :data="healthData"
      stripe
      style="width: 100%"
      empty-text="暫無資料（健康檢查尚未執行）"
    >
      <el-table-column prop="channelName" label="通路名稱" min-width="150" />
      <el-table-column prop="platformName" label="平台" min-width="120" />
      <el-table-column label="啟用狀態" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="row.actived ? 'success' : 'info'" size="small">
            {{ row.actived ? '啟用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="健康狀態" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="healthTagType(row.health)" size="small">
            {{ healthTagLabel(row.health) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="HTTP" width="70" align="center">
        <template #default="{ row }">
          {{ row.httpStatus ?? '-' }}
        </template>
      </el-table-column>
      <el-table-column label="最後檢查時間" min-width="170">
        <template #default="{ row }">
          {{ row.checkedAt ? formatDate(row.checkedAt) : '-' }}
        </template>
      </el-table-column>
      <el-table-column
        prop="errorMessage"
        label="錯誤訊息"
        min-width="200"
        show-overflow-tooltip
      >
        <template #default="{ row }">
          {{ row.errorMessage ?? '-' }}
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import axiosInstance from '../api/index'

interface ChannelHealth {
  channelId: string
  channelName: string
  channelSn: string
  platformName: string
  actived: boolean
  health: 'healthy' | 'unhealthy' | 'unknown'
  httpStatus: number | null
  checkedAt: string | null
  errorMessage: string | null
}

const healthData = ref<ChannelHealth[]>([])
const loading = ref(false)

async function fetchHealthOverview() {
  loading.value = true
  try {
    const res = await axiosInstance.get<ChannelHealth[]>('/api/user/channels/health-overview')
    healthData.value = Array.isArray(res) ? res : (res as any)?.data ?? []
  } catch (e) {
    console.error('Failed to fetch channel health overview:', e)
  } finally {
    loading.value = false
  }
}

function healthTagType(health: string) {
  return ({ healthy: 'success', unhealthy: 'danger', unknown: 'info' } as any)[health] ?? 'info'
}

function healthTagLabel(health: string) {
  return ({ healthy: '正常', unhealthy: '異常', unknown: '未知' } as any)[health] ?? '未知'
}

function formatDate(t: string) {
  return new Date(t).toLocaleString('zh-TW')
}

onMounted(() => {
  fetchHealthOverview()
})
</script>

<style scoped>
.channel-health-overview {
  margin-bottom: 24px;
}
.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
</style>
