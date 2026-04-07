<template>
  <div class="channel-health-overview">
    <div class="header">
      <h3 style="margin: 0">通路健康狀態</h3>
      <el-button :icon="Refresh" :loading="loading" @click="fetchHealthOverview">
        重新整理
      </el-button>
    </div>

    <div v-if="loading" style="padding: 12px 0; color: #909399; font-size: 13px">載入中...</div>

    <div v-else-if="healthData.length === 0" style="padding: 12px 0; color: #909399; font-size: 13px">
      暫無資料（健康檢查尚未執行）
    </div>

    <div v-else class="cards">
      <div
        v-for="row in healthData"
        :key="row.channelId"
        class="health-card"
        :class="cardClass(row)"
      >
        <div class="card-name" :title="row.channelName">{{ row.channelName }}</div>

        <div class="card-tags">
          <el-tooltip :content="channelTooltip(row)" placement="top">
            <el-tag :type="healthTagType(row.health)" size="small">
              通路 {{ healthTagLabel(row.health) }}
            </el-tag>
          </el-tooltip>
          <el-tooltip :content="platformTooltip(row)" placement="top">
            <el-tag :type="healthTagType(row.platformHealth)" size="small">
              平台 {{ healthTagLabel(row.platformHealth) }}
            </el-tag>
          </el-tooltip>
        </div>

        <div class="card-time">
          {{ lastCheckedLabel(row) }}
        </div>
      </div>
    </div>
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
  platformHealth: 'healthy' | 'unhealthy' | 'unknown'
  platformHttpStatus: number | null
  platformCheckedAt: string | null
  platformErrorMessage: string | null
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

function cardClass(row: ChannelHealth) {
  if (row.health === 'unhealthy' || row.platformHealth === 'unhealthy') return 'card-error'
  if (row.health === 'healthy' && row.platformHealth === 'healthy') return 'card-ok'
  return 'card-unknown'
}

function channelTooltip(row: ChannelHealth) {
  const parts = [`通路帳號健康狀況`]
  if (row.httpStatus) parts.push(`HTTP ${row.httpStatus}`)
  if (row.errorMessage) parts.push(row.errorMessage)
  return parts.join(' · ')
}

function platformTooltip(row: ChannelHealth) {
  const parts = [`平台 API 整體健康狀況`]
  if (row.platformHttpStatus) parts.push(`HTTP ${row.platformHttpStatus}`)
  if (row.platformErrorMessage) parts.push(row.platformErrorMessage)
  return parts.join(' · ')
}

function lastCheckedLabel(row: ChannelHealth) {
  const t = row.checkedAt ?? row.platformCheckedAt
  if (!t) return '尚未檢查'
  const d = new Date(t)
  return d.toLocaleTimeString('zh-TW', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
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
  margin-bottom: 12px;
}
.cards {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
.health-card {
  min-width: 180px;
  max-width: 220px;
  padding: 10px 14px;
  border-radius: 8px;
  border: 1.5px solid #e4e7ed;
  background: #fff;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.card-ok    { border-color: #67c23a; background: #f0f9eb; }
.card-error { border-color: #f56c6c; background: #fef0f0; }
.card-unknown { border-color: #d3d6db; background: #f5f7fa; }

.card-name {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.card-tags {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.card-time {
  font-size: 11px;
  color: #909399;
}
</style>
