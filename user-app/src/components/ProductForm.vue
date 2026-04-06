<template>
  <el-dialog v-model="visible" :title="isEdit ? '編輯商品' : '新增商品'" width="600px" @close="handleClose">
    <el-form ref="form" :model="formData" label-width="100px">
      <el-form-item label="SKU">
        <el-input v-model="formData.sku" placeholder="輸入SKU" :disabled="isEdit" />
      </el-form-item>
      <el-form-item label="商品名稱">
        <el-input v-model="formData.productName" placeholder="輸入商品名稱" />
      </el-form-item>
      <el-form-item label="庫存">
        <el-input-number v-model.number="formData.quantity" :min="0" />
      </el-form-item>
      <el-form-item label="建議售價">
        <el-input-number v-model.number="formData.suggestPrice" :min="0" :precision="2" />
      </el-form-item>
      <el-form-item label="成本價">
        <el-input-number v-model.number="formData.costPrice" :min="0" :precision="2" />
      </el-form-item>
      <el-form-item label="安全庫存">
        <el-input-number v-model.number="formData.safetyQuantity" :min="0" />
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
import { Product } from '../types'
import { productAPI } from '../api/product'

const props = defineProps<{
  product?: Product | null
}>()

const emit = defineEmits<{
  saved: [product: Product]
  close: []
}>()

const visible = ref(false)
const isEdit = ref(false)
const loading = ref(false)

const formData = ref<Partial<Product>>({
  sku: '',
  productName: '',
  quantity: 0,
  safetyQuantity: 0,
  suggestPrice: 0,
  costPrice: 0,
  status: 'active'
})

watch(() => props.product, (newVal) => {
  if (newVal !== null && newVal !== undefined) {
    if (newVal.id) {
      // Editing existing product
      formData.value = { ...newVal }
      isEdit.value = true
    } else {
      // Creating new product
      resetForm()
      isEdit.value = false
    }
    visible.value = true
  }
})

function resetForm() {
  formData.value = {
    sku: '',
    productName: '',
    quantity: 0,
    safetyQuantity: 0,
    suggestPrice: 0,
    costPrice: 0,
    status: 'active'
  }
  isEdit.value = false
}

async function handleSubmit() {
  loading.value = true
  try {
    let result: Product
    if (isEdit.value) {
      result = await productAPI.update(formData.value.id!, formData.value) as unknown as Product
      ElMessage.success('更新成功')
    } else {
      result = await productAPI.create(formData.value) as unknown as Product
      ElMessage.success('建立成功')
    }
    emit('saved', result)
    handleClose()
  } catch (error) {
    ElMessage.error('操作失敗')
    console.error(error)
  } finally {
    loading.value = false
  }
}

function handleClose() {
  visible.value = false
  emit('close')
}
</script>
