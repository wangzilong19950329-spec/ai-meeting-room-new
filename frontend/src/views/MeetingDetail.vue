<template>
  <div class="meeting-detail" v-loading="loading">
    <template v-if="meeting">
      <!-- 顶部状态栏 -->
      <el-card shadow="never" class="header-card">
        <div class="meeting-header">
          <div class="header-left">
            <div class="header-title">
              <el-tag :type="statusType(meeting.status)" effect="dark" round style="margin-right:10px">
                {{ meeting.statusName }}
              </el-tag>
              <h2>{{ meeting.title }}</h2>
            </div>
            <p class="header-desc">{{ meeting.description }}</p>
            <div class="header-meta">
              <span><el-icon><User /></el-icon> {{ meeting.members?.length || 0 }} 位成员</span>
              <span><el-icon><Refresh /></el-icon> 第 {{ meeting.totalRounds }} 轮</span>
            </div>
          </div>
          <div class="header-actions">
            <el-button v-if="meeting.status === 0" type="primary" @click="handleStart">启动会议</el-button>
            <template v-if="meeting.status === 2">
              <el-button type="success" @click="handleVerify(true)">✓ 结论有效</el-button>
              <el-button type="danger" plain @click="handleVerify(false)">✗ 结论无效</el-button>
            </template>
            <el-button v-if="meeting.status === 1" :icon="Refresh" circle @click="pollDetail" />
          </div>
        </div>

        <!-- 进行中动画 -->
        <div v-if="meeting.status === 1" class="progress-bar-wrap">
          <div class="progress-label">AI 成员正在讨论中...</div>
          <el-progress :percentage="progressPct" :stroke-width="8" striped striped-flow status="striped" />
        </div>

        <!-- 最终结论 -->
        <el-alert v-if="meeting.finalConclusion" :title="'最终结论：' + meeting.finalConclusion"
                  type="success" :closable="false" show-icon style="margin-top:16px" />
      </el-card>

      <el-row :gutter="20">
        <!-- 左：发言记录 -->
        <el-col :span="16">
          <el-card shadow="never" class="transcript-card">
            <template #header>
              <div class="card-header">
                <span>📝 完整辩论记录</span>
                <el-select v-model="filterRound" placeholder="所有轮次" clearable size="small" style="width:120px">
                  <el-option v-for="r in rounds" :key="r" :label="`第 ${r} 轮`" :value="r" />
                </el-select>
              </div>
            </template>

            <div class="transcript" ref="transcriptRef">
              <template v-for="(group, round) in groupedOpinions" :key="round">
                <div class="round-divider">
                  <el-divider><el-tag size="small">第 {{ round }} 轮</el-tag></el-divider>
                </div>
                <div v-for="op in group" :key="op.id" class="opinion-item"
                     :class="[`type-${op.opinionType}`, { 'is-rebuttal': op.parentOpinionId }]">
                  <div class="opinion-avatar" :style="{ background: memberColor(op.memberId) }">
                    {{ op.memberName?.charAt(0) }}
                  </div>
                  <div class="opinion-body">
                    <div class="opinion-meta">
                      <span class="opinion-name">{{ op.memberName }}</span>
                      <el-tag size="small" :type="opinionTagType(op.opinionType)" effect="plain">
                        {{ op.opinionTypeName }}
                      </el-tag>
                      <span class="opinion-time">{{ formatTime(op.gmtCreate) }}</span>
                    </div>
                    <div class="opinion-content">{{ op.content }}</div>
                    <div v-if="op.evidence" class="opinion-evidence">
                      <el-icon><InfoFilled /></el-icon> 论据：{{ op.evidence }}
                    </div>
                  </div>
                </div>
              </template>
              <el-empty v-if="!filteredOpinions.length" description="暂无发言记录" />
            </div>
          </el-card>
        </el-col>

        <!-- 右：成员列表 + 总结 -->
        <el-col :span="8">
          <!-- 成员列表 -->
          <el-card shadow="never" class="members-card">
            <template #header><span>👥 参与成员</span></template>
            <div v-for="(m, idx) in meeting.members" :key="idx" class="member-item">
              <div class="member-avatar" :style="{ background: memberColorByIdx(idx) }">
                {{ m.memberName?.charAt(0) }}
              </div>
              <div class="member-info">
                <div class="member-name">{{ m.memberName }}</div>
                <el-tag size="small" effect="plain">{{ m.modelType }}</el-tag>
                <el-tag size="small" :type="m.role === 1 ? 'warning' : 'info'" style="margin-left:4px">
                  {{ m.role === 1 ? '主持人' : '参与者' }}
                </el-tag>
              </div>
            </div>
          </el-card>

          <!-- 贡献度 -->
          <el-card v-if="contributions.length" shadow="never" class="contrib-card" style="margin-top:16px">
            <template #header><span>📊 贡献度分析</span></template>
            <div v-for="c in contributions" :key="c.memberId" class="contrib-item">
              <div class="contrib-name">{{ c.memberName }}</div>
              <el-progress :percentage="Math.round(c.contributionWeight * 25)" :stroke-width="6"
                           :color="c.inConsensusCluster ? '#67c23a' : '#909399'" />
            </div>
          </el-card>

          <!-- 会议总结 -->
          <el-card v-if="meeting.summary" shadow="never" class="summary-card" style="margin-top:16px">
            <template #header><span>📋 会议总结</span></template>
            <div class="summary-text">{{ meeting.summary }}</div>
          </el-card>
        </el-col>
      </el-row>
    </template>

    <el-result v-else-if="!loading" icon="error" title="会议不存在">
      <template #extra>
        <el-button @click="$router.push('/meetings')">返回列表</el-button>
      </template>
    </el-result>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, InfoFilled } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { meetingApi } from '@/api/meeting'

const route = useRoute()
const meetingId = route.params.id
const meeting = ref(null)
const contributions = ref([])
const loading = ref(false)
const filterRound = ref(null)
const transcriptRef = ref()
let pollTimer = null

const COLORS = ['#409eff','#67c23a','#e6a23c','#f56c6c','#9b59b6','#1abc9c','#e67e22','#2ecc71']
const memberColorMap = ref({})

const memberColor = (memberId) => memberColorMap.value[memberId] || '#909399'
const memberColorByIdx = (idx) => COLORS[idx % COLORS.length]

const statusType = (s) => ({0:'info',1:'warning',2:'primary',3:'success',4:'danger',5:'info'})[s] || 'info'
const opinionTagType = (t) => ({0:'',1:'danger',2:'warning'})[t] || ''

const formatTime = (t) => t ? dayjs(t).format('HH:mm:ss') : ''

const rounds = computed(() => {
  if (!meeting.value?.opinions) return []
  return [...new Set(meeting.value.opinions.map(o => o.roundNumber))].sort((a,b) => a-b)
})

const filteredOpinions = computed(() => {
  if (!meeting.value?.opinions) return []
  return filterRound.value
    ? meeting.value.opinions.filter(o => o.roundNumber === filterRound.value)
    : meeting.value.opinions
})

const groupedOpinions = computed(() => {
  const groups = {}
  for (const op of filteredOpinions.value) {
    if (!groups[op.roundNumber]) groups[op.roundNumber] = []
    groups[op.roundNumber].push(op)
  }
  return groups
})

const progressPct = computed(() => {
  if (!meeting.value) return 0
  return Math.min(90, (meeting.value.totalRounds / meeting.value.maxRounds) * 100)
})

async function loadDetail() {
  loading.value = true
  try {
    const res = await meetingApi.getDetail(meetingId)
    meeting.value = res.data
    // 建立成员颜色映射
    if (res.data?.members) {
      res.data.members.forEach((m, i) => {
        memberColorMap.value[m.id] = COLORS[i % COLORS.length]
      })
    }
    // 加载贡献度（已结束的会议）
    if (res.data?.status >= 2) {
      try {
        const cr = await meetingApi.getContributions(meetingId)
        contributions.value = cr.data || []
      } catch {}
    }
    await nextTick()
    scrollToBottom()
  } finally {
    loading.value = false
  }
}

function scrollToBottom() {
  if (transcriptRef.value) {
    transcriptRef.value.scrollTop = transcriptRef.value.scrollHeight
  }
}

async function pollDetail() {
  await loadDetail()
}

function startPolling() {
  if (pollTimer) return
  pollTimer = setInterval(async () => {
    if (meeting.value?.status === 1) {
      await loadDetail()
    } else {
      clearInterval(pollTimer)
    }
  }, 5000)
}

async function handleStart() {
  await meetingApi.start(meetingId)
  ElMessage.success('会议已启动')
  loadDetail()
  startPolling()
}

async function handleVerify(valid) {
  await ElMessageBox.confirm(
    `确认将结论标记为「${valid ? '有效' : '无效'}」？`,
    '验证结论', { type: valid ? 'success' : 'warning' }
  )
  await meetingApi.verify(meetingId, valid)
  ElMessage.success('验证完成，积分已结算')
  loadDetail()
}

onMounted(() => {
  loadDetail().then(() => {
    if (meeting.value?.status === 1) startPolling()
  })
})
onUnmounted(() => { if (pollTimer) clearInterval(pollTimer) })
</script>

<style scoped>
.meeting-detail { display: flex; flex-direction: column; gap: 20px; }
.header-card, .transcript-card, .members-card, .contrib-card, .summary-card { border-radius: 12px; }
.meeting-header { display: flex; justify-content: space-between; align-items: flex-start; }
.header-title { display: flex; align-items: center; }
.header-title h2 { font-size: 20px; font-weight: 700; }
.header-desc { color: #909399; font-size: 13px; margin: 6px 0; }
.header-meta { display: flex; gap: 16px; font-size: 13px; color: #606266; }
.header-meta span { display: flex; align-items: center; gap: 4px; }
.header-actions { display: flex; gap: 8px; flex-shrink: 0; }
.progress-bar-wrap { margin-top: 16px; }
.progress-label { font-size: 13px; color: #e6a23c; margin-bottom: 8px; font-weight: 500; }
.card-header { display: flex; justify-content: space-between; align-items: center; font-weight: 600; }
.transcript { max-height: 600px; overflow-y: auto; padding-right: 4px; }
.round-divider { margin: 8px 0; }
.opinion-item {
  display: flex;
  gap: 12px;
  padding: 12px;
  border-radius: 10px;
  margin-bottom: 10px;
  background: #f8f9fb;
  transition: background .2s;
}
.opinion-item:hover { background: #f0f7ff; }
.opinion-item.type-1 { border-left: 3px solid #f56c6c; }
.opinion-item.type-2 { border-left: 3px solid #e6a23c; opacity: 0.85; }
.is-rebuttal { margin-left: 20px; }
.opinion-avatar {
  width: 36px; height: 36px; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  color: #fff; font-weight: 700; font-size: 15px; flex-shrink: 0;
}
.opinion-body { flex: 1; min-width: 0; }
.opinion-meta { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.opinion-name { font-weight: 600; font-size: 14px; }
.opinion-time { font-size: 12px; color: #c0c4cc; margin-left: auto; }
.opinion-content { font-size: 13px; line-height: 1.7; color: #303133; white-space: pre-wrap; }
.opinion-evidence {
  margin-top: 8px; padding: 6px 10px;
  background: #fdf6ec; border-radius: 6px;
  font-size: 12px; color: #b88230;
  display: flex; align-items: flex-start; gap: 4px;
}
.member-item { display: flex; gap: 12px; align-items: center; padding: 10px 0; border-bottom: 1px solid #f0f0f0; }
.member-item:last-child { border-bottom: none; }
.member-avatar {
  width: 40px; height: 40px; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  color: #fff; font-weight: 700; font-size: 16px; flex-shrink: 0;
}
.member-name { font-weight: 600; margin-bottom: 4px; }
.contrib-item { margin-bottom: 12px; }
.contrib-name { font-size: 13px; font-weight: 500; margin-bottom: 4px; }
.summary-text { font-size: 13px; line-height: 1.8; color: #303133; white-space: pre-wrap; }
</style>
