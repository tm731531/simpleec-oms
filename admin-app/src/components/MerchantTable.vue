<template>
  <div>
    <el-table :data="merchants" stripe border v-loading="loading">
      <el-table-column prop="id" label="ID" width="120" />
      <el-table-column prop="merchant_name" label="商家名稱" />
      <el-table-column prop="merchant_email" label="郵箱" />
      <el-table-column prop="vip_level" label="VIP等級" width="80" />
      <el-table-column prop="status" label="狀態" width="80" />
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
import { Merchant } from '../types'
import { merchantAPI } from '../api/merchant'

const props = defineProps<{
  refresh?: number
}>()

const emit = defineEmits<{
  edit: [merchant: Merchant]
}>()

const merchants = ref<Merchant[]>([])
const currentPage = ref(1)
const pageSize = ref(20)
const total = ref(0)
const loading = ref(false)

async function loadMerchants() {
  loading.value = true
  try {
    const res = await merchantAPI.list(currentPage.value, pageSize.value)
    merchants.value = res.data.data.items
    total.value = res.data.data.total
  } catch (err) {
    ElMessage.error('加載商家列表失敗')
    console.error(err)
  } finally {
    loading.value = false
  }
}

function handlePageChange() {
  loadMerchants()
}

function handleEdit(row: Merchant) {
  emit('edit', row)
}

async function handleDelete(row: Merchant) {
  ElMessageBox.confirm(
    `確認刪除商家 ${row.merchant_name}？`,
    '警告',
    { type: 'warning' }
  )
    .then(async () => {
      try {
        await merchantAPI.delete(row.id)
        ElMessage.success('刪除成功')
        loadMerchants()
      } catch (err) {
        ElMessage.error('刪除失敗')
        console.error(err)
      }
    })
    .catch(() => {})
}

onMounted(() => {
  loadMerchants()
})

// 監控刷新請求
watch(() => props.refresh, () => {
  loadMerchants()
})
</script>

<style scoped>
.pagination-container {
  margin-top: 20px;
  text-align: right;
}
</style>
