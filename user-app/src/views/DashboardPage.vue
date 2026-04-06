<template>
  <div>
    <h2>儀表板</h2>

    <el-row :gutter="20" style="margin-bottom: 20px">
      <el-col :xs="24" :sm="12" :md="6">
        <el-card class="stat-card">
          <div class="stat-content">
            <div class="stat-value">{{ stats.totalOrders }}</div>
            <div class="stat-label">總訂單</div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :md="6">
        <el-card class="stat-card">
          <div class="stat-content">
            <div class="stat-value">{{ stats.channelCount }}</div>
            <div class="stat-label">活躍通路</div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :md="6">
        <el-card class="stat-card">
          <div class="stat-content">
            <div class="stat-value">{{ stats.date }}</div>
            <div class="stat-label">統計日期</div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :md="6">
        <el-card class="stat-card">
          <div class="stat-content">
            <div class="stat-value">{{ stats.totalRevenue }}</div>
            <div class="stat-label">總營收</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="20">
      <el-col :xs="24" :md="12">
        <el-card>
          <template #header>
            <h3>最近訂單</h3>
          </template>
          <el-table :data="recentOrders" style="width: 100%">
            <el-table-column prop="orderNumber" label="訂單編號" width="120" />
            <el-table-column prop="platform" label="通路" width="80" />
            <el-table-column prop="totalAmount" label="金額" width="80" />
            <el-table-column prop="status" label="狀態" width="80">
              <template #default="{ row }">
                <el-tag :type="getStatusType(row.status)">
                  {{ getStatusText(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
      <el-col :xs="24" :md="12">
        <el-card>
          <template #header>
            <h3>通路分布</h3>
          </template>
          <div class="platform-list">
            <div v-for="platform in platformStats" :key="platform.name" class="platform-item">
              <span>{{ platform.name }}</span>
              <span style="color: #666">{{ platform.count }} 個訂單</span>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { statsAPI } from '../api/stats'
import { orderAPI } from '../api/order'
import { Order } from '../types'

const stats = ref({
  totalOrders: 0,
  channelCount: 0,
  totalRevenue: 0,
  date: ''
})

const recentOrders = ref<Order[]>([])
const platformStats = ref<{ name: string; count: number }[]>([])

onMounted(async () => {
  await loadDashboard()
})

async function loadDashboard() {
  try {
    // Load today's stats from /api/user/stats/today
    const todayRes: any = await statsAPI.today()
    const todayData = todayRes?.data ?? todayRes ?? {}
    stats.value.totalOrders = todayData.totalOrders ?? 0
    stats.value.totalRevenue = todayData.totalAmount ?? 0
    stats.value.date = todayData.date ?? ''
    stats.value.channelCount = todayData.channels?.length ?? 0

    // channels: DailyStatistics[] — fields: channelId, platformId, newOrderCount
    if (todayData.channels?.length) {
      platformStats.value = todayData.channels.map((c: any) => ({
        name: c.platformId ?? c.channelId,
        count: c.newOrderCount ?? 0
      }))
    }

    // Load recent orders from /api/user/orders (1-indexed, page 1)
    const ordersRes: any = await orderAPI.list(1, 10)
    recentOrders.value = ordersRes.data ?? []
  } catch (error) {
    console.error('Failed to load dashboard:', error)
  }
}

function getStatusType(status: string) {
  const map: Record<string, string> = {
    pending: 'warning',
    confirmed: 'info',
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

<style scoped>
.stat-card {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.stat-content {
  text-align: center;
}

.stat-value {
  font-size: 32px;
  font-weight: bold;
  color: #409eff;
  margin-bottom: 10px;
}

.stat-label {
  font-size: 14px;
  color: #909399;
}

.platform-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.platform-item {
  display: flex;
  justify-content: space-between;
  padding: 8px 0;
  border-bottom: 1px solid #f0f0f0;
}
</style>
