<template>
  <div>
    <el-input
      v-model="searchKeyword"
      placeholder="搜尋商品"
      style="margin-bottom: 15px; width: 300px"
      @input="handleSearch"
    />

    <el-table :data="products" style="width: 100%" v-loading="loading">
      <el-table-column prop="sku" label="SKU" width="120" />
      <el-table-column prop="productName" label="商品名稱" min-width="150" />
      <el-table-column prop="quantity" label="庫存" width="80" />
      <el-table-column prop="suggestPrice" label="建議售價" width="100" />
      <el-table-column prop="status" label="狀態" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 'active' ? 'success' : 'danger'">
            {{ row.status === 'active' ? '啟用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150">
        <template #default="{ row }">
          <el-button link type="primary" @click="$emit('edit', row)">編輯</el-button>
          <el-popconfirm title="確定刪除?" @confirm="$emit('delete', row.id)">
            <template #reference>
              <el-button link type="danger">刪除</el-button>
            </template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="currentPage"
      v-model:page-size="pageSize"
      :page-sizes="[10, 20, 50]"
      :total="total"
      layout="total, sizes, prev, pager, next"
      style="margin-top: 20px; text-align: right"
      @current-page-change="loadData"
      @page-size-change="loadData"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onMounted } from 'vue'
import { productAPI } from '../api/product'
import { Product } from '../types'

const props = defineProps<{
  refresh: number
}>()

const emit = defineEmits<{
  edit: [product: Product]
  delete: [id: string]
}>()

const products = ref<Product[]>([])
const loading = ref(false)
const searchKeyword = ref('')
const currentPage = ref(1)
const pageSize = ref(20)
const total = ref(0)

onMounted(() => {
  loadData()
})

watch(() => props.refresh, () => {
  loadData()
})

async function loadData() {
  loading.value = true
  try {
    const response: any = await productAPI.list(currentPage.value - 1, pageSize.value)
    products.value = response.data ?? []
    total.value = response.pagination?.total ?? 0
  } catch (error) {
    console.error('Failed to load products:', error)
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  currentPage.value = 1
  loadData()
}
</script>
