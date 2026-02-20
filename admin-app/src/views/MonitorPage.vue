<template>
  <div>
    <h2>系統監控</h2>

    <el-row :gutter="20" class="status-row">
      <el-col :xs="24" :sm="12" :md="6">
        <el-card class="status-card">
          <div class="status-indicator">
            <div class="status-dot online"></div>
            <div class="status-info">
              <div class="status-label">後端 API</div>
              <div class="status-value">運行中</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :md="6">
        <el-card class="status-card">
          <div class="status-indicator">
            <div class="status-dot online"></div>
            <div class="status-info">
              <div class="status-label">資料庫</div>
              <div class="status-value">連接正常</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :md="6">
        <el-card class="status-card">
          <div class="status-indicator">
            <div class="status-dot online"></div>
            <div class="status-info">
              <div class="status-label">緩存服務</div>
              <div class="status-value">正常</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :md="6">
        <el-card class="status-card">
          <div class="status-indicator">
            <div class="status-dot online"></div>
            <div class="status-info">
              <div class="status-label">訊息隊列</div>
              <div class="status-value">運行中</div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="20" style="margin-top: 20px">
      <el-col :xs="24" :md="12">
        <el-card>
          <h3>系統性能</h3>
          <div class="performance-item">
            <span class="label">API 平均回應時間</span>
            <span class="value">45 ms</span>
          </div>
          <el-progress :percentage="32" label="CPU 使用率" />
          <div class="performance-item" style="margin-top: 12px">
            <el-progress :percentage="68" label="記憶體使用率" />
          </div>
          <div class="performance-item" style="margin-top: 12px">
            <el-progress :percentage="45" label="磁碟使用率" />
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :md="12">
        <el-card>
          <h3>最近事件</h3>
          <el-timeline>
            <el-timeline-item
              v-for="(event, index) in recentEvents"
              :key="index"
              :timestamp="event.timestamp"
              placement="top"
              :type="event.type"
            >
              {{ event.message }}
            </el-timeline-item>
          </el-timeline>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'

const recentEvents = ref([
  {
    timestamp: '2026-02-21 14:30',
    type: 'success',
    message: '系統正常運行'
  },
  {
    timestamp: '2026-02-21 14:00',
    type: 'primary',
    message: '資料庫同步完成'
  },
  {
    timestamp: '2026-02-21 13:30',
    type: 'primary',
    message: 'Kafka 代理連接成功'
  },
  {
    timestamp: '2026-02-21 13:00',
    type: 'success',
    message: '容器健康檢查通過'
  }
])
</script>

<style scoped>
h3 {
  margin-top: 0;
  margin-bottom: 15px;
}

.status-row {
  margin-bottom: 20px;
}

.status-card {
  height: 100%;
}

.status-indicator {
  display: flex;
  align-items: center;
  gap: 12px;
}

.status-dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  animation: pulse 2s ease-in-out infinite;
}

.status-dot.online {
  background-color: #67c23a;
}

.status-info {
  flex: 1;
}

.status-label {
  font-size: 12px;
  color: #909399;
  margin-bottom: 4px;
}

.status-value {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.performance-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.performance-item .label {
  font-size: 14px;
  color: #606266;
}

.performance-item .value {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

@keyframes pulse {
  0%, 100% {
    opacity: 1;
  }
  50% {
    opacity: 0.5;
  }
}
</style>
