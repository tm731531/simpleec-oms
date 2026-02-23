<template>
  <div>
    <!-- Error display for debugging -->
    <el-alert v-if="errorMessage" type="error" :closable="true" style="margin-bottom: 10px">
      {{ errorMessage }}
    </el-alert>

    <el-table :data="accounts" stripe border v-loading="loading">
      <el-table-column prop="id" label="帳戶ID" width="120" />
      <el-table-column prop="account_name" label="帳戶名稱" />
      <el-table-column prop="account_email" label="郵箱" />
      <el-table-column prop="account_tel" label="電話" width="120" />
      <el-table-column prop="is_main_account" label="主帳戶" width="80">
        <template #default="{ row }">
          <el-tag :type="row.is_main_account ? 'success' : 'info'">
            {{ row.is_main_account ? '是' : '否' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="狀態" width="80" />
      <el-table-column label="操作" width="200">
        <template #default="{ row }">
          <el-button type="primary" size="small" @click="handleEdit(row)">
            編輯
          </el-button>
          <el-button type="warning" size="small" @click="handleResetPassword(row)">
            重設密碼
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
import { Account } from '../types'
import { accountAPI } from '../api/account'

const props = defineProps<{
  refresh?: number
}>()

const emit = defineEmits<{
  edit: [account: Account]
  resetPassword: [account: Account]
}>()

const accounts = ref<Account[]>([])
const currentPage = ref(1)
const pageSize = ref(20)
const total = ref(0)
const loading = ref(false)
const errorMessage = ref('')

async function loadAccounts() {
  loading.value = true
  errorMessage.value = ''
  try {
    const res = await accountAPI.list(currentPage.value, pageSize.value)
    accounts.value = res.items
    total.value = res.total
  } catch (err: any) {
    const errorText = err?.message || err?.response?.statusText || JSON.stringify(err)
    errorMessage.value = `加載帳戶列表失敗: ${errorText}`
    ElMessage.error(errorMessage.value)
    console.error('Error loading accounts:', err)
  } finally {
    loading.value = false
  }
}

function handlePageChange() {
  loadAccounts()
}

function handleEdit(row: Account) {
  emit('edit', row)
}

function handleResetPassword(row: Account) {
  emit('resetPassword', row)
}

async function handleDelete(row: Account) {
  ElMessageBox.confirm(
    `確認刪除帳戶 ${row.account_name}？`,
    '警告',
    { type: 'warning' }
  )
    .then(async () => {
      try {
        await accountAPI.delete(row.id)
        ElMessage.success('刪除成功')
        loadAccounts()
      } catch (err) {
        ElMessage.error('刪除失敗')
        console.error(err)
      }
    })
    .catch(() => {})
}

onMounted(() => {
  loadAccounts()
})

watch(() => props.refresh, () => {
  loadAccounts()
})
</script>

<style scoped>
.pagination-container {
  margin-top: 20px;
  text-align: right;
}
</style>
