<template>
  <div class="settings-page">
    <h3 class="page-title">商家設定</h3>

    <el-card v-loading="loading">
      <el-form :model="form" label-width="120px" v-if="form.id">

        <div class="section-title">基本資訊</div>
        <el-form-item label="商家名稱">
          <el-input v-model="form.merchant_name" />
        </el-form-item>
        <el-form-item label="聯絡 Email">
          <el-input v-model="form.merchant_email" />
        </el-form-item>
        <el-form-item label="聯絡電話">
          <el-input v-model="form.merchant_phone_number" />
        </el-form-item>
        <el-form-item label="統一編號">
          <span class="readonly-text">{{ form.tax_id_number || '—' }}</span>
        </el-form-item>
        <el-form-item label="時區">
          <span class="readonly-text">{{ form.user_local_time_zone || '—' }}</span>
        </el-form-item>

        <div class="section-title">地址</div>
        <el-form-item label="縣市">
          <el-input v-model="form.address_city" style="width:120px" />
          <el-input v-model="form.address_region" style="width:120px; margin-left:8px" placeholder="區域" />
        </el-form-item>
        <el-form-item label="地址">
          <el-input v-model="form.address_line1" placeholder="地址第一行" />
        </el-form-item>
        <el-form-item label="">
          <el-input v-model="form.address_line2" placeholder="地址第二行（選填）" />
        </el-form-item>
        <el-form-item label="郵遞區號">
          <el-input v-model="form.address_zip" style="width:120px" />
        </el-form-item>

        <div class="section-title">付款聯絡人</div>
        <el-form-item label="姓名">
          <el-input v-model="form.payer_name" />
        </el-form-item>
        <el-form-item label="Email">
          <el-input v-model="form.payer_email" />
        </el-form-item>
        <el-form-item label="電話">
          <el-input v-model="form.payer_phone_number" />
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="saving" @click="submitSettings">儲存設定</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api/index'

const loading = ref(false)
const saving = ref(false)

const form = reactive<Record<string, string>>({
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
  address_phone_number: '',
  user_local_time_zone: '',
  payer_name: '',
  payer_email: '',
  payer_phone_number: '',
})

async function loadSettings() {
  loading.value = true
  try {
    const data: any = await api.get('/api/user/settings')
    Object.assign(form, data)
  } catch {
    ElMessage.error('載入設定失敗')
  } finally {
    loading.value = false
  }
}

async function submitSettings() {
  saving.value = true
  try {
    await api.patch('/api/user/settings', {
      merchantName: form.merchant_name,
      merchantEmail: form.merchant_email,
      merchantPhoneNumber: form.merchant_phone_number,
    })
    ElMessage.success('設定已儲存')
  } catch {
    ElMessage.error('儲存失敗')
  } finally {
    saving.value = false
  }
}

onMounted(loadSettings)
</script>

<style scoped>
.settings-page { padding: 0; }
.page-title { margin: 0 0 16px; font-size: 20px; color: #333; }
.section-title {
  font-size: 14px; font-weight: 600; color: #333;
  margin: 16px 0 12px; padding-left: 8px;
  border-left: 3px solid #409eff;
}
.readonly-text { color: #555; font-size: 14px; }
</style>
