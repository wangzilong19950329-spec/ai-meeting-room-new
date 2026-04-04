<template>
  <div class="meeting-list">
    <!-- 顶部操作栏 -->
    <div class="page-header">
      <div>
        <h2>我的会议</h2>
        <p class="subtitle">多AI成员协作，减少单模型偏差</p>
      </div>
      <el-button type="primary" size="large" @click="$router.push('/meetings/create')">
        <el-icon><Plus /></el-icon> 发起新会议
      </el-button>
    </div>

    <!-- 统计卡片 -->
    <el-row :gutter="16" class="stat-cards">
      <el-col :span="6" v-for="s in stats" :key="s.label">
        <el-card shadow="never" class="stat-card">
          <div class="stat-inner">
            <el-icon :size="36" :color="s.color"><component :is="s.icon" /></el-icon>
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
          <el-input v-model="search" placeholder="搜索会议主题..." clearable style="width:240px">
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
        </div>
      </template>

      <el-table :data="filteredMeetings" v-loading="loading" stripe row-class-name="meeting-row"
                @row-click="row => $router.push(`/meetings/${row.meetingId}`)">
        <el-table-column label="会议主题" min-width="200">
          <template #default="{ row }">
            <div class="meeting-title">{{ row.title }}</div>
            <div class="meeting-desc">{{ row.description || '暂无描述' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="130" align="center">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" effect="light" round>
              {{ row.statusName }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="成员" width="100" align="center">
          <template #default="{ row }">
            <el-badge :value="row.members?.length || 0" type="primary">
              <el-icon><User /></el-icon>
            </el-badge>
          </template>
        </el-table-column>
        <el-table-column label="已进行轮次" prop="totalRounds" width="110" align="center" />
        <el-table-column label="最终结论" min-width="180">
          <template #default="{ row }">
            <span v-if="row.finalConclusion" class="conclusion-text">{{ row.finalConclusion }}</span>
            <el-text v-else type="info">进行中...</el-text>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="$router.push(`/meetings/${row.meetingId}`)">
              查看详情
            </el-button>
            <el-button v-if="row.status === 2" link type="success" @click.stop="handleVerify(row, true)">
              验证有效
            </el-button>
            <el-button v-if="row.status === 2" link type="danger" @click.stop="handleVerify(row, false)">
              验证无效
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="!loading && filteredMeetings.length === 0" description="暂无会议，快去发起一个吧" />
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { meetingApi } from '@/api/meeting'

const CREATOR_ID = 1  // TODO: 接入登录系统后替换

const meetings = ref([])
const loading = ref(false)
const search = ref('')

const filteredMeetings = computed(() =>
  search.value
    ? meetings.value.filter(m => m.title.includes(search.value))
    : meetings.value
)

const stats = computed(() => [
  { label: '全部会议', value: meetings.value.length, icon: 'Grid', color: '#409eff' },
  { label: '进行中', value: meetings.value.filter(m => m.status === 1).length, icon: 'Loading', color: '#e6a23c' },
  { label: '已达成结论', value: meetings.value.filter(m => m.status >= 2).length, icon: 'CircleCheck', color: '#67c23a' },
  { label: '待验证', value: meetings.value.filter(m => m.status === 2).length, icon: 'QuestionFilled', color: '#f56c6c' },
])

const statusType = (s) => ({0:'info', 1:'warning', 2:'primary', 3:'success', 4:'danger', 5:'info'})[s] || 'info'

async function loadMeetings() {
  loading.value = true
  try {
    const res = await meetingApi.list(CREATOR_ID)
    meetings.value = res.data || []
  } finally {
    loading.value = false
  }
}

async function handleVerify(row, valid) {
  await ElMessageBox.confirm(
    `确认将此会议结论标记为「${valid ? '有效' : '无效'}」？这将触发积分计算。`,
    '验证结论',
    { type: valid ? 'success' : 'warning', confirmButtonText: '确认', cancelButtonText: '取消' }
  )
  await meetingApi.verify(row.meetingId, valid)
  ElMessage.success('验证成功，积分已结算')
  loadMeetings()
}

onMounted(loadMeetings)
</script>

<style scoped>
.meeting-list { display: flex; flex-direction: column; gap: 20px; }
.page-header { display: flex; justify-content: space-between; align-items: center; }
.page-header h2 { font-size: 22px; font-weight: 700; color: #1a1f2e; }
.subtitle { color: #909399; font-size: 13px; margin-top: 4px; }
.stat-card { border-radius: 12px; }
.stat-inner { display: flex; align-items: center; gap: 16px; }
.stat-num { font-size: 28px; font-weight: 700; color: #1a1f2e; }
.stat-label { font-size: 13px; color: #909399; margin-top: 2px; }
.card-header { display: flex; justify-content: space-between; align-items: center; font-weight: 600; }
.meeting-title { font-weight: 600; color: #303133; }
.meeting-desc { font-size: 12px; color: #909399; margin-top: 2px; }
.conclusion-text { font-size: 13px; color: #606266; }
:deep(.meeting-row) { cursor: pointer; }
:deep(.meeting-row:hover td) { background: #f0f7ff !important; }
</style>
