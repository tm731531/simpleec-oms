<template>
  <div class="report-page">
    <h3 class="page-title">銷售報表</h3>

    <!-- Summary Cards -->
    <el-row :gutter="16" style="margin-bottom:16px" v-loading="loading">
      <el-col :span="6">
        <el-card class="stat-card">
          <div class="stat-label">總訂單數</div>
          <div class="stat-value">{{ sales?.summary?.totalOrders ?? 0 }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="stat-card">
          <div class="stat-label">總金額</div>
          <div class="stat-value">NT$ {{ fmt(sales?.summary?.totalAmount) }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="stat-card">
          <div class="stat-label">已出貨</div>
          <div class="stat-value">{{ sales?.summary?.totalShipped ?? 0 }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="stat-card">
          <div class="stat-label">取消率</div>
          <div class="stat-value">
            {{ sales?.summary?.totalOrders
              ? ((sales.summary.totalCancelled / sales.summary.totalOrders) * 100).toFixed(1) + '%'
              : '0%' }}
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16">
      <!-- By Date -->
      <el-col :span="14">
        <el-card>
          <template #header><span>每日訂單</span></template>
          <el-table :data="sales?.byDate ?? []" stripe size="small">
            <el-table-column prop="date" label="日期" width="120" />
            <el-table-column prop="orderCount" label="訂單數" width="90" align="right" />
            <el-table-column label="金額" align="right">
              <template #default="{ row }">NT$ {{ fmt(row.amount) }}</template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>

      <!-- By Platform -->
      <el-col :span="10">
        <el-card>
          <template #header><span>各平台佔比</span></template>
          <el-table :data="sales?.byPlatform ?? []" stripe size="small">
            <el-table-column prop="platformId" label="平台" />
            <el-table-column prop="orderCount" label="訂單" width="70" align="right" />
            <el-table-column label="佔比" width="80" align="right">
              <template #default="{ row }">{{ row.percentage?.toFixed(1) }}%</template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <!-- Profit Report -->
    <el-card style="margin-top:16px">
      <template #header>
        <div class="card-header">
          <span>利潤分析</span>
          <div>
            <span class="profit-stat">總營收：NT$ {{ fmt(profit?.totalRevenue) }}</span>
            <span class="profit-stat">毛利：NT$ {{ fmt(profit?.grossProfit) }}</span>
            <span class="profit-stat">毛利率：{{ profit?.grossMargin != null ? profit.grossMargin.toFixed(1) + '%' : '—' }}</span>
          </div>
        </div>
      </template>
      <el-table :data="profit?.byProduct ?? []" stripe size="small">
        <el-table-column prop="productName" label="商品名稱" min-width="200" />
        <el-table-column label="營收" width="120" align="right">
          <template #default="{ row }">{{ row.revenue != null ? 'NT$ ' + fmt(row.revenue) : '—' }}</template>
        </el-table-column>
        <el-table-column label="成本" width="100" align="right">
          <template #default="{ row }">{{ row.cost != null ? 'NT$ ' + fmt(row.cost) : '—' }}</template>
        </el-table-column>
        <el-table-column label="利潤" width="100" align="right">
          <template #default="{ row }">{{ row.profit != null ? 'NT$ ' + fmt(row.profit) : '—' }}</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { reportAPI } from '../api/report'

const loading = ref(false)
const sales = ref<any>(null)
const profit = ref<any>(null)

function fmt(v?: number | null): string {
  if (v == null) return '0'
  return v.toLocaleString('zh-TW')
}

function getDefaultDateRange(): { from: string; to: string } {
  const to = new Date()
  const from = new Date()
  from.setDate(from.getDate() - 30)
  const fmt = (d: Date) => d.toISOString().slice(0, 10)
  return { from: fmt(from), to: fmt(to) }
}

async function loadReports() {
  loading.value = true
  try {
    const { from, to } = getDefaultDateRange()
    const [s, p] = await Promise.all([
      reportAPI.sales(from, to),
      reportAPI.profit(from, to),
    ])
    sales.value = s
    profit.value = p
  } catch {
    ElMessage.error('載入報表失敗')
  } finally {
    loading.value = false
  }
}

onMounted(loadReports)
</script>

<style scoped>
.report-page { padding: 0; }
.page-title { margin: 0 0 16px; font-size: 20px; color: #333; }
.stat-card { text-align: center; }
.stat-label { font-size: 13px; color: #999; margin-bottom: 8px; }
.stat-value { font-size: 24px; font-weight: 600; color: #333; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.profit-stat { margin-left: 16px; font-size: 13px; color: #555; }
</style>
