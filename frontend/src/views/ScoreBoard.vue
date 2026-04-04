<template>
  <div class="score-board">
    <div class="page-header">
      <div>
        <h2>积分排行榜</h2>
        <p class="subtitle">根据贡献度计算，结论有效得分，无效扣分</p>
      </div>
    </div>

    <el-row :gutter="20">
      <!-- 积分规则说明 -->
      <el-col :span="24">
        <el-card shadow="never" class="rules-card">
          <el-row :gutter="16">
            <el-col :span="6" v-for="rule in rules" :key="rule.title">
              <div class="rule-item">
                <el-icon :size="28" :color="rule.color"><component :is="rule.icon" /></el-icon>
                <div class="rule-title">{{ rule.title }}</div>
                <div class="rule-desc">{{ rule.desc }}</div>
              </div>
            </el-col>
          </el-row>
        </el-card>
      </el-col>

      <!-- 查询成员积分 -->
      <el-col :span="24">
        <el-card shadow="never">
          <template #header>
            <div class="card-header">
              <span>成员积分记录</span>
              <div style="display:flex;gap:8px">
                <el-input-number v-model="memberId" placeholder="成员ID" :min="1" controls-position="right" style="width:140px" />
                <el-button type="primary" @click="loadScores" :loading="loading">查询</el-button>
              </div>
            </div>
          </template>

          <!-- 汇总 -->
          <div v-if="scores.length" class="score-summary">
            <el-statistic title="总积分" :value="totalScore"
                          :value-style="{ color: totalScore >= 0 ? '#67c23a' : '#f56c6c', fontSize: '32px' }" />
            <el-statistic title="获奖次数" :value="rewardCount" />
            <el-statistic title="扣分次数" :value="penaltyCount" />
            <el-statistic title="参与会议" :value="meetingCount" />
          </div>

          <el-table :data="scores" v-loading="loading" stripe>
            <el-table-column label="会议ID" prop="meetingId" width="100" align="center" />
            <el-table-column label="积分变动" width="120" align="center">
              <template #default="{ row }">
                <span :class="row.scoreDelta > 0 ? 'score-up' : 'score-down'">
                  {{ row.scoreDelta > 0 ? '+' : '' }}{{ row.scoreDelta }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="原因" prop="reason" />
            <el-table-column label="时间" width="180">
              <template #default="{ row }">{{ formatTime(row.gmtCreate) }}</template>
            </el-table-column>
            <el-table-column label="贡献详情" width="120" align="center">
              <template #default="{ row }">
                <el-popover placement="left" :width="320" trigger="hover">
                  <template #reference>
                    <el-button link type="primary" size="small">查看</el-button>
                  </template>
                  <div v-if="row.contributionDetail" class="contrib-detail">
                    <div v-for="(v, k) in parseDetail(row.contributionDetail)" :key="k" class="detail-row">
                      <span>{{ detailLabel(k) }}</span>
                      <el-progress v-if="typeof v === 'number' && v <= 1" :percentage="Math.round(v * 100)"
                                   :stroke-width="6" style="width:120px" />
                      <span v-else class="detail-val">{{ typeof v === 'number' ? v.toFixed(2) : v }}</span>
                    </div>
                  </div>
                </el-popover>
              </template>
            </el-table-column>
          </el-table>

          <el-empty v-if="!loading && scores.length === 0 && queried" description="暂无积分记录" />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { meetingApi } from '@/api/meeting'

const memberId = ref(1)
const scores = ref([])
const loading = ref(false)
const queried = ref(false)

const totalScore = computed(() => scores.value.reduce((sum, s) => sum + s.scoreDelta, 0))
const rewardCount = computed(() => scores.value.filter(s => s.scoreDelta > 0).length)
const penaltyCount = computed(() => scores.value.filter(s => s.scoreDelta < 0).length)
const meetingCount = computed(() => new Set(scores.value.map(s => s.meetingId)).size)

const formatTime = (t) => t ? dayjs(t).format('YYYY-MM-DD HH:mm') : '-'
const parseDetail = (str) => { try { return JSON.parse(str) } catch { return {} } }
const detailLabel = (k) => ({
  participationRate: '参与率',
  rebuttalQuality: '反驳质量',
  earlyAgreement: '早期认同',
  evidenceRate: '论据提供率',
  positionConsistency: '立场一致性',
  contributionWeight: '综合权重',
}[k] || k)

const rules = [
  { title: '参与率 (20%)', desc: '每轮均发言得满分', icon: 'ChatDotRound', color: '#409eff' },
  { title: '反驳质量 (25%)', desc: '有效反驳他人观点', icon: 'Switch', color: '#e6a23c' },
  { title: '早期认同 (20%)', desc: '越早支持最终结论', icon: 'Timer', color: '#67c23a' },
  { title: '论据提供 (20%)', desc: '发言附有事实论据', icon: 'Document', color: '#9b59b6' },
]

async function loadScores() {
  if (!memberId.value) return ElMessage.warning('请输入成员ID')
  loading.value = true
  queried.value = true
  try {
    const res = await meetingApi.getMemberScores(memberId.value)
    scores.value = res.data || []
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.score-board { display: flex; flex-direction: column; gap: 20px; }
.page-header h2 { font-size: 22px; font-weight: 700; }
.subtitle { color: #909399; font-size: 13px; margin-top: 4px; }
.rules-card { border-radius: 12px; }
.rule-item { display: flex; flex-direction: column; align-items: center; gap: 8px; padding: 16px; text-align: center; }
.rule-title { font-weight: 600; font-size: 14px; }
.rule-desc { font-size: 12px; color: #909399; }
.card-header { display: flex; justify-content: space-between; align-items: center; font-weight: 600; }
.score-summary { display: flex; gap: 32px; padding: 16px 0 24px; border-bottom: 1px solid #f0f0f0; margin-bottom: 16px; }
.score-up { color: #67c23a; font-weight: 700; font-size: 16px; }
.score-down { color: #f56c6c; font-weight: 700; font-size: 16px; }
.contrib-detail { display: flex; flex-direction: column; gap: 8px; }
.detail-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; font-size: 13px; }
.detail-val { font-weight: 600; color: #409eff; }
</style>
