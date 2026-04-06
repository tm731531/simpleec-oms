<template>
  <div>
    <h2>通路管理</h2>
    <el-card>
      <el-row :gutter="20">
        <el-col v-for="platform in platforms" :key="platform.id" :xs="24" :sm="12" :md="8">
          <el-card class="platform-card" shadow="hover">
            <template #header>
              <h3 style="margin: 0">{{ platform.platformName }}</h3>
            </template>
            <div class="platform-info">
              <div>
                <span class="label">ID:</span>
                <span>{{ platform.id }}</span>
              </div>
              <div style="margin-top: 10px">
                <span class="label">Topic:</span>
                <span>{{ platform.queueTopic }}</span>
              </div>
            </div>
          </el-card>
        </el-col>
      </el-row>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { Platform } from '../types'
import { channelAPI } from '../api/channel'

const platforms = ref<Platform[]>([])

onMounted(async () => {
  try {
    const response: any = await channelAPI.listPlatforms()
    platforms.value = Array.isArray(response) ? response : (response?.data ?? [])
  } catch (error) {
    console.error('Failed to load platforms:', error)
  }
})
</script>

<style scoped>
.platform-card {
  height: 100%;
}

.platform-info {
  font-size: 14px;
}

.label {
  color: #909399;
  margin-right: 8px;
}
</style>
