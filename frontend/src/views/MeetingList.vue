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
          <el-input v-model="search" placeholder="搜索主题..." clearable style="width:180px">
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
        </div>
      </template>

      <!-- 桌面端表格 -->
      <el-table :data="filteredMeetings" v-loading="loading" stripe
                class="desktop-table" row-class-name="meeting-row"
                @row-click="row => $router.push(`/meetings/${row.meetingId}`)">
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
            <span v-if="row.finalConclusion" class="conclusion-text">{{ row.finalConclusion }}</span>
            <el-text v-else type="info">进行中...</el-text>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="$router.push(`/meetings/${row.meetingId}`)">详情</el-button>
            <el-button v-if="row.status === 2" link type="success" @click.stop="handleVerify(row, true)">有效</el-button>
            <el-button v-if="row.status === 2" link type="danger" @click.stop="handleVerify(row, false)">无效</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 移动端卡片列表 -->
      <div class="mobile-list">
        <div v-for="row in filteredMeetings" :key="row.meetingId" class="mobile-meeting-card"
             @click="$router.push(`/meetings/${row.meetingId}`)">
          <div class="mc-top">
            <div class="mc-title">{{ row.title }}</div>
            <el-tag :type="statusType(row.status)" effect="light" round size="small">{{ row.statusName }}</el-tag>
          </div>
          <div class="mc-desc">{{ row.description || '暂无描述' }}</div>
          <div class="mc-meta">
            <span><el-icon><User /></el-icon> {{ row.members?.length || 0 }} 成员</span>
            <span><el-icon><Refresh /></el-icon> {{ row.totalRounds }} 轮</span>
            <span v-if="row.finalConclusion" class="mc-conclusion">{{ row.finalConclusion }}</span>
          </div>
          <div v-if="row.status === 2" class="mc-actions" @click.stop>
            <el-button size="small" type="success" plain @click="handleVerify(row, true)">验证有效</el-button>
            <el-button size="small" type="danger" plain @click="handleVerify(row, false)">验证无效</el-button>
          </div>
        </div>
        <el-empty v-if="!loading && filteredMeetings.length === 0" description="暂无会议，快去发起一个吧" />
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { meetingApi } from '@/api/meeting'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()
const CREATOR_ID = computed(() => userStore.userId || 1)
const meetings = ref([])
const loading = ref(false)
const search = ref('')

const filteredMeetings = computed(() =>
  search.value ? meetings.value.filter(m => m.title.includes(search.value)) : meetings.value
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
.card-header { display: flex; justify-content: space-between; align-items: center; font-weight: 600; }
.meeting-title { font-weight: 600; color: #303133; }
.meeting-desc { font-size: 12px; color: #909399; margin-top: 2px; }
.conclusion-text { font-size: 13px; color: #606266; }
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
.mc-title { font-weight: 600; font-size: 15px; color: #1a1f2e; flex: 1; }
.mc-desc { font-size: 12px; color: #909399; margin-bottom: 8px; }
.mc-meta { display: flex; gap: 12px; font-size: 12px; color: #606266; flex-wrap: wrap; }
.mc-meta span { display: flex; align-items: center; gap: 3px; }
.mc-conclusion { color: #67c23a; font-style: italic; }
.mc-actions { margin-top: 10px; display: flex; gap: 8px; }

@media (max-width: 768px) {
  .desktop-table { display: none; }
  .mobile-list { display: block; }
  .stat-cards { margin-bottom: 4px; }
}
</style>
