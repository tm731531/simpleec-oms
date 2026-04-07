<template>
  <div>
    <el-table :data="platforms" stripe border v-loading="loading">
      <el-table-column prop="id" label="平台ID" width="120" />
      <el-table-column prop="platform_name" label="平台名稱" />
      <el-table-column prop="queue_topic" label="Kafka Topic" width="150" />
      <el-table-column prop="currency" label="幣種" width="80" />
      <el-table-column label="配送選項" width="120">
        <template #default="{ row }">
          {{ row.ship_options ? Object.keys(row.ship_options).join(', ') : '-' }}
        </template>
      </el-table-column>
      <el-table-column label="平台能力" width="180">
        <template #default="{ row }">
          <el-tag v-if="row.capabilities?.multiLocation" size="small" class="capability-tag">多倉庫</el-tag>
          <el-tag v-if="row.capabilities?.webhook" size="small" type="success" class="capability-tag">Webhook</el-tag>
          <el-tag v-if="row.capabilities?.asyncInventory" size="small" type="warning" class="capability-tag">非同步庫存</el-tag>
          <span v-if="!row.capabilities || (!row.capabilities.multiLocation && !row.capabilities.webhook && !row.capabilities.asyncInventory)">-</span>
        </template>
      </el-table-column>
      <el-table-column prop="actived" label="狀態" width="80">
        <template #default="{ row }">
          {{ row.actived ? '啟用' : '停用' }}
        </template>
      </el-table-column>
      <el-table-column prop="updated_at" label="最後更新" width="160">
        <template #default="{ row }">
          {{ formatDateTime(row.updated_at) }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150">
        <template #default="{ row }">
          <el-button type="primary" size="small" @click="handleEdit(row)">
            編輯
          </el-button>
          <el-button type="danger" size="small" @click="handleDelete(row)">
            刪除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pagination-container">
      <el-pagination
        v-model:current-page="currentPage"
        v-model:page-size="pageSize"
        :page-sizes="[10, 20, 50]"
        :total="total"
        layout="total, sizes, prev, pager, next"
        @change="handlePageChange"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Platform } from '../types'
import { platformAPI } from '../api/platform'

const props = defineProps<{
  refresh?: number
}>()

const emit = defineEmits<{
  edit: [platform: Platform]
}>()

const platforms = ref<Platform[]>([])
const currentPage = ref(1)
const pageSize = ref(20)
const total = ref(0)
const loading = ref(false)

function formatDateTime(dateStr: string): string {
  if (!dateStr) return '-'
  const date = new Date(dateStr)
  return date.toLocaleString('zh-TW', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })
}

async function loadPlatforms() {
  loading.value = true
  try {
    const res = await platformAPI.list(currentPage.value, pageSize.value)
    platforms.value = res.items
    total.value = res.total
  } catch (err) {
    ElMessage.error('加載平台列表失敗')
    console.error(err)
  } finally {
    loading.value = false
  }
}

function handlePageChange() {
  loadPlatforms()
}

function handleEdit(row: Platform) {
  emit('edit', row)
}

async function handleDelete(row: Platform) {
  ElMessageBox.confirm(
    `確認刪除平台 ${row.platform_name}？`,
    '警告',
    { type: 'warning' }
  )
    .then(async () => {
      try {
        await platformAPI.delete(row.id)
        ElMessage.success('刪除成功')
        loadPlatforms()
      } catch (err) {
        ElMessage.error('刪除失敗')
        console.error(err)
      }
    })
    .catch(() => {})
}

onMounted(() => {
  loadPlatforms()
})

watch(() => props.refresh, () => {
  loadPlatforms()
})
</script>

<style scoped>
.pagination-container {
  margin-top: 20px;
  text-align: right;
}

.capability-tag {
  margin-right: 4px;
  margin-bottom: 2px;
}
</style>
