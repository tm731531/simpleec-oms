<template>
  <el-dialog v-model="visible" :title="isEdit ? '編輯平台' : '新增平台'" width="600px" @close="handleClose">
    <el-form ref="form" :model="formData" label-width="120px">
      <el-form-item v-if="!isEdit" label="平台ID">
        <el-input v-model="formData.id" placeholder="輸入平台ID" />
      </el-form-item>
      <el-form-item label="平台名稱">
        <el-input v-model="formData.platform_name" placeholder="輸入平台名稱 (如 Cyberbiz, Shopee)" />
      </el-form-item>
      <el-form-item label="認證憑證1">
        <el-input v-model="formData.credential1" type="password" placeholder="輸入 API Key 或 Client ID" show-password />
      </el-form-item>
      <el-form-item label="認證憑證2">
        <el-input v-model="formData.credential2" type="password" placeholder="輸入 API Secret 或 Access Token" show-password />
      </el-form-item>
      <el-form-item label="Kafka Topic">
        <el-input v-model="formData.queue_topic" placeholder="例如: shopee.fast, momo.slow" />
      </el-form-item>
      <el-form-item label="幣種">
        <el-input v-model="formData.currency" placeholder="預設 TWD" />
      </el-form-item>
      <el-form-item label="狀態">
        <el-select v-model="formData.actived">
          <el-option :label="'啟用'" :value="true" />
          <el-option :label="'停用'" :value="false" />
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
  platform?: Platform | null | undefined
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
  platform_name: '',
  credential1: '',
  credential2: '',
  queue_topic: '',
  currency: 'TWD',
  actived: true
})

watch(() => props.platform, (newVal) => {
  if (newVal !== undefined) {
    if (newVal) {
      // Edit mode
      formData.value = { ...newVal }
      isEdit.value = true
    } else {
      // New mode (newVal is null)
      resetForm()
      isEdit.value = false
    }
    visible.value = true
  }
})

function resetForm() {
  formData.value = {
    id: '',
    platform_name: '',
    credential1: '',
    credential2: '',
    actived: true
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
      result = res
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
