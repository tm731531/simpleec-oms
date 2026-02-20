<template>
  <el-dialog v-model="visible" :title="isEdit ? '編輯商家' : '新增商家'" width="600px" @close="handleClose">
    <el-form ref="form" :model="formData" label-width="120px">
      <el-form-item v-if="!isEdit" label="商家ID">
        <el-input v-model="formData.id" placeholder="輸入商家ID" />
      </el-form-item>
      <el-form-item label="商家名稱">
        <el-input v-model="formData.merchant_name" placeholder="輸入商家名稱" />
      </el-form-item>
      <el-form-item label="郵箱">
        <el-input v-model="formData.merchant_email" type="email" placeholder="輸入郵箱" />
      </el-form-item>
      <el-form-item label="電話">
        <el-input v-model="formData.merchant_phone_number" placeholder="輸入電話" />
      </el-form-item>
      <el-form-item label="統一編號">
        <el-input v-model="formData.tax_id_number" placeholder="輸入統一編號" />
      </el-form-item>
      <el-form-item label="城市">
        <el-input v-model="formData.address_city" placeholder="輸入城市" />
      </el-form-item>
      <el-form-item label="地區">
        <el-input v-model="formData.address_region" placeholder="輸入地區" />
      </el-form-item>
      <el-form-item label="國家">
        <el-input v-model="formData.address_country" placeholder="輸入國家" />
      </el-form-item>
      <el-form-item label="郵遞區號">
        <el-input v-model="formData.address_zip" placeholder="輸入郵遞區號" />
      </el-form-item>
      <el-form-item label="地址1">
        <el-input v-model="formData.address_line1" placeholder="輸入地址1" />
      </el-form-item>
      <el-form-item label="地址2">
        <el-input v-model="formData.address_line2" placeholder="輸入地址2" />
      </el-form-item>
      <el-form-item label="VIP等級">
        <el-input-number v-model="formData.vip_level" :min="0" />
      </el-form-item>
      <el-form-item label="時區">
        <el-input v-model="formData.user_local_time_zone" placeholder="例如: Asia/Taipei" />
      </el-form-item>
      <el-form-item label="付款人名稱">
        <el-input v-model="formData.payer_name" placeholder="輸入付款人名稱" />
      </el-form-item>
      <el-form-item label="付款人郵箱">
        <el-input v-model="formData.payer_email" type="email" placeholder="輸入付款人郵箱" />
      </el-form-item>
      <el-form-item label="付款人電話">
        <el-input v-model="formData.payer_phone_number" placeholder="輸入付款人電話" />
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
import { Merchant } from '../types'
import { merchantAPI } from '../api/merchant'

const props = defineProps<{
  merchant?: Merchant | null
}>()

const emit = defineEmits<{
  saved: [merchant: Merchant]
  close: []
}>()

const visible = ref(false)
const isEdit = ref(false)
const loading = ref(false)

const formData = ref<Partial<Merchant>>({
  id: '',
  merchant_name: '',
  merchant_email: '',
  merchant_phone_number: '',
  tax_id_number: '',
  address_city: '',
  address_region: '',
  address_country: '',
  address_zip: '',
  address_line1: '',
  address_line2: '',
  vip_level: 0,
  user_local_time_zone: 'Asia/Taipei',
  payer_name: '',
  payer_email: '',
  payer_phone_number: '',
  status: 'active'
})

watch(() => props.merchant, (newVal) => {
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
    merchant_name: '',
    merchant_email: '',
    merchant_phone_number: '',
    tax_id_number: '',
    address_city: '',
    address_region: '',
    address_country: '',
    address_zip: '',
    address_line1: '',
    address_line2: '',
    vip_level: 0,
    user_local_time_zone: 'Asia/Taipei',
    payer_name: '',
    payer_email: '',
    payer_phone_number: '',
    status: 'active'
  }
  isEdit.value = false
}

async function handleSubmit() {
  loading.value = true
  try {
    let result
    if (isEdit.value) {
      await merchantAPI.update(formData.value.id!, formData.value)
      result = formData.value as Merchant
    } else {
      const res = await merchantAPI.create(formData.value)
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
