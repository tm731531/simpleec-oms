<template>
  <el-dialog v-model="visible" :title="isEdit ? '編輯通路' : '新增通路'" width="600px" @close="handleClose">
    <el-form ref="form" :model="formData" label-width="120px">
      <el-form-item v-if="!isEdit" label="通路ID">
        <el-input v-model="formData.id" placeholder="輸入通路ID" />
      </el-form-item>
      <el-form-item label="商家ID">
        <el-input v-model="formData.merchant_id" placeholder="輸入商家ID" />
      </el-form-item>
      <el-form-item label="通路名稱">
        <el-input v-model="formData.platform_name" placeholder="輸入通路名稱" />
      </el-form-item>
      <el-form-item label="通路代碼">
        <el-input v-model="formData.platform_code" placeholder="輸入通路代碼 (如 shopee, momo)" />
      </el-form-item>
      <el-form-item label="API Key">
        <el-input v-model="formData.api_key" placeholder="輸入 API Key" show-password />
      </el-form-item>
      <el-form-item label="API Secret">
        <el-input v-model="formData.api_secret" type="password" placeholder="輸入 API Secret" show-password />
      </el-form-item>
      <el-form-item label="狀態">
        <el-select v-model="formData.status">
          <el-option label="啟用" value="active" />
          <el-option label="停用" value="inactive" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="handleClose">取消</el-button>
      <el-button type="primary" @click="handleSubmit" :loading="loading">
        {{ isEdit ? '更新' : '建立' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Platform } from '../types'
import { platformAPI } from '../api/platform'

const props = defineProps<{
  platform?: Platform | null
}>()

const emit = defineEmits<{
  saved: [platform: Platform]
  close: []
}>()

const visible = ref(false)
const isEdit = ref(false)
const loading = ref(false)

const formData = ref<Partial<Platform>>({
  id: '',
  merchant_id: '',
  platform_name: '',
  platform_code: '',
  api_key: '',
  api_secret: '',
  status: 'active'
})

watch(() => props.platform, (newVal) => {
  if (newVal) {
    formData.value = { ...newVal }
    isEdit.value = true
    visible.value = true
  } else {
    resetForm()
  }
})

function resetForm() {
  formData.value = {
    id: '',
    merchant_id: '',
    platform_name: '',
    platform_code: '',
    api_key: '',
    api_secret: '',
    status: 'active'
  }
  isEdit.value = false
}

async function handleSubmit() {
  loading.value = true
  try {
    let result
    if (isEdit.value) {
      await platformAPI.update(formData.value.id!, formData.value)
      result = formData.value as Platform
    } else {
      const res = await platformAPI.create(formData.value)
      result = res.data.data
    }
    ElMessage.success(isEdit.value ? '更新成功' : '建立成功')
    emit('saved', result)
    handleClose()
  } catch (err) {
    ElMessage.error('操作失敗')
    console.error(err)
  } finally {
    loading.value = false
  }
}

function handleClose() {
  visible.value = false
  emit('close')
}
</script>
