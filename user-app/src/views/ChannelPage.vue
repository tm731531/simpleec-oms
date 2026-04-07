<template>
  <div>
    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px">
      <h2 style="margin: 0">通路管理</h2>
      <el-button type="primary" @click="openCreate">新增通路</el-button>
    </div>

    <ChannelHealthOverview style="margin-bottom: 24px" />

    <el-card v-loading="loading">
      <div v-if="channels.length === 0 && !loading" style="text-align: center; padding: 40px; color: #909399">
        尚無通路資料，點擊「新增通路」開始設定
      </div>
      <el-row :gutter="20">
        <el-col v-for="ch in channels" :key="ch.id" :xs="24" :sm="12" :lg="8" style="margin-bottom: 20px">
          <el-card shadow="hover" class="channel-card">
            <template #header>
              <div style="display: flex; justify-content: space-between; align-items: center">
                <div>
                  <span style="font-weight: bold; font-size: 15px">{{ ch.channelName }}</span>
                  <el-tag size="small" style="margin-left: 8px">{{ ch.platformName }}</el-tag>
                </div>
                <el-tag :type="ch.actived ? 'success' : 'info'" size="small">
                  {{ ch.actived ? '啟用' : '停用' }}
                </el-tag>
              </div>
            </template>

            <template v-if="ch.oauthFlow">
              <div class="info-row">
                <span class="label">授權狀態</span>
                <el-tag :type="getOAuthTagType(ch.oauthStatus)" size="small">{{ getOAuthStatusText(ch.oauthStatus) }}</el-tag>
              </div>
              <div v-if="ch.tokenExpiresAt" class="info-row">
                <span class="label">Token 到期</span>
                <span :style="{ color: isExpiringSoon(ch.tokenExpiresInMinutes) ? '#f56c6c' : '#606266', fontSize: '12px' }">
                  {{ formatExpiry(ch.tokenExpiresAt, ch.tokenExpiresInMinutes) }}
                </span>
              </div>
            </template>

            <template v-for="i in 5" :key="i">
              <div v-if="getTokenMasked(ch, i)" class="info-row">
                <span class="label">{{ getTokenLabel(ch, i) }}</span>
                <span class="token-masked">{{ getTokenMasked(ch, i) }}</span>
              </div>
            </template>

            <div class="info-row" style="margin-top: 8px">
              <span class="label">寫入通路</span>
              <el-tag :type="ch.writeActived ? 'warning' : 'info'" size="small">{{ ch.writeActived ? '啟用' : '停用' }}</el-tag>
            </div>
            <div class="info-row">
              <span class="label">自動同步</span>
              <el-tag :type="ch.enableSync ? 'primary' : 'info'" size="small">{{ ch.enableSync ? '啟用' : '停用' }}</el-tag>
            </div>
            <div v-if="ch.lastSyncTime" class="info-row" style="margin-top: 4px; border-top: 1px solid #f0f0f0; padding-top: 6px">
              <span class="label">最後同步</span>
              <span style="color: #909399; font-size: 12px">{{ formatTime(ch.lastSyncTime) }}</span>
            </div>

            <div style="margin-top: 12px; display: flex; gap: 8px; flex-wrap: wrap">
              <el-button size="small" @click="openEdit(ch)">編輯</el-button>
              <el-button size="small" :type="ch.actived ? 'warning' : 'success'" @click="handleToggle(ch)">
                {{ ch.actived ? '停用' : '啟用' }}
              </el-button>
              <el-button size="small" type="info" :loading="syncingChannels.has(ch.id)" @click="handleSyncSellPack(ch)">
                同步商品
              </el-button>
              <el-button size="small" @click="openLogs(ch)">日誌</el-button>
              <template v-if="ch.oauthFlow === 'shopee_oauth'">
                <el-button size="small" type="primary" @click="handleShopeeConnect(ch)">
                  {{ ch.oauthStatus === 'authorized' ? '重新授權' : '連結蝦皮' }}
                </el-button>
                <el-button v-if="ch.oauthStatus === 'authorized'" size="small" @click="handleShopeeRefresh(ch)">刷新 Token</el-button>
                <el-button v-if="ch.oauthStatus === 'authorized'" size="small" type="danger" plain @click="handleShopeeDisconnect(ch)">解除授權</el-button>
              </template>
            </div>
          </el-card>
        </el-col>
      </el-row>
    </el-card>

    <!-- 同步日誌 Drawer -->
    <el-drawer v-model="logsVisible" :title="`${logsChannel?.channelName ?? ''} — 同步日誌`" size="720px">
      <el-tabs v-model="logsTab" @tab-change="onLogsTabChange">

        <!-- 通路日誌 tab -->
        <el-tab-pane label="通路日誌" name="channel">
          <el-table :data="logsList" v-loading="logsLoading" size="small" style="width: 100%">
            <el-table-column prop="syncType" label="類型" width="200" />
            <el-table-column label="狀態" width="90">
              <template #default="{ row }">
                <el-tag :type="row.status === 'success' ? 'success' : (row.status === 'skipped' ? 'info' : 'danger')" size="small">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="健康" width="90">
              <template #default="{ row }">
                <el-tag v-if="row.health" :type="row.health === 'healthy' ? 'success' : (row.health === 'unknown' ? 'info' : 'danger')" size="small">{{ row.health }}</el-tag>
                <span v-else style="color:#ccc">—</span>
              </template>
            </el-table-column>
            <el-table-column prop="httpStatus" label="HTTP" width="70" />
            <el-table-column prop="errorMessage" label="錯誤訊息" min-width="160" show-overflow-tooltip />
            <el-table-column label="時間" width="160">
              <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
            </el-table-column>
          </el-table>
          <el-pagination
            v-model:current-page="logsPage"
            v-model:page-size="logsPageSize"
            :total="logsTotal"
            :page-sizes="[20, 50, 100]"
            layout="total, sizes, prev, pager, next"
            style="margin-top: 16px; text-align: right"
            @current-change="loadLogs"
            @size-change="loadLogs"
          />
        </el-tab-pane>

        <!-- 平台日誌 tab -->
        <el-tab-pane :label="`平台日誌（${logsChannel?.platformName ?? ''}）`" name="platform">
          <el-table :data="platformLogsList" v-loading="platformLogsLoading" size="small" style="width: 100%">
            <el-table-column prop="syncType" label="類型" width="200" />
            <el-table-column label="狀態" width="90">
              <template #default="{ row }">
                <el-tag :type="row.status === 'success' ? 'success' : (row.status === 'skipped' ? 'info' : 'danger')" size="small">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="健康" width="90">
              <template #default="{ row }">
                <el-tag v-if="row.health" :type="row.health === 'healthy' ? 'success' : (row.health === 'unknown' ? 'info' : 'danger')" size="small">{{ row.health }}</el-tag>
                <span v-else style="color:#ccc">—</span>
              </template>
            </el-table-column>
            <el-table-column prop="httpStatus" label="HTTP" width="70" />
            <el-table-column prop="errorMessage" label="錯誤訊息" min-width="160" show-overflow-tooltip />
            <el-table-column label="時間" width="160">
              <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
            </el-table-column>
          </el-table>
          <el-pagination
            v-model:current-page="platformLogsPage"
            v-model:page-size="platformLogsPageSize"
            :total="platformLogsTotal"
            :page-sizes="[20, 50, 100]"
            layout="total, sizes, prev, pager, next"
            style="margin-top: 16px; text-align: right"
            @current-change="loadPlatformLogs"
            @size-change="loadPlatformLogs"
          />
        </el-tab-pane>

      </el-tabs>
    </el-drawer>

    <!-- 新增/編輯 Dialog -->
    <el-dialog v-model="formVisible" :key="editingChannel?.id ?? 'new'" :title="editingChannel ? '編輯通路' : '新增通路'" width="540px" @close="onFormClose">
      <el-form :model="formData" label-width="120px">
        <!-- 新增時選平台 -->
        <el-form-item v-if="!editingChannel" label="平台">
          <el-select v-model="formData.platformId" placeholder="選擇平台" style="width: 100%" @change="onPlatformChange">
            <el-option v-for="p in platforms" :key="p.id" :label="p.platformName" :value="p.id" />
          </el-select>
        </el-form-item>

        <el-form-item label="通路名稱">
          <el-input v-model="formData.channelName" placeholder="例：蝦皮官方店" />
        </el-form-item>

        <el-form-item label="通路編號">
          <el-input v-model="formData.channelSn" placeholder="平台給的店家/賣場 ID" />
        </el-form-item>

        <!-- Token 欄位：永遠顯示 token1~5，label 優先使用 tokenLabels，沒有就顯示預設 -->
        <el-divider content-position="left" style="margin: 16px 0 8px; font-size: 13px; color: #909399">
          🔑 認證憑證
        </el-divider>
        <div style="color: #909399; font-size: 12px; margin-bottom: 12px">
          💡 編輯模式下，留空表示不修改原有憑證，輸入新值才會更新
        </div>
        <template v-for="i in 5" :key="i">
          <el-form-item :label="getFormTokenLabel(i)">
            <el-input
              v-model="formTokens[i - 1]"
              :placeholder="editingChannel ? '留空表示不修改' : ''"
              type="password"
              show-password
            />
          </el-form-item>
        </template>

        <!-- OAuth 按鈕區（由 platform capabilities.oauthFlow 決定要不要顯示） -->
        <el-form-item v-if="formOAuthFlow && editingChannel" label="OAuth 授權">
          <div style="display: flex; gap: 8px; flex-wrap: wrap">
            <el-button type="primary" @click="handleShopeeConnect(editingChannel)">
              {{ editingChannel.oauthStatus === 'authorized' ? '重新授權' : '前往授權' }}
            </el-button>
            <el-button v-if="editingChannel.oauthStatus === 'authorized'" @click="handleShopeeRefresh(editingChannel)">
              刷新 Token
            </el-button>
            <el-button v-if="editingChannel.oauthStatus === 'authorized'" type="danger" plain @click="handleShopeeDisconnect(editingChannel)">
              解除授權
            </el-button>
          </div>
        </el-form-item>

        <el-form-item label="寫入通路">
          <el-switch v-model="formData.writeActived" />
        </el-form-item>
        <el-form-item label="自動同步">
          <el-switch v-model="formData.enableSync" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">
          {{ editingChannel ? '更新' : '建立' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { channelAPI, ChannelVO } from '../api/channel'
import ChannelHealthOverview from '../components/ChannelHealthOverview.vue'

const channels = ref<ChannelVO[]>([])
const platforms = ref<any[]>([])
const loading = ref(false)
const saving = ref(false)
const syncingChannels = ref<Set<string>>(new Set())
const logsVisible = ref(false)
const logsChannel = ref<ChannelVO | null>(null)
const logsTab = ref<'channel' | 'platform'>('channel')
const logsList = ref<any[]>([])
const logsLoading = ref(false)
const logsPage = ref(1)
const logsPageSize = ref(20)
const logsTotal = ref(0)
const platformLogsList = ref<any[]>([])
const platformLogsLoading = ref(false)
const platformLogsPage = ref(1)
const platformLogsPageSize = ref(20)
const platformLogsTotal = ref(0)
const formVisible = ref(false)
const editingChannel = ref<ChannelVO | null>(null)
const selectedPlatform = ref<any>(null)

const formData = ref({
  platformId: '',
  channelName: '',
  channelSn: '',
  writeActived: false,
  enableSync: true
})
const formTokens = ref(['', '', '', '', ''])

// oauthFlow 來自選擇的平台（新增）或當前 channel（編輯）
const formOAuthFlow = computed(() => {
  if (editingChannel.value) return editingChannel.value.oauthFlow
  return selectedPlatform.value?.capabilities?.oauthFlow ?? null
})

// Token label：新增模式從 platform capabilities 取，編輯模式從 channel tokenLabels 取
// 永遠回傳 label，沒有 alias 就顯示預設 "Token N"
function getFormTokenLabel(i: number): string {
  const key = i === 1 ? 'token1' : `token${i}`

  if (editingChannel.value) {
    const labels = editingChannel.value.tokenLabels
    if (labels && labels[key]) {
      return labels[key] as string
    }
    return `Token ${i}`
  }

  // 新增模式
  const caps = selectedPlatform.value?.capabilities
  if (caps?.tokenLabels && caps.tokenLabels[key]) {
    return caps.tokenLabels[key]
  }
  return `Token ${i}`
}

onMounted(async () => {
  await Promise.all([loadChannels(), loadPlatforms()])
})

async function loadChannels() {
  loading.value = true
  try {
    const res: any = await channelAPI.listChannels()
    channels.value = Array.isArray(res) ? res : (res?.data ?? [])
  } catch (e) {
    console.error(e)
  } finally {
    loading.value = false
  }
}

async function loadPlatforms() {
  try {
    const res: any = await channelAPI.listPlatforms()
    platforms.value = Array.isArray(res) ? res : (res?.data ?? [])
  } catch (e) {
    console.error(e)
  }
}

function openCreate() {
  editingChannel.value = null
  selectedPlatform.value = null
  formData.value = { platformId: '', channelName: '', channelSn: '', writeActived: false, enableSync: true }
  formTokens.value = ['', '', '', '', '']
  formVisible.value = true
}

async function openEdit(ch: ChannelVO) {
  editingChannel.value = ch
  selectedPlatform.value = platforms.value.find(p => p.id === ch.platformId) ?? null
  formData.value = { platformId: ch.platformId, channelName: ch.channelName, channelSn: ch.channelSn, writeActived: ch.writeActived, enableSync: ch.enableSync }
  formTokens.value = ['', '', '', '', '']
  // 確保 Vue 更新完成後再打開 dialog
  await nextTick()
  formVisible.value = true
}

function onFormClose() {
  editingChannel.value = null
  selectedPlatform.value = null
}

function onPlatformChange(platformId: string) {
  selectedPlatform.value = platforms.value.find(p => p.id === platformId) ?? null
}

async function handleSave() {
  saving.value = true
  try {
    const payload: any = { ...formData.value }
    const tokenKeys = ['token', 'token2', 'token3', 'token4', 'token5']
    formTokens.value.forEach((v, idx) => {
      if (v) payload[tokenKeys[idx]] = v
    })

    if (editingChannel.value) {
      const res: any = await channelAPI.updateChannel(editingChannel.value.id, payload)
      const idx = channels.value.findIndex(c => c.id === (res as ChannelVO).id)
      if (idx >= 0) channels.value[idx] = res as ChannelVO
      ElMessage.success('更新成功')
      formVisible.value = false
    } else {
      const res: any = await channelAPI.createChannel(payload)
      channels.value.push(res as ChannelVO)
      ElMessage.success('建立成功')
      formVisible.value = false
    }
  } catch (e) {
    ElMessage.error('操作失敗')
  } finally {
    saving.value = false
  }
}

function openLogs(ch: ChannelVO) {
  logsChannel.value = ch
  logsTab.value = 'channel'
  logsPage.value = 1
  logsList.value = []
  logsTotal.value = 0
  platformLogsPage.value = 1
  platformLogsList.value = []
  platformLogsTotal.value = 0
  logsVisible.value = true
  loadLogs()
}

function onLogsTabChange(tab: string) {
  if (tab === 'platform' && platformLogsList.value.length === 0) {
    loadPlatformLogs()
  }
}

async function loadLogs() {
  if (!logsChannel.value) return
  logsLoading.value = true
  try {
    const res: any = await channelAPI.getSyncLogs(logsChannel.value.id, logsPage.value, logsPageSize.value)
    logsList.value = res?.data ?? []
    logsTotal.value = res?.pagination?.total ?? 0
  } catch (e) {
    console.error(e)
  } finally {
    logsLoading.value = false
  }
}

async function loadPlatformLogs() {
  if (!logsChannel.value?.platformId) return
  platformLogsLoading.value = true
  try {
    const res: any = await channelAPI.getPlatformSyncLogs(logsChannel.value.platformId, platformLogsPage.value, platformLogsPageSize.value)
    platformLogsList.value = res?.data ?? []
    platformLogsTotal.value = res?.pagination?.total ?? 0
  } catch (e) {
    console.error(e)
  } finally {
    platformLogsLoading.value = false
  }
}

async function handleSyncSellPack(ch: ChannelVO) {
  syncingChannels.value = new Set([...syncingChannels.value, ch.id])
  try {
    await channelAPI.syncSellPack(ch.id)
    ElMessage.success('同步已觸發，商品資料將在背景更新')
  } catch (e) {
    ElMessage.error('觸發同步失敗')
  } finally {
    syncingChannels.value.delete(ch.id)
    syncingChannels.value = new Set(syncingChannels.value)
  }
}

async function handleToggle(ch: ChannelVO) {
  try {
    const res: any = await channelAPI.toggleStatus(ch.id)
    const idx = channels.value.findIndex(c => c.id === ch.id)
    if (idx >= 0) channels.value[idx] = res as ChannelVO
    ElMessage.success((res as ChannelVO).actived ? '已啟用' : '已停用')
  } catch (e) {
    ElMessage.error('操作失敗')
  }
}

async function handleShopeeConnect(ch: ChannelVO) {
  try {
    const res: any = await channelAPI.getShopeeAuthUrl(ch.id)
    window.open(res?.authUrl ?? res, 'shopee_oauth', 'width=600,height=700')
    ElMessage.info('請在彈出視窗完成授權，完成後重新整理頁面')
  } catch (e) {
    ElMessage.error('無法取得授權連結')
  }
}

async function handleShopeeRefresh(ch: ChannelVO) {
  try {
    const res: any = await channelAPI.refreshShopeeToken(ch.id)
    const idx = channels.value.findIndex(c => c.id === ch.id)
    if (idx >= 0) channels.value[idx] = res as ChannelVO
    if (editingChannel.value?.id === ch.id) editingChannel.value = res as ChannelVO
    ElMessage.success('Token 已刷新')
  } catch (e) {
    ElMessage.error('刷新失敗')
  }
}

async function handleShopeeDisconnect(ch: ChannelVO) {
  try {
    await ElMessageBox.confirm('確定解除授權？', '警告', { type: 'warning' })
    const res: any = await channelAPI.disconnectShopee(ch.id)
    const idx = channels.value.findIndex(c => c.id === ch.id)
    if (idx >= 0) channels.value[idx] = res as ChannelVO
    if (editingChannel.value?.id === ch.id) editingChannel.value = res as ChannelVO
    ElMessage.success('已解除授權')
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error('操作失敗')
  }
}

// ── 卡片顯示用 helpers ──

function getTokenMasked(ch: ChannelVO, i: number) {
  return (ch as any)[`token${i}Masked`]
}
function getTokenLabel(ch: ChannelVO, i: number): string {
  const key = i === 1 ? 'token1' : `token${i}`
  return ch.tokenLabels?.[key] ?? `Token ${i}`
}
function getOAuthTagType(status?: string) {
  return ({ authorized: 'success', expired: 'danger', pending: 'warning' } as any)[status ?? ''] ?? 'info'
}
function getOAuthStatusText(status?: string) {
  return ({ authorized: '已授權', expired: 'Token 已過期', pending: '待授權' } as any)[status ?? ''] ?? (status ?? '未知')
}
function isExpiringSoon(minutes?: number) {
  return minutes != null && minutes < 120
}
function formatExpiry(expiresAt: string, minutes?: number) {
  const date = new Date(expiresAt).toLocaleString('zh-TW')
  if (minutes == null) return date
  if (minutes <= 0) return `${date}（已過期）`
  if (minutes < 60) return `${date}（${minutes} 分後過期）`
  return `${date}（${Math.floor(minutes / 60)} 小時後過期）`
}
function formatTime(t?: string) {
  if (!t) return '-'
  return new Date(t).toLocaleString('zh-TW')
}
</script>

<style scoped>
.channel-card { height: 100%; }
.info-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 3px 0;
  font-size: 13px;
}
.label { color: #909399; flex-shrink: 0; margin-right: 8px; }
.token-masked {
  font-family: monospace;
  font-size: 12px;
  color: #606266;
  word-break: break-all;
  text-align: right;
}
</style>
