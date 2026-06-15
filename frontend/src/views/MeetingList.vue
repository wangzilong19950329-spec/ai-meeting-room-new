<template>
  <div class="meeting-list">
    <!-- 顶部操作栏 -->
    <div class="page-header">
      <div>
        <h2>我的会议</h2>
        <p class="subtitle">多AI成员协作，减少单模型偏差</p>
      </div>
      <el-button type="primary" @click="$router.push('/meetings/create')">
        <el-icon><Plus /></el-icon> 发起新会议
      </el-button>
    </div>

    <!-- 统计卡片 -->
    <el-row :gutter="12" class="stat-cards">
      <el-col :xs="12" :sm="12" :md="6" v-for="s in stats" :key="s.label">
        <el-card shadow="never" class="stat-card">
          <div class="stat-inner">
            <el-icon :size="30" :color="s.color"><component :is="s.icon" /></el-icon>
            <div>
              <div class="stat-num">{{ s.value }}</div>
              <div class="stat-label">{{ s.label }}</div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 会议列表 -->
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>会议记录</span>
          <div class="header-actions">
            <el-button
              type="danger"
              plain
              :disabled="selectedMeetings.length === 0"
              :loading="batchDeleting"
              @click="handleBatchDelete"
            >
              批量删除<span v-if="selectedMeetings.length">（{{ selectedMeetings.length }}）</span>
            </el-button>
            <span class="batch-hint">
              已选 {{ selectedMeetings.length }} / 当前 {{ deletableFilteredMeetings.length }}
              <template v-if="activeSelectedMeetings.length">，将先停止 {{ activeSelectedMeetings.length }} 个进行中会议</template>
            </span>
            <el-input v-model="search" placeholder="搜索主题..." clearable style="width:180px">
              <template #prefix><el-icon><Search /></el-icon></template>
            </el-input>
          </div>
        </div>
      </template>

      <!-- 桌面端表格 -->
      <el-table :data="filteredMeetings" v-loading="loading" stripe row-key="meetingId"
                class="desktop-table" row-class-name="meeting-row"
                @row-click="row => $router.push(`/meetings/${row.meetingId}`)">
        <el-table-column width="48" align="center">
          <template #header>
            <div class="selection-header" title="选择当前列表中的会议；进行中/修订中会议删除前会先停止">
              <el-checkbox
                :model-value="allFilteredSelected"
                :indeterminate="isFilteredSelectionIndeterminate"
                :disabled="deletableFilteredMeetings.length === 0"
                @click.stop
                @change="toggleAllFiltered"
              />
              <span>全选</span>
            </div>
          </template>
          <template #default="{ row }">
            <el-checkbox
              :model-value="isSelected(row)"
              title="选择此会议"
              @click.stop
              @change="checked => toggleRowSelection(row, checked)"
            />
          </template>
        </el-table-column>
        <el-table-column label="会议主题" min-width="180">
          <template #default="{ row }">
            <div class="meeting-title">{{ row.title }}</div>
            <div class="meeting-desc">{{ row.description || '暂无描述' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120" align="center">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" effect="light" round>{{ row.statusName }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="成员" width="80" align="center">
          <template #default="{ row }">
            <el-badge :value="row.members?.length || 0" type="primary"><el-icon><User /></el-icon></el-badge>
          </template>
        </el-table-column>
        <el-table-column label="轮次" prop="totalRounds" width="80" align="center" />
        <el-table-column label="最终结论" min-width="160">
          <template #default="{ row }">
            <div v-if="row.finalConclusion" class="conclusion-cell" @click.stop>
              <span class="conclusion-text">{{ conclusionText(row) }}</span>
              <el-button
                v-if="shouldCollapseConclusion(row)"
                link
                type="primary"
                class="conclusion-toggle"
                @click.stop="toggleConclusion(row)"
              >
                {{ isConclusionExpanded(row) ? '收起' : '展开' }}
              </el-button>
            </div>
            <el-text v-else type="info">进行中...</el-text>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="248" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="$router.push(`/meetings/${row.meetingId}`)">详情</el-button>
            <el-button link type="info" @click.stop="handleClone(row)">复制</el-button>
            <el-button v-if="row.status === 2" link type="success" @click.stop="handleVerify(row, true)">有效</el-button>
            <el-button v-if="row.status === 2" link type="danger" @click.stop="handleVerify(row, false)">无效</el-button>
            <el-button v-if="canDelete(row)" link type="danger" @click.stop="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 移动端卡片列表 -->
      <div class="mobile-list">
        <div v-for="row in filteredMeetings" :key="row.meetingId" class="mobile-meeting-card"
             @click="$router.push(`/meetings/${row.meetingId}`)">
          <div class="mc-top">
            <el-checkbox
              v-if="canDelete(row)"
              :model-value="isSelected(row)"
              class="mc-select"
              @click.stop
              @change="checked => toggleRowSelection(row, checked)"
            />
            <div class="mc-title">{{ row.title }}</div>
            <el-tag :type="statusType(row.status)" effect="light" round size="small">{{ row.statusName }}</el-tag>
          </div>
          <div class="mc-desc">{{ row.description || '暂无描述' }}</div>
          <div class="mc-meta">
            <span><el-icon><User /></el-icon> {{ row.members?.length || 0 }} 成员</span>
            <span><el-icon><Refresh /></el-icon> {{ row.totalRounds }} 轮</span>
          </div>
          <div v-if="row.finalConclusion" class="mc-conclusion" @click.stop>
            <span>{{ conclusionText(row) }}</span>
            <el-button
              v-if="shouldCollapseConclusion(row)"
              link
              type="primary"
              class="conclusion-toggle"
              @click.stop="toggleConclusion(row)"
            >
              {{ isConclusionExpanded(row) ? '收起' : '展开' }}
            </el-button>
          </div>
          <div v-if="row.status === 2 || canDelete(row)" class="mc-actions" @click.stop>
            <el-button v-if="row.status === 2" size="small" type="success" plain @click="handleVerify(row, true)">验证有效</el-button>
            <el-button v-if="row.status === 2" size="small" type="danger" plain @click="handleVerify(row, false)">验证无效</el-button>
            <el-button v-if="canDelete(row)" size="small" type="danger" plain @click="handleDelete(row)">删除</el-button>
          </div>
        </div>
        <el-empty v-if="!loading && filteredMeetings.length === 0" description="暂无会议，快去发起一个吧" />
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { meetingApi } from '@/api/meeting'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()
const CREATOR_ID = computed(() => userStore.userId || 1)
const meetings = ref([])
const loading = ref(false)
const search = ref('')
const batchDeleting = ref(false)
const selectedMeetingIds = ref(new Set())
const expandedConclusionIds = ref(new Set())
const CONCLUSION_PREVIEW_LIMIT = 500

const filteredMeetings = computed(() =>
  search.value ? meetings.value.filter(m => m.title.includes(search.value)) : meetings.value
)

const deletableFilteredMeetings = computed(() =>
  filteredMeetings.value.filter(canDelete)
)

const allFilteredSelected = computed(() =>
  deletableFilteredMeetings.value.length > 0
    && deletableFilteredMeetings.value.every(row => selectedMeetingIds.value.has(row.meetingId))
)

const isFilteredSelectionIndeterminate = computed(() => {
  const selectedCount = deletableFilteredMeetings.value.filter(row => selectedMeetingIds.value.has(row.meetingId)).length
  return selectedCount > 0 && selectedCount < deletableFilteredMeetings.value.length
})

const selectedMeetings = computed(() =>
  meetings.value.filter(m => selectedMeetingIds.value.has(m.meetingId) && canDelete(m))
)

const activeSelectedMeetings = computed(() =>
  selectedMeetings.value.filter(isActiveMeeting)
)

const stats = computed(() => [
  { label: '全部会议', value: meetings.value.length, icon: 'Grid', color: '#409eff' },
  { label: '进行中', value: meetings.value.filter(m => m.status === 1).length, icon: 'Loading', color: '#e6a23c' },
  { label: '已达成结论', value: meetings.value.filter(m => m.status >= 2).length, icon: 'CircleCheck', color: '#67c23a' },
  { label: '待验证', value: meetings.value.filter(m => m.status === 2).length, icon: 'QuestionFilled', color: '#f56c6c' },
])

const statusType = (s) => ({0:'info',1:'warning',2:'primary',3:'success',4:'danger',5:'info'})[s] || 'info'

async function loadMeetings() {
  loading.value = true
  try {
    const res = await meetingApi.list(CREATOR_ID.value)
    meetings.value = res.data || []
    const existingIds = new Set(meetings.value.map(m => m.meetingId))
    selectedMeetingIds.value = new Set([...selectedMeetingIds.value].filter(id => existingIds.has(id)))
    expandedConclusionIds.value = new Set([...expandedConclusionIds.value].filter(id => existingIds.has(id)))
  } finally { loading.value = false }
}

async function handleVerify(row, valid) {
  await ElMessageBox.confirm(
    `确认将此会议结论标记为「${valid ? '有效' : '无效'}」？`,
    '验证结论', { type: valid ? 'success' : 'warning', confirmButtonText: '确认', cancelButtonText: '取消' }
  )
  await meetingApi.verify(row.meetingId, valid)
  ElMessage.success('验证成功，积分已结算')
  loadMeetings()
}

function handleClone(row) {
  router.push({ name: 'MeetingCreate', query: { cloneFrom: row.meetingId } })
}

const canDelete = (row) => Boolean(row?.meetingId)

const isActiveMeeting = (row) => row?.status === 1 || row?.status === 6

function normalizeConclusion(row) {
  return String(row?.finalConclusion || '')
}

function conclusionText(row) {
  const text = normalizeConclusion(row)
  if (isConclusionExpanded(row) || !shouldCollapseConclusion(row)) return text
  return `${Array.from(text).slice(0, CONCLUSION_PREVIEW_LIMIT).join('')}...`
}

function shouldCollapseConclusion(row) {
  return Array.from(normalizeConclusion(row)).length > CONCLUSION_PREVIEW_LIMIT
}

function isConclusionExpanded(row) {
  return expandedConclusionIds.value.has(row.meetingId)
}

function toggleConclusion(row) {
  const next = new Set(expandedConclusionIds.value)
  if (next.has(row.meetingId)) next.delete(row.meetingId)
  else next.add(row.meetingId)
  expandedConclusionIds.value = next
}

function isSelected(row) {
  return selectedMeetingIds.value.has(row.meetingId)
}

function toggleRowSelection(row, checked) {
  if (!canDelete(row)) return
  const next = new Set(selectedMeetingIds.value)
  if (checked) next.add(row.meetingId)
  else next.delete(row.meetingId)
  selectedMeetingIds.value = next
}

function toggleAllFiltered(checked) {
  const next = new Set(selectedMeetingIds.value)
  for (const row of deletableFilteredMeetings.value) {
    if (checked) next.add(row.meetingId)
    else next.delete(row.meetingId)
  }
  selectedMeetingIds.value = next
}

async function handleDelete(row) {
  const activeNotice = isActiveMeeting(row) ? '该会议正在运行，系统会先停止会议再删除。' : ''
  await ElMessageBox.confirm(
    `确认删除会议「${row.title}」？${activeNotice}将一并清除其发言、投票、共识、积分、工作流及追踪项，且不可恢复。`,
    '删除会议', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
  )
  await deleteOneMeeting(row)
  const next = new Set(selectedMeetingIds.value)
  next.delete(row.meetingId)
  selectedMeetingIds.value = next
  ElMessage.success('会议已删除')
  loadMeetings()
}

async function handleBatchDelete() {
  const targets = selectedMeetings.value
  if (!targets.length) return
  const preview = targets.slice(0, 5).map(row => `「${row.title}」`).join('、')
  const more = targets.length > 5 ? ` 等 ${targets.length} 场会议` : ''
  const activeCount = targets.filter(isActiveMeeting).length
  const activeNotice = activeCount ? `其中 ${activeCount} 场正在运行，系统会先停止这些会议再删除。` : ''
  await ElMessageBox.confirm(
    `确认批量删除 ${targets.length} 场会议：${preview}${more}？${activeNotice}将一并清除发言、投票、共识、积分、工作流及追踪项，且不可恢复。`,
    '批量删除会议',
    { type: 'warning', confirmButtonText: '批量删除', cancelButtonText: '取消' }
  )
  batchDeleting.value = true
  try {
    await deleteMeetingsWithFallback(targets.map(row => row.meetingId))
    selectedMeetingIds.value = new Set()
    ElMessage.success(`已删除 ${targets.length} 场会议`)
    loadMeetings()
  } finally {
    batchDeleting.value = false
  }
}

async function deleteMeetingsWithFallback(ids) {
  try {
    await meetingApi.batchRemove(ids, { suppressErrorMessage: true })
  } catch (error) {
    if (!isBatchDeleteEndpointMissing(error)) throw error
    const rowsById = new Map(meetings.value.map(row => [row.meetingId, row]))
    await Promise.all(ids.map(id => deleteOneMeeting(rowsById.get(id) || { meetingId: id })))
  }
}

async function deleteOneMeeting(row) {
  if (isActiveMeeting(row)) {
    await meetingApi.cancel(row.meetingId, { suppressErrorMessage: true }).catch(() => {})
  }
  await meetingApi.remove(row.meetingId)
}

function isBatchDeleteEndpointMissing(error) {
  const status = error?.response?.status || error?.status || error?.code
  return status === 404 || status === 405
}

onMounted(loadMeetings)
</script>

<style scoped>
.meeting-list { display: flex; flex-direction: column; gap: 16px; }
.page-header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 10px; }
.page-header h2 { font-size: 20px; font-weight: 700; color: #1a1f2e; }
.subtitle { color: #909399; font-size: 13px; margin-top: 2px; }
.stat-card { border-radius: 10px; }
.stat-inner { display: flex; align-items: center; gap: 12px; }
.stat-num { font-size: 24px; font-weight: 700; color: #1a1f2e; }
.stat-label { font-size: 12px; color: #909399; margin-top: 2px; }
.card-header { display: flex; justify-content: space-between; align-items: center; gap: 12px; font-weight: 600; }
.header-actions { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; justify-content: flex-end; }
.batch-hint {
  color: #909399;
  font-size: 12px;
  font-weight: 500;
  white-space: nowrap;
}
.selection-header {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: #909399;
  font-size: 12px;
  line-height: 1;
  white-space: nowrap;
}
.meeting-title { font-weight: 600; color: #303133; }
.meeting-desc { font-size: 12px; color: #909399; margin-top: 2px; }
.conclusion-cell { max-width: 560px; }
.conclusion-text {
  display: block;
  font-size: 13px;
  line-height: 1.6;
  color: #606266;
  white-space: pre-wrap;
  word-break: break-word;
}
.conclusion-toggle {
  margin-top: 4px;
  padding: 0;
  height: auto;
  font-size: 12px;
  font-weight: 600;
}
:deep(.meeting-row) { cursor: pointer; }
:deep(.meeting-row:hover td) { background: #f0f7ff !important; }

/* 移动端卡片 */
.mobile-list { display: none; }
.mobile-meeting-card {
  border: 1px solid #e4e7ed; border-radius: 10px;
  padding: 14px; margin-bottom: 10px; cursor: pointer;
  transition: box-shadow .2s;
}
.mobile-meeting-card:active { box-shadow: 0 2px 12px rgba(0,0,0,0.1); }
.mc-top { display: flex; justify-content: space-between; align-items: flex-start; gap: 8px; margin-bottom: 6px; }
.mc-select { flex: 0 0 auto; margin-top: -2px; }
.mc-title { font-weight: 600; font-size: 15px; color: #1a1f2e; flex: 1; }
.mc-desc { font-size: 12px; color: #909399; margin-bottom: 8px; }
.mc-meta { display: flex; gap: 12px; font-size: 12px; color: #606266; flex-wrap: wrap; }
.mc-meta span { display: flex; align-items: center; gap: 3px; }
.mc-conclusion {
  margin-top: 8px;
  padding: 8px 10px;
  border-radius: 8px;
  color: #4f6f55;
  background: #f3f9f0;
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
}
.mc-actions { margin-top: 10px; display: flex; gap: 8px; }

@media (max-width: 768px) {
  .desktop-table { display: none; }
  .mobile-list { display: block; }
  .stat-cards { margin-bottom: 4px; }
  .card-header { align-items: flex-start; flex-direction: column; }
  .header-actions { width: 100%; justify-content: space-between; }
  .header-actions .el-input { flex: 1; min-width: 160px; }
}
</style>
