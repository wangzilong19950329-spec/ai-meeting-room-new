<template>
  <div class="tracking-page">
    <div class="hero">
      <div>
        <div class="hero-title">追踪中心</div>
        <div class="hero-subtitle">集中查看会议追踪项、自定义 watch，以及每天的自动刷新结果。</div>
      </div>
      <div class="hero-actions">
        <el-button @click="loadOverview" :loading="loading">刷新总览</el-button>
        <el-button type="primary" @click="showCreate = true">新增追踪项</el-button>
      </div>
    </div>

    <div class="stat-grid">
      <el-card shadow="hover"><div class="stat-num">{{ overview.totalCount || 0 }}</div><div class="stat-label">总追踪项</div></el-card>
      <el-card shadow="hover"><div class="stat-num">{{ overview.autoRefreshCount || 0 }}</div><div class="stat-label">自动刷新中</div></el-card>
      <el-card shadow="hover"><div class="stat-num">{{ overview.dueSoonCount || 0 }}</div><div class="stat-label">1小时内待刷新</div></el-card>
      <el-card shadow="hover"><div class="stat-num danger">{{ overview.materialChangeCount || 0 }}</div><div class="stat-label">关键变化</div></el-card>
      <el-card shadow="hover"><div class="stat-num">{{ overview.meetingLinkedCount || 0 }}</div><div class="stat-label">会议关联</div></el-card>
      <el-card shadow="hover"><div class="stat-num">{{ overview.customCount || 0 }}</div><div class="stat-label">自定义 watch</div></el-card>
    </div>

    <el-card shadow="never" class="filter-card">
      <div class="filters">
        <el-input v-model="keyword" clearable placeholder="筛选标题、描述、查询语句" class="filter-input" @keyup.enter="applyFilters" @clear="applyFilters" />
        <el-select v-model="sourceFilter" clearable placeholder="来源类型" class="filter-select">
          <el-option label="会议追踪" value="MEETING" />
          <el-option label="自定义" value="CUSTOM" />
        </el-select>
        <el-select v-model="severityFilter" clearable placeholder="变化等级" class="filter-select">
          <el-option label="NONE" value="NONE" />
          <el-option label="MINOR" value="MINOR" />
          <el-option label="MATERIAL" value="MATERIAL" />
        </el-select>
        <el-select v-model="statusFilter" clearable placeholder="状态" class="filter-select">
          <el-option v-for="item in trackingStatuses" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <div class="filter-actions">
          <el-button type="primary" @click="applyFilters">搜索</el-button>
          <el-button @click="resetFilters" :disabled="!hasActiveFilter">重置</el-button>
        </div>
      </div>
    </el-card>

    <div class="tracking-list">
      <el-card v-for="item in filteredItems" :key="item.id" shadow="hover" class="tracking-card">
        <div class="card-head">
          <div>
            <div class="card-title-row">
              <span class="card-title">{{ item.title }}</span>
              <el-tag size="small" :type="severityTagType(item.changeSeverity)" effect="dark">{{ item.changeSeverity || 'NONE' }}</el-tag>
              <el-tag size="small" effect="plain">{{ item.statusName }}</el-tag>
              <el-tag v-if="item.sourceType" size="small" type="info" effect="plain">{{ item.sourceType }}</el-tag>
            </div>
            <div class="card-meta">
              <span v-if="item.meetingId">关联会议：<router-link :to="`/meetings/${item.meetingId}`">{{ item.meetingTitle || `#${item.meetingId}` }}</router-link></span>
              <span>刷新模式：{{ item.refreshMode || 'DAILY' }}{{ item.refreshIntervalHours ? ` / ${item.refreshIntervalHours}h` : '' }}</span>
              <span v-if="item.nextCheckAt">下次刷新：{{ formatDateTime(item.nextCheckAt) }}</span>
            </div>
          </div>
          <div class="card-actions">
            <el-button size="small" @click="refreshItem(item)" :loading="refreshingIds.has(item.id)">立即刷新</el-button>
            <el-button
              v-if="item.meetingId && item.changeSeverity === 'MATERIAL'"
              size="small"
              type="warning"
              @click="reopenMeeting(item)"
            >
              重开会议
            </el-button>
          </div>
        </div>

        <div v-if="item.description" class="card-desc">{{ item.description }}</div>
        <div v-if="item.watchQuery" class="watch-query">监控查询：{{ item.watchQuery }}</div>
        <div v-if="item.lastCheckNote" class="last-note">
          <TrackingRefreshNote :text="item.lastCheckNote" />
        </div>

        <div class="flag-row">
          <el-tag v-if="item.autoRefreshEnabled" size="small" type="success" effect="plain">自动刷新</el-tag>
          <el-tag v-if="item.reopenTrigger" size="small" type="warning" effect="plain">可触发重开</el-tag>
          <el-tag v-if="item.requiredForContinue" size="small" type="danger" effect="plain">继续前必需</el-tag>
          <el-tag v-if="item.reopenPolicy" size="small" type="info" effect="plain">重开策略 {{ item.reopenPolicy }}</el-tag>
          <el-tag v-if="item.todayUpdateCount" size="small" effect="plain">今日更新 {{ item.todayUpdateCount }}</el-tag>
        </div>

        <div class="inline-actions">
          <el-button v-if="['OPEN','BLOCKED'].includes(item.status)" size="small" plain @click="updateStatus(item, 'IN_PROGRESS')">开始处理</el-button>
          <el-button v-if="['OPEN','IN_PROGRESS','READY_FOR_REVIEW','BLOCKED'].includes(item.status)" size="small" type="success" plain @click="updateStatus(item, 'VERIFIED')">标记已验证</el-button>
          <el-button v-if="!['CLOSED','VERIFIED'].includes(item.status)" size="small" type="info" plain @click="updateStatus(item, 'CLOSED')">关闭</el-button>
          <el-button v-if="!['REJECTED','CLOSED'].includes(item.status)" size="small" type="danger" plain @click="updateStatus(item, 'REJECTED')">驳回</el-button>
        </div>

        <div v-if="item.todayUpdates?.length" class="today-box">
          <div class="section-title">今日追踪明细</div>
          <div v-for="update in item.todayUpdates" :key="update.id" class="update-row">
            <div class="update-head">
              <el-tag size="small" :type="updateTagType(update)" effect="plain">{{ updateLabel(update) }}</el-tag>
              <span>{{ formatDateTime(update.gmtCreate) }}</span>
            </div>
            <div v-if="update.content" class="update-content">
              <TrackingRefreshNote :text="update.content" />
            </div>
            <div v-if="update.evidenceRef" class="update-evidence">证据：{{ update.evidenceRef }}</div>
          </div>
        </div>
      </el-card>

      <el-empty v-if="!loading && filteredItems.length === 0" description="暂无追踪项" />
    </div>

    <el-dialog v-model="showCreate" title="新增追踪项" width="720px">
      <el-form :model="createForm" label-width="110px">
        <el-form-item label="标题">
          <el-input v-model="createForm.title" placeholder="例如：2026年下半年金价预测" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="createForm.description" type="textarea" :rows="3" placeholder="说明跟踪目标和关注点" />
        </el-form-item>
        <el-form-item label="关联会议">
          <el-select v-model="createForm.meetingId" clearable filterable placeholder="可选，绑定到某场会议">
            <el-option v-for="meeting in meetings" :key="meeting.meetingId" :label="meeting.title" :value="meeting.meetingId" />
          </el-select>
        </el-form-item>
        <el-form-item label="监控查询">
          <el-input v-model="createForm.watchQuery" placeholder="为空则默认使用标题" />
        </el-form-item>
        <el-form-item label="刷新模式">
          <el-select v-model="createForm.refreshMode">
            <el-option label="每天一次" value="DAILY" />
            <el-option label="间隔刷新" value="INTERVAL" />
            <el-option label="仅手动" value="MANUAL" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="createForm.refreshMode === 'INTERVAL'" label="刷新间隔">
          <el-input-number v-model="createForm.refreshIntervalHours" :min="1" :max="336" />
          <span class="field-tip">小时</span>
        </el-form-item>
        <el-form-item label="重开策略">
          <el-select v-model="createForm.reopenPolicy">
            <el-option label="人工决定" value="MANUAL" />
            <el-option label="建议重开" value="SUGGEST" />
            <el-option label="自动重开" value="AUTO" />
          </el-select>
        </el-form-item>
        <el-form-item label="附加选项">
          <el-checkbox v-model="createForm.autoRefreshEnabled">自动刷新</el-checkbox>
          <el-checkbox v-model="createForm.reopenTrigger">可触发重开</el-checkbox>
          <el-checkbox v-model="createForm.requiredForContinue">继续前必需</el-checkbox>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreate = false">取消</el-button>
        <el-button type="primary" @click="createItem" :loading="creating">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import dayjs from 'dayjs'
import { ElMessage } from 'element-plus'
import { meetingApi } from '@/api/meeting'
import TrackingRefreshNote from '@/components/TrackingRefreshNote.vue'

const loading = ref(false)
const creating = ref(false)
const showCreate = ref(false)
const overview = reactive({
  totalCount: 0,
  autoRefreshCount: 0,
  dueSoonCount: 0,
  materialChangeCount: 0,
  meetingLinkedCount: 0,
  customCount: 0,
  items: []
})
const meetings = ref([])
const keyword = ref('')
const sourceFilter = ref('')
const severityFilter = ref('')
const statusFilter = ref('')
const refreshingIds = ref(new Set())

// 输入框是“草稿”，点「搜索」或回车后才提交到 applied，列表据此过滤。
const applied = reactive({ keyword: '', source: '', severity: '', status: '' })

const createForm = reactive({
  creatorId: 1,
  meetingId: null,
  title: '',
  description: '',
  watchQuery: '',
  refreshMode: 'DAILY',
  refreshIntervalHours: 24,
  reopenPolicy: 'MANUAL',
  autoRefreshEnabled: true,
  reopenTrigger: false,
  requiredForContinue: false
})

const trackingStatuses = [
  { label: '待处理', value: 'OPEN' },
  { label: '处理中', value: 'IN_PROGRESS' },
  { label: '已阻塞', value: 'BLOCKED' },
  { label: '待复核', value: 'READY_FOR_REVIEW' },
  { label: '已验证', value: 'VERIFIED' },
  { label: '已驳回', value: 'REJECTED' },
  { label: '已关闭', value: 'CLOSED' }
]

const filteredItems = computed(() => {
  const q = applied.keyword.trim().toLowerCase()
  return (overview.items || []).filter(item => {
    if (applied.source && item.sourceType !== applied.source) return false
    if (applied.severity && item.changeSeverity !== applied.severity) return false
    if (applied.status && item.status !== applied.status) return false
    if (!q) return true
    return [item.title, item.description, item.watchQuery, item.meetingTitle]
      .filter(Boolean)
      .some(text => String(text).toLowerCase().includes(q))
  })
})

const hasActiveFilter = computed(() =>
  !!(keyword.value || sourceFilter.value || severityFilter.value || statusFilter.value ||
     applied.keyword || applied.source || applied.severity || applied.status)
)

function applyFilters() {
  applied.keyword = keyword.value
  applied.source = sourceFilter.value
  applied.severity = severityFilter.value
  applied.status = statusFilter.value
}

function resetFilters() {
  keyword.value = ''
  sourceFilter.value = ''
  severityFilter.value = ''
  statusFilter.value = ''
  applyFilters()
}

function formatDateTime(value) {
  return value ? dayjs(value).format('MM-DD HH:mm') : '-'
}

function severityTagType(value) {
  if (value === 'MATERIAL') return 'danger'
  if (value === 'MINOR') return 'warning'
  return 'info'
}

function updateTagType(update) {
  if (!update) return 'info'
  if (String(update.updateType || '').includes('ERROR')) return 'danger'
  if (String(update.updateType || '').includes('REFRESH')) return 'success'
  if (update.statusAfter === 'VERIFIED') return 'success'
  if (update.statusAfter === 'REJECTED') return 'danger'
  return 'info'
}

function updateLabel(update) {
  if (!update) return '更新'
  if (update.updateType === 'AUTO_REFRESH') return '自动刷新'
  if (update.updateType === 'MANUAL_REFRESH') return '手动刷新'
  if (update.updateType === 'AUTO_REFRESH_ERROR') return '刷新失败'
  if (update.statusAfter) {
    const status = trackingStatuses.find(item => item.value === update.statusAfter)
    return status ? `状态 -> ${status.label}` : `状态 -> ${update.statusAfter}`
  }
  return update.updateType || '更新'
}

async function loadOverview() {
  loading.value = true
  try {
    const [overviewResp, meetingsResp] = await Promise.all([
      meetingApi.getTrackingOverview(),
      meetingApi.list(1)
    ])
    Object.assign(overview, overviewResp.data || {})
    meetings.value = meetingsResp.data || []
  } finally {
    loading.value = false
  }
}

async function createItem() {
  if (!createForm.title.trim()) {
    ElMessage.warning('标题不能为空')
    return
  }
  creating.value = true
  try {
    await meetingApi.createCustomTrackingItem({ ...createForm })
    ElMessage.success('追踪项已创建')
    showCreate.value = false
    Object.assign(createForm, {
      creatorId: 1,
      meetingId: null,
      title: '',
      description: '',
      watchQuery: '',
      refreshMode: 'DAILY',
      refreshIntervalHours: 24,
      reopenPolicy: 'MANUAL',
      autoRefreshEnabled: true,
      reopenTrigger: false,
      requiredForContinue: false
    })
    await loadOverview()
  } finally {
    creating.value = false
  }
}

async function refreshItem(item) {
  const next = new Set(refreshingIds.value)
  next.add(item.id)
  refreshingIds.value = next
  try {
    await meetingApi.refreshTrackingItem(item.id)
    ElMessage.success('已完成刷新')
    await loadOverview()
  } finally {
    const done = new Set(refreshingIds.value)
    done.delete(item.id)
    refreshingIds.value = done
  }
}

async function updateStatus(item, status) {
  await meetingApi.updateTrackingStatus(item.id, status, '')
  ElMessage.success('追踪项状态已更新')
  await loadOverview()
}

async function reopenMeeting(item) {
  await meetingApi.reopenMeetingFromTracking(item.id)
  ElMessage.success('已触发会议重开')
  await loadOverview()
}

onMounted(loadOverview)
</script>

<style scoped>
.tracking-page { display: flex; flex-direction: column; gap: 16px; }
.hero { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; }
.hero-title { font-size: 28px; font-weight: 700; color: #18243b; }
.hero-subtitle { margin-top: 6px; color: #5d6b82; }
.hero-actions { display: flex; gap: 10px; }
.stat-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 12px; }
.stat-num { font-size: 28px; font-weight: 700; color: #162032; }
.stat-num.danger { color: #c2410c; }
.stat-label { margin-top: 6px; color: #64748b; font-size: 13px; }
.filter-card { border-radius: 18px; }
.filters { display: grid; grid-template-columns: minmax(200px, 1.6fr) repeat(3, minmax(130px, .8fr)) auto; gap: 12px; align-items: center; }
.filter-input, .filter-select { width: 100%; }
.filter-actions { display: flex; gap: 8px; align-items: center; }
.tracking-list { display: grid; gap: 14px; }
.tracking-card { border-radius: 20px; border: 1px solid #e7ebf3; }
.card-head { display: flex; justify-content: space-between; gap: 14px; }
.card-title-row { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.card-title { font-size: 18px; font-weight: 700; color: #18243b; }
.card-meta { display: flex; flex-wrap: wrap; gap: 14px; margin-top: 8px; color: #64748b; font-size: 13px; }
.card-actions { display: flex; align-items: flex-start; gap: 8px; }
.card-desc, .watch-query, .last-note { margin-top: 10px; color: #334155; line-height: 1.7; }
.watch-query { font-size: 13px; color: #475569; }
.last-note { padding: 12px 14px; border-radius: 14px; background: linear-gradient(180deg, #fff7ed, #fffbeb); border: 1px solid #fed7aa; }
.flag-row, .inline-actions { margin-top: 10px; display: flex; gap: 8px; flex-wrap: wrap; }
.today-box { margin-top: 14px; padding: 14px; border-radius: 16px; background: #f8fafc; border: 1px solid #e2e8f0; }
.section-title { font-weight: 700; color: #1e293b; margin-bottom: 10px; }
.update-row { padding: 10px 0; border-top: 1px solid #e2e8f0; }
.update-row:first-of-type { border-top: none; padding-top: 0; }
.update-head { display: flex; justify-content: space-between; gap: 10px; color: #64748b; font-size: 12px; }
.update-content { margin-top: 8px; color: #334155; line-height: 1.7; }
.update-evidence { margin-top: 6px; color: #475569; font-size: 12px; }
.field-tip { margin-left: 8px; color: #64748b; }

@media (max-width: 900px) {
  .hero { flex-direction: column; align-items: stretch; }
  .hero-actions { justify-content: flex-start; }
  .filters { grid-template-columns: 1fr; }
  .card-head { flex-direction: column; }
}
</style>
