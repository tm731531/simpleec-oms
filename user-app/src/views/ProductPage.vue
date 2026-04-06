<template>
  <div>
    <div class="page-header">
      <h2>商品管理</h2>
      <el-button type="primary" @click="handleNewProduct">+ 新增商品</el-button>
    </div>
    <el-card>
      <ProductTable :refresh="refreshCount" @edit="handleEditProduct" @delete="handleDeleteProduct" />
    </el-card>
    <ProductForm :product="selectedProduct" @saved="handleSaved" @close="handleFormClose" />
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Product } from '../types'
import { productAPI } from '../api/product'
import ProductTable from '../components/ProductTable.vue'
import ProductForm from '../components/ProductForm.vue'

const selectedProduct = ref<Product | null | undefined>(undefined)
const refreshCount = ref(0)

function handleNewProduct() {
  // Pass an empty product-like object to trigger form open in create mode
  selectedProduct.value = {} as Product
}

function handleEditProduct(product: Product) {
  selectedProduct.value = product
}

async function handleDeleteProduct(id: string) {
  try {
    await productAPI.delete(id)
    ElMessage.success('刪除成功')
    refreshCount.value++
  } catch (error) {
    ElMessage.error('刪除失敗')
  }
}

function handleSaved() {
  refreshCount.value++
  selectedProduct.value = undefined
}

function handleFormClose() {
  selectedProduct.value = undefined
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
