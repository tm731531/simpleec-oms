<template>
  <div class="inventory-page">
    <h3 class="page-title">庫存管理</h3>

    <el-card class="filter-bar">
      <el-row :gutter="12" align="middle">
        <el-col :span="6">
          <el-checkbox v-model="showLowStock" @change="handleSearch">只看低庫存</el-checkbox>
        </el-col>
        <el-col :span="4">
          <el-button type="primary" @click="handleSearch">重整</el-button>
        </el-col>
      </el-row>
    </el-card>

    <el-card style="margin-top:16px">
      <el-table :data="products" v-loading="loading" stripe>
        <el-table-column prop="sku" label="SKU" width="140" />
        <el-table-column prop="productName" label="商品名稱" min-width="200" />
        <el-table-column prop="specSummary" label="規格" width="110" />
        <el-table-column label="現有庫存" width="100" align="right">
          <template #default="{ row }">
            <span :class="{ 'low-stock': isLow(row) }">{{ row.quantity ?? 0 }}</span>
          </template>
        </el-table-column>
        <el-table-column label="安全庫存" width="100" align="right">
          <template #default="{ row }">{{ row.safetyQuantity ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="狀態" width="80">
          <template #default="{ row }">
            <el-tag :type="isLow(row) ? 'danger' : 'success'" size="small">
              {{ isLow(row) ? '低庫存' : '正常' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="openAdjust(row)">調整</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-bar">
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          :total="total"
          :page-sizes="[20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @size-change="s => { pageSize = s; currentPage = 1; loadProducts() }"
          @current-change="p => { currentPage = p; loadProducts() }"
        />
      </div>
    </el-card>

    <el-dialog v-model="adjustVisible" title="調整庫存" width="420px" :close-on-click-modal="false">
      <div v-if="adjustTarget">
        <p style="margin-bottom:16px">
          <strong>{{ adjustTarget.productName }}</strong>（{{ adjustTarget.sku }}）<br>
          <span style="color:#666">目前庫存：{{ adjustTarget.quantity ?? 0 }} / 安全庫存：{{ adjustTarget.safetyQuantity ?? '未設定' }}</span>
        </p>
        <el-form label-width="90px">
          <el-form-item label="新庫存量">
            <el-input-number v-model="newQuantity" :min="0" style="width:160px" />
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="adjustVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitAdjust">確認調整</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { inventoryAPI, InventoryItem } from '../api/inventory'

type Product = InventoryItem

const products = ref<Product[]>([])
const loading = ref(false)
const saving = ref(false)
const showLowStock = ref(false)
const currentPage = ref(1)
const pageSize = ref(20)
const total = ref(0)
const adjustVisible = ref(false)
const adjustTarget = ref<Product | null>(null)
const newQuantity = ref(0)

function isLow(row: Product): boolean {
  if (!row.safetyQuantity) return false
  return (row.quantity ?? 0) < row.safetyQuantity
}

async function loadProducts() {
  loading.value = true
  try {
    const res: any = showLowStock.value
      ? await inventoryAPI.listLowStock(currentPage.value, pageSize.value)
      : await inventoryAPI.list(currentPage.value, pageSize.value)
    products.value = res?.data ?? []
    total.value = res?.pagination?.total ?? products.value.length
  } catch {
    ElMessage.error('載入庫存失敗')
  } finally {
    loading.value = false
  }
}

function handleSearch() { currentPage.value = 1; loadProducts() }

function openAdjust(row: Product) {
  adjustTarget.value = row
  newQuantity.value = row.quantity ?? 0
  adjustVisible.value = true
}

async function submitAdjust() {
  if (!adjustTarget.value) return
  saving.value = true
  try {
    await inventoryAPI.update(adjustTarget.value.id, newQuantity.value)
    ElMessage.success('庫存已更新')
    adjustVisible.value = false
    loadProducts()
  } catch {
    ElMessage.error('更新失敗')
  } finally {
    saving.value = false
  }
}

onMounted(loadProducts)
</script>

<style scoped>
.inventory-page { padding: 0; }
.page-title { margin: 0 0 16px; font-size: 20px; color: #333; }
.filter-bar { margin-bottom: 0; }
.pagination-bar { display: flex; justify-content: flex-end; margin-top: 16px; }
.low-stock { color: #f56c6c; font-weight: 600; }
</style>
