<template>
  <el-dialog v-model="visible" title="編輯賣場" width="600px" @close="handleClose">
    <el-form :model="formData" label-width="100px">
      <el-form-item label="商品">
        <el-input :value="formData.productId" disabled />
      </el-form-item>
      <el-form-item label="通路">
        <el-input :value="formData.channelId" disabled />
      </el-form-item>
      <el-form-item label="數量">
        <el-input-number v-model.number="formData.quantity" :min="0" />
      </el-form-item>
      <el-form-item label="售價">
        <el-input-number v-model.number="formData.sellingPrice" :min="0" :precision="2" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="handleClose">取消</el-button>
      <el-button type="primary" @click="handleSubmit" :loading="loading">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { SellPack } from '../types'
import { sellpackAPI } from '../api/sellpack'

const props = defineProps<{
  sellpack?: SellPack | null
}>()

const emit = defineEmits<{
  saved: [sellpack: SellPack]
  close: []
}>()

const visible = ref(false)
const loading = ref(false)

const formData = ref<Partial<SellPack>>({
  productId: '',
  channelId: '',
  quantity: 0,
  sellingPrice: 0
})

watch(() => props.sellpack, (newVal) => {
  if (newVal) {
    formData.value = { ...newVal }
    visible.value = true
  }
})

async function handleSubmit() {
  loading.value = true
  try {
    if (props.sellpack?.id) {
      const originalQuantity = props.sellpack.quantity
      const originalPrice = props.sellpack.sellingPrice
      if (formData.value.quantity !== originalQuantity) {
        await sellpackAPI.update(props.sellpack.id, 'quantity', formData.value.quantity)
      }
      if (formData.value.sellingPrice !== originalPrice) {
        await sellpackAPI.update(props.sellpack.id, 'price', formData.value.sellingPrice)
      }
    }
    ElMessage.success('保存成功')
    emit('saved', formData.value as SellPack)
    handleClose()
  } catch (error) {
    ElMessage.error('保存失敗')
  } finally {
    loading.value = false
  }
}

function handleClose() {
  visible.value = false
  emit('close')
}
</script>
