<template>
  <div>
    <h2>儀表板</h2>
    <el-row :gutter="20">
      <el-col :xs="24" :sm="12" :md="6">
        <el-card>
          <div class="stat-card">
            <div class="stat-value">{{ stats.merchantCount }}</div>
            <div class="stat-label">商家總數</div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :md="6">
        <el-card>
          <div class="stat-card">
            <div class="stat-value">{{ stats.accountCount }}</div>
            <div class="stat-label">帳戶總數</div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :md="6">
        <el-card>
          <div class="stat-card">
            <div class="stat-value">{{ stats.platformCount }}</div>
            <div class="stat-label">通路數量</div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :md="6">
        <el-card>
          <div class="stat-card">
            <div class="stat-value">{{ stats.orderCount }}</div>
            <div class="stat-label">訂單總數</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-card style="margin-top: 20px">
      <h3>系統狀態</h3>
      <el-alert
        title="系統運行正常"
        type="success"
        :closable="false"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'

const stats = ref({
  merchantCount: 0,
  accountCount: 0,
  platformCount: 0,
  orderCount: 0
})

onMounted(() => {
  // 稍後從 API 加載統計數據
})
</script>

<style scoped>
.stat-card {
  text-align: center;
  padding: 20px 0;
}

.stat-value {
  font-size: 32px;
  font-weight: bold;
  color: #409eff;
  margin-bottom: 10px;
}

.stat-label {
  font-size: 14px;
  color: #606266;
}

h3 {
  margin-top: 0;
}
</style>
