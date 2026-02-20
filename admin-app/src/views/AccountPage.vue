<template>
  <div>
    <div class="page-header">
      <h2>帳戶管理</h2>
      <el-button type="primary" @click="handleNewAccount">
        + 新增帳戶
      </el-button>
    </div>
    <el-card>
      <AccountTable :refresh="refreshCount" @edit="handleEditAccount" @resetPassword="handleResetPassword" />
    </el-card>
    <AccountForm
      :account="selectedAccount"
      :resetPasswordAccount="resetPasswordAccount"
      @saved="handleSaved"
      @close="handleFormClose"
    />
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { Account } from '../types'
import AccountTable from '../components/AccountTable.vue'
import AccountForm from '../components/AccountForm.vue'

const selectedAccount = ref<Account | null>(null)
const resetPasswordAccount = ref<Account | null>(null)
const refreshCount = ref(0)

function handleNewAccount() {
  selectedAccount.value = null
}

function handleEditAccount(account: Account) {
  selectedAccount.value = account
}

function handleResetPassword(account: Account) {
  resetPasswordAccount.value = account
}

function handleSaved() {
  refreshCount.value++
  selectedAccount.value = null
}

function handleFormClose() {
  selectedAccount.value = null
  resetPasswordAccount.value = null
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
