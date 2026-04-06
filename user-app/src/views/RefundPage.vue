<template>
  <div>
    <h2>退貨管理</h2>
    <el-card>
      <RefundTable :refresh="refreshCount" @approve="handleApprove" @reject="handleReject" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refund } from '../types'
import { refundAPI } from '../api/refund'
import RefundTable from '../components/RefundTable.vue'

const refreshCount = ref(0)

async function handleApprove(refund: Refund) {
  ElMessageBox.confirm('確定同意這筆退貨?', '確認', {
    confirmButtonText: '同意',
    cancelButtonText: '取消',
    type: 'warning'
  }).then(async () => {
    try {
      await refundAPI.approve(refund.id)
      ElMessage.success('退貨已同意，同步進行中')
      refreshCount.value++
    } catch (error) {
      ElMessage.error('操作失敗')
    }
  }).catch(() => {})
}

async function handleReject(refund: Refund) {
  ElMessageBox.prompt('請輸入拒絕原因', '拒絕退貨', {
    confirmButtonText: '確定',
    cancelButtonText: '取消'
  }).then(async ({ value }) => {
    try {
      await refundAPI.reject(refund.id, value)
      ElMessage.success('退貨已拒絕')
      refreshCount.value++
    } catch (error) {
      ElMessage.error('操作失敗')
    }
  }).catch(() => {})
}
</script>
