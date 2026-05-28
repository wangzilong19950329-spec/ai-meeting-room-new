<template>
  <div class="model-config">

    <!-- 模型注册表 -->
    <el-card shadow="never" class="config-card">
      <template #header>
        <div class="card-header">
          <div>
            <span class="card-title">🤖 AI 模型配置</span>
            <span class="card-hint">会自动持久化到本机 ~/.aimeeting-room/model-registry.json，重启后保留</span>
          </div>
          <el-button type="primary" @click="openAdd">
            <el-icon><Plus /></el-icon> 添加模型
          </el-button>
        </div>
      </template>

      <el-table :data="models" v-loading="loading" stripe>
        <el-table-column label="显示名称" min-width="160">
          <template #default="{ row }">
            <div class="model-name">{{ row.displayName }}</div>
            <div class="model-id">{{ row.modelId }}</div>
          </template>
        </el-table-column>
        <el-table-column label="接口地址" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="api-url">{{ row.apiUrl }}</span>
          </template>
        </el-table-column>
        <el-table-column label="格式" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.apiFormat === 'claude' ? 'warning' : 'success'" effect="plain">
              {{ row.apiFormat }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="API Key" width="140" align="center">
          <template #default="{ row }">
            <span class="key-mask">{{ row.apiKey || '未配置' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-switch v-model="row.enabled" @change="toggleEnabled(row)" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 会议引擎参数 -->
    <el-card shadow="never" class="config-card">
      <template #header>
        <div class="card-header">
          <span class="card-title">⚙️ 会议引擎参数</span>
          <el-button type="primary" :loading="savingMeeting" @click="saveMeeting">保存</el-button>
        </div>
      </template>
      <el-form :model="meetingForm" label-width="140px" size="default">
        <el-row :gutter="20">
          <el-col :xs="24" :sm="12" :md="8">
            <el-form-item label="共识阈值">
              <el-slider v-model="meetingForm.consensusThreshold" :min="0.5" :max="1" :step="0.05"
                         :format-tooltip="v => (v*100).toFixed(0)+'%'" show-input input-size="small" style="width:200px" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8">
            <el-form-item label="最少认同人数">
              <el-input-number v-model="meetingForm.minAgreementCount" :min="2" :max="10" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8">
            <el-form-item label="默认最大轮次">
              <el-input-number v-model="meetingForm.maxRoundsDefault" :min="2" :max="20" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8">
            <el-form-item label="有效结论奖励分">
              <el-input-number v-model="meetingForm.baseReward" :min="10" :max="500" :step="10" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8">
            <el-form-item label="无效结论扣除分">
              <el-input-number v-model="meetingForm.basePenalty" :min="0" :max="200" :step="10" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8">
            <el-form-item label="异议方扣分系数">
              <el-input-number v-model="meetingForm.dissentPenaltyFactor" :min="0" :max="1" :step="0.05" :precision="2" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
    </el-card>

    <!-- 添加/编辑对话框 -->
    <el-dialog v-model="dialogVisible" :title="editing ? '编辑模型' : '添加模型'" width="560px" :close-on-click-modal="false">
      <el-form :model="dialogForm" :rules="dialogRules" ref="dialogFormRef" label-width="100px" size="default">
        <el-form-item label="显示名称" prop="displayName">
          <el-input v-model="dialogForm.displayName" placeholder="如：DeepSeek R1（代理）" />
        </el-form-item>
        <el-form-item label="模型 ID" prop="modelId">
          <el-input v-model="dialogForm.modelId" placeholder="如：deepseek-reasoner" />
          <div class="form-tip">发送给接口的 model 字段值，需与接口文档一致</div>
        </el-form-item>
        <el-form-item label="接口地址" prop="apiUrl">
          <el-input v-model="dialogForm.apiUrl" placeholder="如：https://api.deepseek.com/v1/chat/completions" />
        </el-form-item>
        <el-form-item label="API Key" prop="apiKey">
          <el-input v-model="dialogForm.apiKey" :type="showKey ? 'text' : 'password'" placeholder="sk-...">
            <template #suffix>
              <el-icon style="cursor:pointer" @click="showKey = !showKey">
                <component :is="showKey ? 'Hide' : 'View'" />
              </el-icon>
            </template>
          </el-input>
          <div v-if="editing" class="form-tip">留空或含 *** 则保留原 Key 不变</div>
        </el-form-item>
        <el-form-item label="调用格式" prop="apiFormat">
          <el-radio-group v-model="dialogForm.apiFormat">
            <el-radio value="openai">OpenAI 兼容（适用于 GPT、Qwen、DeepSeek 等绝大多数模型）</el-radio>
            <el-radio value="claude">Claude 原生（仅 Anthropic Claude 系列）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="dialogForm.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">确定</el-button>
      </template>
    </el-dialog>

  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { configApi } from '@/api/config'

const loading      = ref(false)
const saving       = ref(false)
const savingMeeting = ref(false)
const models       = ref([])
const dialogVisible = ref(false)
const editing      = ref(false)
const showKey      = ref(false)
const dialogFormRef = ref()

const dialogForm = reactive({
  id: '', displayName: '', modelId: '', apiUrl: '', apiKey: '', apiFormat: 'openai', enabled: true
})

const dialogRules = {
  displayName: [{ required: true, message: '请输入显示名称' }],
  modelId:     [{ required: true, message: '请输入模型 ID'  }],
  apiUrl:      [{ required: true, message: '请输入接口地址' }],
  apiFormat:   [{ required: true }],
}

const meetingForm = reactive({
  consensusThreshold: 0.70, minAgreementCount: 2, maxRoundsDefault: 10,
  baseReward: 100, basePenalty: 50, dissentPenaltyFactor: 0.30,
})

async function loadRegistry() {
  loading.value = true
  try {
    const res = await configApi.listRegistry()
    models.value = res.data || []
  } finally { loading.value = false }
}

async function loadMeetingConfig() {
  try {
    const res = await configApi.getMeetingConfig()
    Object.assign(meetingForm, res.data || {})
  } catch {}
}

function openAdd() {
  editing.value = false
  showKey.value = false
  Object.assign(dialogForm, { id: '', displayName: '', modelId: '', apiUrl: '', apiKey: '', apiFormat: 'openai', enabled: true })
  dialogVisible.value = true
}

function openEdit(row) {
  editing.value = true
  showKey.value = false
  Object.assign(dialogForm, { ...row })
  dialogVisible.value = true
}

async function handleSave() {
  await dialogFormRef.value.validate()
  saving.value = true
  try {
    if (editing.value) {
      await configApi.updateModel(dialogForm.id, { ...dialogForm })
      ElMessage.success('已更新')
    } else {
      await configApi.addModel({ ...dialogForm })
      ElMessage.success('已添加')
    }
    dialogVisible.value = false
    await loadRegistry()
  } finally { saving.value = false }
}

async function handleDelete(row) {
  await ElMessageBox.confirm(`确认删除「${row.displayName}」？`, '删除模型', { type: 'warning' })
  await configApi.deleteModel(row.id)
  ElMessage.success('已删除')
  await loadRegistry()
}

async function toggleEnabled(row) {
  try {
    await configApi.updateModel(row.id, { ...row })
    ElMessage.success(row.enabled ? '已启用' : '已禁用')
  } catch {
    row.enabled = !row.enabled  // 回滚
  }
}

async function saveMeeting() {
  savingMeeting.value = true
  try {
    await configApi.saveMeetingConfig({ ...meetingForm })
    ElMessage.success('会议参数已保存')
  } finally { savingMeeting.value = false }
}

onMounted(() => { loadRegistry(); loadMeetingConfig() })
</script>

<style scoped>
.model-config { display: flex; flex-direction: column; gap: 20px; }
.config-card { border-radius: 12px; }
.card-header { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }
.card-title { font-size: 15px; font-weight: 700; }
.card-hint { font-size: 12px; color: #909399; margin-left: 8px; }
.model-name { font-weight: 600; font-size: 13px; }
.model-id   { font-size: 11px; color: #909399; margin-top: 2px; font-family: monospace; }
.api-url    { font-size: 12px; color: #606266; word-break: break-all; }
.key-mask   { font-family: monospace; font-size: 12px; color: #909399; }
.form-tip   { font-size: 11px; color: #909399; margin-top: 4px; }
</style>
