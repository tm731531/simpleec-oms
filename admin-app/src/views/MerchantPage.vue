<template>
  <div>
    <div class="page-header">
      <h2>商家管理</h2>
      <el-button type="primary" @click="handleNewMerchant">
        + 新增商家
      </el-button>
    </div>
    <el-card>
      <MerchantTable :refresh="refreshCount" @edit="handleEditMerchant" />
    </el-card>
    <MerchantForm :merchant="selectedMerchant" @saved="handleSaved" @close="selectedMerchant = null" />
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { Merchant } from '../types'
import MerchantTable from '../components/MerchantTable.vue'
import MerchantForm from '../components/MerchantForm.vue'

const selectedMerchant = ref<Merchant | null>(null)
const refreshCount = ref(0)

function handleNewMerchant() {
  selectedMerchant.value = null
}

function handleEditMerchant(merchant: Merchant) {
  selectedMerchant.value = merchant
}

function handleSaved() {
  refreshCount.value++
  selectedMerchant.value = null
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
