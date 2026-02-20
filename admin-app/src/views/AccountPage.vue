<template>
  <div>
    <div class="page-header">
      <h2>帳戶管理</h2>
      <el-button type="primary" @click="handleNewAccount">
        + 新增帳戶
      </el-button>
    </div>
    <el-card>
      <el-alert
        title="帳戶管理功能開發中"
        type="info"
        :closable="false"
        description="此功能會串接後端 Account API"
      />
      <p style="margin-top: 20px; color: #606266;">功能特性將包括：帳戶 CRUD、密碼重設、權限設定</p>
    </el-card>
  </div>
</template>

<script setup lang="ts">
function handleNewAccount() {
  console.log('New account')
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
