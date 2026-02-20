<template>
  <div>
    <!-- 編輯/新增帳戶對話框 -->
    <el-dialog
      v-model="editDialogVisible"
      :title="isEdit ? '編輯帳戶' : '新增帳戶'"
      width="600px"
      @close="handleCloseEdit"
    >
      <el-form ref="form" :model="formData" label-width="120px">
        <el-form-item v-if="!isEdit" label="帳戶ID">
          <el-input v-model="formData.id" placeholder="輸入帳戶ID" />
        </el-form-item>
        <el-form-item label="商家ID">
          <el-input v-model="formData.merchant_id" placeholder="輸入商家ID" />
        </el-form-item>
        <el-form-item label="帳戶名稱">
          <el-input v-model="formData.account_name" placeholder="輸入帳戶名稱" />
        </el-form-item>
        <el-form-item label="郵箱">
          <el-input v-model="formData.account_email" type="email" placeholder="輸入郵箱" />
        </el-form-item>
        <el-form-item v-if="!isEdit" label="密碼">
          <el-input v-model="formData.account_password" type="password" placeholder="輸入密碼" show-password />
        </el-form-item>
        <el-form-item label="電話">
          <el-input v-model="formData.account_tel" placeholder="輸入電話" />
        </el-form-item>
        <el-form-item label="主帳戶">
          <el-switch v-model="formData.is_main_account" />
        </el-form-item>
        <el-form-item label="訪問級別">
          <el-input-number v-model="formData.access_level" :min="0" :max="10" />
        </el-form-item>
        <el-form-item label="狀態">
          <el-select v-model="formData.status">
            <el-option label="啟用" value="active" />
            <el-option label="停用" value="inactive" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="handleCloseEdit">取消</el-button>
        <el-button type="primary" @click="handleSubmit" :loading="loading">
          {{ isEdit ? '更新' : '建立' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 重設密碼對話框 -->
    <el-dialog v-model="resetPasswordDialogVisible" title="重設密碼" width="400px" @close="handleCloseReset">
      <el-form :model="resetPasswordForm" label-width="100px">
        <el-form-item label="新密碼">
          <el-input
            v-model="resetPasswordForm.newPassword"
            type="password"
            placeholder="輸入新密碼"
            show-password
          />
        </el-form-item>
        <el-form-item label="確認密碼">
          <el-input
            v-model="resetPasswordForm.confirmPassword"
            type="password"
            placeholder="確認新密碼"
            show-password
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="handleCloseReset">取消</el-button>
        <el-button type="primary" @click="handleResetPasswordSubmit" :loading="resetLoading">
          重設
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Account } from '../types'
import { accountAPI } from '../api/account'

const props = defineProps<{
  account?: Account | null
  resetPasswordAccount?: Account | null
}>()

const emit = defineEmits<{
  saved: [account: Account]
  close: []
}>()

const editDialogVisible = ref(false)
const resetPasswordDialogVisible = ref(false)
const isEdit = ref(false)
const loading = ref(false)
const resetLoading = ref(false)

const formData = ref<Partial<Account>>({
  id: '',
  merchant_id: '',
  account_name: '',
  account_email: '',
  account_password: '',
  account_tel: '',
  is_main_account: false,
  access_level: 0,
  status: 'active'
})

const resetPasswordForm = ref({
  newPassword: '',
  confirmPassword: ''
})

watch(() => props.account, (newVal) => {
  if (newVal) {
    formData.value = { ...newVal }
    isEdit.value = true
    editDialogVisible.value = true
  }
})

watch(() => props.resetPasswordAccount, (newVal) => {
  if (newVal) {
    resetPasswordForm.value = {
      newPassword: '',
      confirmPassword: ''
    }
    resetPasswordDialogVisible.value = true
  }
})

function resetForm() {
  formData.value = {
    id: '',
    merchant_id: '',
    account_name: '',
    account_email: '',
    account_password: '',
    account_tel: '',
    is_main_account: false,
    access_level: 0,
    status: 'active'
  }
  isEdit.value = false
}

async function handleSubmit() {
  loading.value = true
  try {
    let result
    if (isEdit.value) {
      await accountAPI.update(formData.value.id!, formData.value)
      result = formData.value as Account
    } else {
      const res = await accountAPI.create(formData.value)
      result = res.data.data
    }
    ElMessage.success(isEdit.value ? '更新成功' : '建立成功')
    emit('saved', result)
    handleCloseEdit()
  } catch (err) {
    ElMessage.error('操作失敗')
    console.error(err)
  } finally {
    loading.value = false
  }
}

async function handleResetPasswordSubmit() {
  if (resetPasswordForm.value.newPassword !== resetPasswordForm.value.confirmPassword) {
    ElMessage.error('兩次密碼輸入不一致')
    return
  }

  if (!resetPasswordForm.value.newPassword) {
    ElMessage.error('請輸入新密碼')
    return
  }

  resetLoading.value = true
  try {
    const accountId = props.resetPasswordAccount?.id
    if (!accountId) return

    await accountAPI.resetPassword(accountId, resetPasswordForm.value.newPassword)
    ElMessage.success('密碼重設成功')
    handleCloseReset()
  } catch (err) {
    ElMessage.error('密碼重設失敗')
    console.error(err)
  } finally {
    resetLoading.value = false
  }
}

function handleCloseEdit() {
  editDialogVisible.value = false
  resetForm()
  emit('close')
}

function handleCloseReset() {
  resetPasswordDialogVisible.value = false
  resetPasswordForm.value = {
    newPassword: '',
    confirmPassword: ''
  }
  emit('close')
}
</script>
