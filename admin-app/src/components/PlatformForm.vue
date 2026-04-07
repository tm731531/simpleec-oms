<template>
  <el-dialog v-model="visible" :title="isEdit ? '編輯平台' : '新增平台'" width="750px" @close="handleClose">
    <el-form ref="form" :model="formData" label-width="140px">
      <el-form-item v-if="!isEdit" label="平台ID">
        <el-input v-model="formData.id" placeholder="輸入平台ID (如: shopee, momo)" />
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
      <el-form-item label="配送選項 (JSON)">
        <el-input v-model="shipOptionsText" type="textarea" :rows="3" placeholder='例如: {"logistics":["7-11","family"]}' />
      </el-form-item>

      <!-- 平台能力 -->
      <el-form-item label="OAuth 類型">
        <el-select v-model="capabilities.oauthFlow" placeholder="選擇 OAuth 類型" style="width: 100%">
          <el-option label="無（一般 API 認證）" value="" />
          <el-option label="Shopee OAuth" value="shopee_oauth" />
        </el-select>
        <div style="color: #909399; font-size: 12px; margin-top: 4px">
          選擇 OAuth 類型後，通路管理會顯示授權按鈕
        </div>
      </el-form-item>
      <el-form-item label="平台能力">
        <el-checkbox v-model="capabilities.multiLocation">多倉庫支援 (multiLocation)</el-checkbox>
        <el-checkbox v-model="capabilities.webhook">Webhook 支援 (webhook)</el-checkbox>
        <el-checkbox v-model="capabilities.asyncInventory">非同步庫存 (asyncInventory)</el-checkbox>
      </el-form-item>

      <!-- Token 欄位名稱設定 -->
      <el-form-item label="Token 欄位名稱">
        <div style="width: 100%">
          <el-row :gutter="10" style="margin-bottom: 8px">
            <el-col :span="12">
              <el-input v-model="tokenLabels.token1" placeholder="Token 1 名稱 (如: Access Token)" />
            </el-col>
            <el-col :span="12">
              <el-input v-model="tokenLabels.token2" placeholder="Token 2 名稱 (如: Refresh Token)" />
            </el-col>
          </el-row>
          <el-row :gutter="10" style="margin-bottom: 8px">
            <el-col :span="12">
              <el-input v-model="tokenLabels.token3" placeholder="Token 3 名稱 (如: Shop ID)" />
            </el-col>
            <el-col :span="12">
              <el-input v-model="tokenLabels.token4" placeholder="Token 4 名稱 (如: Token 到期時間)" />
            </el-col>
          </el-row>
          <el-row :gutter="10">
            <el-col :span="12">
              <el-input v-model="tokenLabels.token5" placeholder="Token 5 名稱 (留空表示不使用)" />
            </el-col>
          </el-row>
          <div style="color: #909399; font-size: 12px; margin-top: 4px">
            設定後會顯示在通路管理的 Token 欄位名稱，留空表示不使用該欄位
          </div>
        </div>
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
  actived: true,
  ship_options: {},
  capabilities: {}
})

const shipOptionsText = ref('')

const capabilities = ref({
  oauthFlow: '',
  multiLocation: false,
  webhook: false,
  asyncInventory: false
})

const tokenLabels = ref({
  token1: '',
  token2: '',
  token3: '',
  token4: '',
  token5: ''
})

watch(() => props.platform, (newVal) => {
  if (newVal !== undefined) {
    if (newVal) {
      // Edit mode
      formData.value = { ...newVal }
      shipOptionsText.value = newVal.ship_options ? JSON.stringify(newVal.ship_options, null, 2) : ''
      capabilities.value = {
        oauthFlow: newVal.capabilities?.oauthFlow || '',
        multiLocation: newVal.capabilities?.multiLocation || false,
        webhook: newVal.capabilities?.webhook || false,
        asyncInventory: newVal.capabilities?.asyncInventory || false
      }
      // Load tokenLabels from capabilities
      if (newVal.capabilities?.tokenLabels) {
        tokenLabels.value = {
          token1: newVal.capabilities.tokenLabels.token1 || '',
          token2: newVal.capabilities.tokenLabels.token2 || '',
          token3: newVal.capabilities.tokenLabels.token3 || '',
          token4: newVal.capabilities.tokenLabels.token4 || '',
          token5: newVal.capabilities.tokenLabels.token5 || ''
        }
      } else {
        tokenLabels.value = { token1: '', token2: '', token3: '', token4: '', token5: '' }
      }
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
    queue_topic: '',
    currency: 'TWD',
    actived: true,
    ship_options: {},
    capabilities: {}
  }
  shipOptionsText.value = ''
  capabilities.value = {
    oauthFlow: '',
    multiLocation: false,
    webhook: false,
    asyncInventory: false
  }
  tokenLabels.value = { token1: '', token2: '', token3: '', token4: '', token5: '' }
  isEdit.value = false
}

async function handleSubmit() {
  loading.value = true
  try {
    // Parse ship_options JSON
    if (shipOptionsText.value.trim()) {
      try {
        formData.value.ship_options = JSON.parse(shipOptionsText.value)
      } catch (e) {
        ElMessage.error('配送選項 JSON 格式錯誤')
        loading.value = false
        return
      }
    } else {
      formData.value.ship_options = undefined
    }

    // Build capabilities object
    const caps: Record<string, any> = {
      multiLocation: capabilities.value.multiLocation,
      webhook: capabilities.value.webhook,
      asyncInventory: capabilities.value.asyncInventory
    }

    // Add oauthFlow if set
    if (capabilities.value.oauthFlow) {
      caps.oauthFlow = capabilities.value.oauthFlow
    }

    // Add tokenLabels if any token label is set
    const hasAnyTokenLabel = Object.values(tokenLabels.value).some(v => v)
    if (hasAnyTokenLabel) {
      caps.tokenLabels = {
        token1: tokenLabels.value.token1 || null,
        token2: tokenLabels.value.token2 || null,
        token3: tokenLabels.value.token3 || null,
        token4: tokenLabels.value.token4 || null,
        token5: tokenLabels.value.token5 || null
      }
    }

    formData.value.capabilities = caps

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
