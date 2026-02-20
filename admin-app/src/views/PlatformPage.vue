<template>
  <div>
    <div class="page-header">
      <h2>通路管理</h2>
      <el-button type="primary" @click="handleNewPlatform">
        + 新增通路
      </el-button>
    </div>
    <el-card>
      <PlatformTable :refresh="refreshCount" @edit="handleEditPlatform" />
    </el-card>
    <PlatformForm :platform="selectedPlatform" @saved="handleSaved" @close="selectedPlatform = null" />
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { Platform } from '../types'
import PlatformTable from '../components/PlatformTable.vue'
import PlatformForm from '../components/PlatformForm.vue'

const selectedPlatform = ref<Platform | null>(null)
const refreshCount = ref(0)

function handleNewPlatform() {
  selectedPlatform.value = null
}

function handleEditPlatform(platform: Platform) {
  selectedPlatform.value = platform
}

function handleSaved() {
  refreshCount.value++
  selectedPlatform.value = null
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
