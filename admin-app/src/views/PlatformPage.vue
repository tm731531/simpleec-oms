<template>
  <div>
    <div class="page-header">
      <h2>通路管理</h2>
      <el-button type="primary" @click="handleNewPlatform">
        + 新增通路
      </el-button>
    </div>
    <el-card>
      <el-alert
        title="通路管理功能開發中"
        type="info"
        :closable="false"
        description="此功能會串接後端 Platform API"
      />
      <p style="margin-top: 20px; color: #606266;">功能特性將包括：通路 CRUD、API 金鑰管理、同步日誌</p>
    </el-card>
  </div>
</template>

<script setup lang="ts">
function handleNewPlatform() {
  console.log('New platform')
}
</script>

<style scoped>
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.page-header h2 {
  margin: 0;
}
</style>
