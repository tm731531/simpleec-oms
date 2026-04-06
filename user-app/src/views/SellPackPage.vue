<template>
  <div>
    <div class="page-header">
      <h2>賣場管理</h2>
      <el-button type="primary" @click="handleBatchUpdate">批量更新</el-button>
    </div>
    <el-card>
      <SellPackTable :refresh="refreshCount" @edit="handleEditSellPack" @retry="handleRetrySellPack" />
    </el-card>
    <SellPackForm :sellpack="selectedSellpack" @saved="handleSaved" @close="selectedSellpack = null" />
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { SellPack } from '../types'
import SellPackTable from '../components/SellPackTable.vue'
import SellPackForm from '../components/SellPackForm.vue'

const selectedSellpack = ref<SellPack | null>(null)
const refreshCount = ref(0)

function handleEditSellPack(sellpack: SellPack) {
  selectedSellpack.value = sellpack
}

function handleRetrySellPack(sellpack: SellPack) {
  ElMessage.info('重試功能將在後續版本實現')
}

function handleBatchUpdate() {
  ElMessage.info('批量更新功能將在後續版本實現')
}

function handleSaved() {
  refreshCount.value++
  selectedSellpack.value = null
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
