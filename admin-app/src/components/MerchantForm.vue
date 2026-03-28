<template>
  <!-- 測試用簡單 div 替換 el-dialog -->
  <div v-if="visible" style="position: fixed; top: 50%; left: 50%; transform: translate(-50%, -50%); background: white; border: 2px solid red; padding: 20px; width: 600px; max-height: 80vh; overflow-y: auto; z-index: 10000; box-shadow: 0 0 20px rgba(0,0,0,0.3);">
    <div style="font-size: 20px; font-weight: bold; margin-bottom: 20px;">
      {{ isEdit ? '編輯商家' : '新增商家' }}
    </div>

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

    <div style="margin-top: 20px; text-align: right;">
      <el-button @click="handleClose">取消</el-button>
      <el-button type="primary" @click="handleSubmit" :loading="loading">
        {{ isEdit ? '更新' : '建立' }}
      </el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onMounted, watchEffect } from 'vue'
import { ElMessage } from 'element-plus'
import { Merchant } from '../types'
import { merchantAPI } from '../api/merchant'

const props = defineProps<{
  merchant?: Merchant | null | undefined
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

// 使用 watchEffect 確保每次 merchant 改變都會執行
watchEffect(() => {
  const merchant = props.merchant

  if (merchant !== undefined) {
    if (merchant) {
      // Edit mode
      formData.value = { ...merchant }
      isEdit.value = true
    } else {
      // New mode (merchant is null)
      resetForm()
      isEdit.value = false
    }
    visible.value = true
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
    let result: Merchant
    if (isEdit.value) {
      result = await merchantAPI.update(formData.value.id!, formData.value)
    } else {
      result = await merchantAPI.create(formData.value)
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
