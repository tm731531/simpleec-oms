<template>
  <div>
    <h2>系統監控</h2>
    <el-row :gutter="20">
      <el-col :xs="24" :md="12">
        <el-card>
          <h3>系統健康狀態</h3>
          <el-statistic title="API 回應時間" value="45" suffix="ms" />
          <el-statistic title="CPU 使用率" value="32.5" suffix="%" />
          <el-statistic title="記憶體使用率" value="68.2" suffix="%" />
        </el-card>
      </el-col>
      <el-col :xs="24" :md="12">
        <el-card>
          <h3>最近活動</h3>
          <el-timeline>
            <el-timeline-item timestamp="2026-02-21 10:00" placement="top">
              系統啟動成功
            </el-timeline-item>
            <el-timeline-item timestamp="2026-02-21 09:30" placement="top">
              資料庫同步完成
            </el-timeline-item>
            <el-timeline-item timestamp="2026-02-21 09:00" placement="top">
              Kafka 代理連接成功
            </el-timeline-item>
          </el-timeline>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
</script>

<style scoped>
h3 {
  margin-top: 0;
  margin-bottom: 15px;
}
</style>
