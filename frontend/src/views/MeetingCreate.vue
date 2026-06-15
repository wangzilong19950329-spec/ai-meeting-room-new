<template>
  <div class="meeting-create">
    <el-card shadow="never" class="create-card">
      <template #header>
        <span class="card-title">🧠 {{ isCloneMode ? '复制并发起会议' : '发起新会议' }}</span>
        <p class="card-desc">{{ usesActorSlots ? '按流程节点槽位配置执行议员，启动流程化协作会议' : '配置会议主题与AI成员，启动多模型协作讨论' }}</p>
      </template>

      <el-alert
        v-if="isCloneMode"
        type="info"
        :closable="false"
        show-icon
        title="已载入被复制会议的配置，可自由调整后再创建并启动"
        style="margin-bottom:16px"
      />

      <el-form v-loading="cloning" :model="form" :rules="rules" ref="formRef" label-width="80px" size="default">
        <el-divider content-position="left"><b>基本信息</b></el-divider>
        <el-form-item label="会议主题" prop="title">
          <el-input v-model="form.title" placeholder="例如：量子计算会在10年内超越传统计算机吗？" />
        </el-form-item>
        <el-form-item label="背景描述">
          <el-input v-model="form.description" type="textarea" :rows="3"
            placeholder="补充议题背景、约束条件等（可选）" />
        </el-form-item>
        <el-form-item label="项目标识">
          <el-input v-model="form.projectKey" placeholder="例如：growth-q3、风控策略、产品定价" />
        </el-form-item>
        <el-form-item label="项目记忆">
          <el-input
            v-model="form.projectContext"
            type="textarea"
            :rows="4"
            placeholder="填写该项目长期适用的背景、约束、既有结论、组织偏好。相同项目标识的新会议会自动继承历史结论。"
          />
        </el-form-item>
        <el-form-item label="最大轮数">
          <div class="slider-wrap">
            <el-slider v-model="form.maxRounds" :min="2" :max="20" show-input input-size="small" />
          </div>
        </el-form-item>
        <el-form-item label="会议流程">
          <el-select v-model="form.workflowTemplateKey" placeholder="选择流程模板" style="width:100%;max-width:420px">
            <el-option
              v-for="tpl in workflowTemplates"
              :key="tpl.id"
              :label="tpl.name"
              :value="tpl.templateKey"
            >
              <div style="display:flex;justify-content:space-between;gap:12px">
                <span>{{ tpl.name }}</span>
                <span style="font-size:12px;color:#909399">{{ tpl.templateKey }}</span>
              </div>
            </el-option>
          </el-select>
          <div class="workflow-hint">
            有执行槽位的模板，会在下面自动展开“按节点槽位配置 AI 成员”。
          </div>
          <div v-if="currentWorkflowTemplate" class="workflow-template-summary">
            <div class="workflow-template-head">
              <div>
                <div class="workflow-template-name">{{ currentWorkflowTemplate.name }}</div>
                <div v-if="currentWorkflowTemplate.description" class="workflow-template-desc">
                  {{ currentWorkflowTemplate.description }}
                </div>
              </div>
              <div class="workflow-template-tags">
                <el-tag size="small" effect="plain">{{ currentWorkflowTemplate.templateKey }}</el-tag>
                <el-tag size="small" effect="plain" type="success">
                  {{ usesActorSlots ? `${templateActorSlots.length} 个执行槽位` : '圆桌模板' }}
                </el-tag>
              </div>
            </div>
            <div class="workflow-stage-preview-list">
              <div
                v-for="stage in currentWorkflowTemplate.stages || []"
                :key="stage.id || stage.stageKey"
                class="workflow-stage-preview-item"
              >
                <div class="workflow-stage-preview-head">
                  <span class="workflow-stage-order">#{{ stage.stageOrder }}</span>
                  <strong>{{ stage.stageName }}</strong>
                  <el-tag size="small" effect="plain">{{ stage.stageType }}</el-tag>
                </div>
                <div class="workflow-stage-preview-meta">
                  <span>{{ actorTypeLabel(stage.actorType) }}</span>
                  <span v-if="stage.actorSlotKey">槽位 · {{ stage.actorSlotName || stage.actorSlotKey }}</span>
                  <span>搜索 · {{ searchStrategyLabel(stage.searchStrategy, stage.stageType) }}</span>
                  <span v-if="stage.skillKey">Skill · {{ stage.skillKey }}</span>
                </div>
              </div>
            </div>
          </div>
        </el-form-item>

        <el-divider content-position="left"><b>{{ usesActorSlots ? '执行议员配置' : 'AI 成员配置' }}</b></el-divider>

        <div v-if="usesActorSlots" class="slot-config-panel">
          <div class="workflow-hint slot-hint">
            <template v-if="hasPrefilledRoster">已按模板默认阵容预填执行议员，可在此基础上调整。当前模板定义了 {{ templateActorSlots.length }} 个执行槽位。</template>
            <template v-else>当前模板定义了 {{ templateActorSlots.length }} 个执行槽位。这里直接给每个槽位绑定执行议员，不需要再额外配置一套全局参会成员。</template>
          </div>
          <div v-if="selectedModelWarnings.length" class="model-warning-list">
            <div v-for="warning in selectedModelWarnings" :key="warning.key" class="model-warning-item">
              <el-tag size="small" type="warning" effect="dark">未验证模型</el-tag>
              <span>{{ warning.label }}</span>
            </div>
          </div>
          <div class="members-container">
            <div v-for="(slot, idx) in form.slotBindings" :key="slot.slotKey" class="member-card">
              <div class="member-header">
                <el-tag :color="memberColor(idx)" effect="dark" round>{{ slot.slotName || slot.slotKey }}</el-tag>
                <el-tag v-if="slot.required === 1" size="small" effect="plain" type="danger">必填槽位</el-tag>
                <el-tag v-if="slot.allowMultiple === 1" size="small" effect="plain" type="success">支持多人</el-tag>
              </div>
              <div v-if="slot.description" class="slot-desc">{{ slot.description }}</div>
              <div v-if="slotStageMap[slot.slotKey]?.length" class="slot-desc">
                关联节点：{{ slotStageMap[slot.slotKey].join('、') }}
              </div>
              <div class="slot-member-list">
                <div v-for="(binding, memberIdx) in slot.bindings" :key="binding.uid" class="slot-member-item">
                  <div class="slot-member-head">
                    <strong>执行议员 {{ memberIdx + 1 }}</strong>
                    <el-tag v-if="binding.templateMissing" size="small" type="danger" effect="plain">模版已删除（使用存档快照）</el-tag>
                    <el-button
                      v-if="slot.allowMultiple === 1"
                      link
                      type="danger"
                      @click="removeSlotBinding(idx, memberIdx)"
                      :disabled="slot.bindings.length <= 1"
                    >
                      删除
                    </el-button>
                  </div>
                  <el-form-item label="议员模板" label-width="60px">
                    <el-select
                      v-model="binding.templateId"
                      clearable
                      filterable
                      placeholder="选择已有议员模板"
                      style="width:100%"
                      @change="applySlotTemplate(binding)"
                    >
                      <el-option
                        v-for="tpl in templates"
                        :key="tpl.id"
                        :label="tpl.name"
                        :value="tpl.id"
                      >
                        <div style="display:flex;justify-content:space-between;gap:12px">
                          <span>{{ tpl.name }}</span>
                          <span style="font-size:12px;color:#909399">{{ tpl.modelType || '未设模型' }}</span>
                        </div>
                      </el-option>
                    </el-select>
                  </el-form-item>
                  <el-row :gutter="8">
                    <el-col :xs="24" :sm="8">
                      <el-form-item :prop="`slotBindings.${idx}.bindings.${memberIdx}.memberName`" :rules="[{ required: slot.required === 1, message: '必填' }]" label="议员名" label-width="46px">
                        <el-input v-model="binding.memberName" placeholder="如：分析员A / 风控审核员" />
                      </el-form-item>
                    </el-col>
                    <el-col :xs="24" :sm="8">
                      <el-form-item :prop="`slotBindings.${idx}.bindings.${memberIdx}.modelType`" :rules="[{ required: slot.required === 1, message: '必填' }]" label="模型" label-width="46px">
                        <el-select v-model="binding.modelType" placeholder="选择模型" style="width:100%" :no-data-text="'暂无可用模型，请先在模型配置页添加并启用'">
                          <el-option v-for="m in availableModels" :key="m.modelKey || m.modelId" :label="m.displayName" :value="m.modelKey || m.modelId">
                            <div class="model-option">
                              <span>{{ m.displayName }}</span>
                              <el-tag
                                v-if="getModelBadge(m.modelKey || m.modelId)"
                                size="small"
                                :type="getModelBadge(m.modelKey || m.modelId).type"
                                effect="plain"
                              >
                                {{ getModelBadge(m.modelKey || m.modelId).label }}
                              </el-tag>
                            </div>
                          </el-option>
                        </el-select>
                        <div v-if="getSelectedModelBadge(binding.modelType)" class="model-badge-line">
                          <el-tag size="small" :type="getSelectedModelBadge(binding.modelType).type" effect="plain">
                            {{ getSelectedModelBadge(binding.modelType).label }}
                          </el-tag>
                          <span v-if="getSelectedModelBadge(binding.modelType).desc">{{ getSelectedModelBadge(binding.modelType).desc }}</span>
                        </div>
                      </el-form-item>
                    </el-col>
                    <el-col :xs="24" :sm="8">
                      <el-form-item label="角色" label-width="46px">
                        <el-select v-model="binding.role" style="width:100%">
                          <el-option label="参与者" :value="0" />
                          <el-option label="主持人" :value="1" />
                        </el-select>
                      </el-form-item>
                    </el-col>
                  </el-row>
                  <el-form-item :prop="`slotBindings.${idx}.bindings.${memberIdx}.persona`" :rules="[{ required: slot.required === 1, message: '必填' }]" label="人设" label-width="46px">
                    <el-input v-model="binding.persona" type="textarea" :rows="2" placeholder="描述该槽位执行者的思维风格、专业背景、分析角度..." />
                  </el-form-item>
                </div>
              </div>
              <el-button
                v-if="slot.allowMultiple === 1"
                plain
                size="small"
                @click="addSlotBinding(idx)"
              >
                <el-icon><Plus /></el-icon> 添加该槽位执行议员
              </el-button>
            </div>
          </div>
        </div>

        <template v-else>
        <el-form-item label="快速预设">
          <el-space wrap>
            <el-button v-for="preset in presets" :key="preset.name" plain size="small"
                       @click="applyPreset(preset)">{{ preset.name }}</el-button>
            <el-button plain size="small" type="primary" @click="showTemplateDialog = true">
              📋 从议员模版选择
            </el-button>
          </el-space>
        </el-form-item>

        <div class="members-container">
          <div v-if="selectedModelWarnings.length" class="model-warning-list">
            <div v-for="warning in selectedModelWarnings" :key="warning.key" class="model-warning-item">
              <el-tag size="small" type="warning" effect="dark">未验证模型</el-tag>
              <span>{{ warning.label }}</span>
            </div>
          </div>
          <div v-for="(member, idx) in form.members" :key="idx" class="member-card">
            <div class="member-header">
              <el-tag :color="memberColor(idx)" effect="dark" round>成员 {{ idx + 1 }}</el-tag>
              <el-button link type="danger" @click="removeMember(idx)" :disabled="form.members.length <= 2">
                <el-icon><Delete /></el-icon>
              </el-button>
            </div>
            <el-row :gutter="8">
              <el-col :xs="24" :sm="8">
                <el-form-item :prop="`members.${idx}.memberName`" :rules="[{required:true,message:'必填'}]"
                              label="名称" label-width="46px">
                  <el-input v-model="member.memberName" placeholder="如：理性分析者" />
                </el-form-item>
              </el-col>
              <el-col :xs="24" :sm="8">
                <el-form-item :prop="`members.${idx}.modelType`" :rules="[{required:true,message:'必填'}]"
                              label="模型" label-width="46px">
                  <el-select v-model="member.modelType" placeholder="选择模型" style="width:100%"
                             :no-data-text="'暂无可用模型，请先在模型配置页添加并启用'">
                    <el-option
                      v-for="m in availableModels" :key="m.modelKey || m.modelId"
                      :label="m.displayName" :value="m.modelKey || m.modelId"
                    >
                      <div class="model-option">
                        <span>{{ m.displayName }}</span>
                        <el-tag
                          v-if="getModelBadge(m.modelKey || m.modelId)"
                          size="small"
                          :type="getModelBadge(m.modelKey || m.modelId).type"
                          effect="plain"
                        >
                          {{ getModelBadge(m.modelKey || m.modelId).label }}
                        </el-tag>
                      </div>
                    </el-option>
                  </el-select>
                  <div v-if="getSelectedModelBadge(member.modelType)" class="model-badge-line">
                    <el-tag size="small" :type="getSelectedModelBadge(member.modelType).type" effect="plain">
                      {{ getSelectedModelBadge(member.modelType).label }}
                    </el-tag>
                    <span v-if="getSelectedModelBadge(member.modelType).desc">{{ getSelectedModelBadge(member.modelType).desc }}</span>
                  </div>
                </el-form-item>
              </el-col>
              <el-col :xs="24" :sm="8">
                <el-form-item label="角色" label-width="46px">
                  <el-select v-model="member.role" style="width:100%">
                    <el-option label="参与者" :value="0" />
                    <el-option label="主持人" :value="1" />
                  </el-select>
                </el-form-item>
              </el-col>
            </el-row>
            <el-form-item :prop="`members.${idx}.persona`" :rules="[{required:true,message:'必填'}]"
                          label="人设" label-width="46px">
              <el-input v-model="member.persona" type="textarea" :rows="2"
                placeholder="描述该AI成员的思维风格、专业背景、分析角度..." />
            </el-form-item>
          </div>

          <el-button dashed class="add-member-btn" @click="addMember" :disabled="form.members.length >= 8">
            <el-icon><Plus /></el-icon> 添加成员（最多8人）
          </el-button>
        </div>
        </template>

        <!-- 议员模版选择弹窗 -->
        <el-dialog v-model="showTemplateDialog" title="从议员模版选择" width="600px">
          <div v-if="templates.length === 0" style="text-align:center;padding:20px;color:#909399">
            暂无模版，请先前往「议员模版」页面创建
          </div>
          <div v-else class="template-list">
            <div v-for="t in templates" :key="t.id"
                 class="template-item"
                 :class="{ selected: selectedTemplateIds.has(t.id) }"
                 @click="toggleTemplate(t)">
              <div class="tpl-header">
                <span class="tpl-name">{{ t.name }}</span>
                <el-tag v-if="t.modelType" size="small" effect="plain">{{ t.modelType }}</el-tag>
                <el-icon v-if="selectedTemplateIds.has(t.id)" color="#409eff"><Check /></el-icon>
              </div>
              <div v-if="t.tags" class="tpl-tags">
                <el-tag v-for="tag in t.tags.split(',')" :key="tag" size="small" type="info" style="margin:2px">{{ tag.trim() }}</el-tag>
              </div>
              <div class="tpl-persona">{{ t.persona }}</div>
            </div>
          </div>
          <template #footer>
            <el-button @click="showTemplateDialog = false">取消</el-button>
            <el-button type="primary" @click="addFromTemplates" :disabled="selectedTemplateIds.size === 0">
              添加选中的 {{ selectedTemplateIds.size }} 个议员
            </el-button>
          </template>
        </el-dialog>

        <el-form-item style="margin-top:20px">
          <el-button type="primary" :loading="submitting" @click="handleSubmit" style="min-width:130px">
            创建并启动
          </el-button>
          <el-button @click="$router.back()">取消</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, computed, watch, nextTick } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { meetingApi, templateApi } from '@/api/meeting'
import { configApi } from '@/api/config'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const cloning = ref(false)
const isCloneMode = computed(() => !!route.query.cloneFrom)
const formRef = ref()
const submitting = ref(false)
const availableModels = ref([])
const workflowTemplates = ref([])
const templates = ref([])
const showTemplateDialog = ref(false)
const selectedTemplateIds = ref(new Set())

async function loadModels() {
  try {
    const res = await configApi.availableModels()
    availableModels.value = res.data || []
    normalizeMemberModels()
    syncSlotBindingsFromTemplate()
  } catch {}
}

function defaultModelKey() {
  return preferredStableModelKey() || preferredSearchModelKey() || availableModels.value[0]?.modelKey || availableModels.value[0]?.modelId || ''
}
function preferredStableModelKey() {
  return availableModels.value.find(item => item.verificationStatus === 'verified_stable')?.modelKey || ''
}
function preferredSearchModelKey() {
  return availableModels.value.find(item => item.verificationStatus === 'verified_search_experimental')?.modelKey || ''
}
function actorTypeLabel(type) {
  return ({
    ALL_MEMBERS: '全体议员',
    ANALYST: '分析员',
    REVIEWER: '复核员',
    APPROVER: '审批人',
    HUMAN: '人工',
    SYSTEM: '系统'
  })[type] || type
}
function defaultSearchStrategy(stageType) {
  return ['ANALYSIS', 'DEBATE', 'TRACKING'].includes(stageType) ? 'OPTIONAL' : 'OFF'
}
function searchStrategyLabel(strategy, stageType) {
  const normalized = String(strategy || '').trim().toUpperCase()
  const value = ['OFF', 'OPTIONAL', 'REQUIRED'].includes(normalized)
    ? normalized
    : defaultSearchStrategy(stageType)
  return ({
    OFF: '关闭搜索',
    OPTIONAL: '按需搜索',
    REQUIRED: '强制搜索'
  })[value] || '按需搜索'
}
function preferredModelForIndex(index = 0) {
  return index % 2 === 0
    ? (preferredStableModelKey() || preferredSearchModelKey() || defaultModelKey())
    : (preferredSearchModelKey() || preferredStableModelKey() || defaultModelKey())
}
function getModelBadge(modelValue) {
  if (!modelValue) return null
  const model = availableModels.value.find(item => (item.modelKey || item.modelId) === modelValue)
  if (!model) return null
  switch (model.verificationStatus) {
    case 'verified_stable':
      return { label: model.verificationLabel || '已验证稳定', type: 'success', desc: model.verificationNote || '' }
    case 'verified_search_experimental':
      return { label: model.verificationLabel || '已验证搜索实验', type: 'warning', desc: model.verificationNote || '' }
    default:
      return { label: model.verificationLabel || '未验证', type: 'danger', desc: model.verificationNote || '' }
  }
}
function getSelectedModelBadge(modelValue) {
  return getModelBadge(modelValue)
}
function normalizeMemberModels() {
  form.members.forEach((member, idx) => {
    const meta = getModelBadge(member.modelType)
    if (!member.modelType || !availableModels.value.some(item => (item.modelKey || item.modelId) === member.modelType) || !meta || meta.type === 'danger') {
      member.modelType = preferredModelForIndex(idx)
    }
  })
}
async function loadWorkflowTemplates() {
  try {
    const res = await meetingApi.listWorkflowTemplateDetails()
    workflowTemplates.value = res.data || []
    if (!form.workflowTemplateKey && workflowTemplates.value.length) {
      form.workflowTemplateKey = workflowTemplates.value[0].templateKey
    }
    syncSlotBindingsFromTemplate()
  } catch {}
}
async function loadTemplates() {
  try {
    const res = await templateApi.list()
    templates.value = res.data || []
  } catch {}
}
onMounted(async () => {
  await Promise.all([loadModels(), loadWorkflowTemplates(), loadTemplates()])
  const cloneFrom = Number(route.query.cloneFrom)
  if (cloneFrom) await applyCloneFrom(cloneFrom)
})

async function applyCloneFrom(id) {
  cloning.value = true
  try {
    const res = await meetingApi.getDetail(id)
    const d = res.data
    if (!d) return
    form.title = `${d.title || ''}（副本）`
    form.description = d.description || ''
    form.projectKey = d.projectKey || ''
    form.projectContext = d.projectContext || ''
    form.maxRounds = Number(d.totalRounds) || form.maxRounds
    const members = (d.members || []).map(m => ({
      memberName: m.memberName || '',
      modelType: resolveClonedModel(m.modelType),
      persona: m.persona || '',
      role: m.role ?? 0,
      actorSlotKey: m.actorSlotKey || ''
    }))
    const tplKey = d.workflow?.templateKey || ''
    const hasTemplate = tplKey && workflowTemplates.value.some(t => t.templateKey === tplKey)
    form.workflowTemplateKey = hasTemplate ? tplKey : ''
    await nextTick()
    if (hasTemplate && usesActorSlots.value) {
      const bySlot = new Map()
      for (const m of members) {
        const key = m.actorSlotKey || ''
        if (!bySlot.has(key)) bySlot.set(key, [])
        bySlot.get(key).push(m)
      }
      form.slotBindings.forEach(slot => {
        const matched = bySlot.get(slot.slotKey)
        if (matched && matched.length) {
          slot.bindings = matched.map(m => newSlotMemberBinding({
            memberName: m.memberName,
            modelType: m.modelType,
            persona: m.persona,
            role: m.role,
            templateId: null
          }))
        }
      })
    } else if (members.length) {
      form.members = members.map(({ memberName, modelType, persona, role }) => ({ memberName, modelType, persona, role }))
    }
  } catch {
    ElMessage.error('加载被复制会议失败，已切换为新建会议')
  } finally {
    cloning.value = false
  }
}

function resolveClonedModel(modelType) {
  if (modelType && availableModels.value.some(item => (item.modelKey || item.modelId) === modelType)) {
    return modelType
  }
  return defaultModelKey()
}

function toggleTemplate(t) {
  const s = new Set(selectedTemplateIds.value)
  s.has(t.id) ? s.delete(t.id) : s.add(t.id)
  selectedTemplateIds.value = s
}
function addFromTemplates() {
  const selected = templates.value.filter(t => selectedTemplateIds.value.has(t.id))
  for (const [idx, t] of selected.entries()) {
    if (form.members.length >= 8) break
    const candidateModel = t.modelType && availableModels.value.some(item => (item.modelKey || item.modelId) === t.modelType)
      ? t.modelType
      : preferredModelForIndex(form.members.length + idx)
    form.members.push({
      memberName: t.name,
      modelType: candidateModel,
      persona: [t.persona, t.skills, t.personality].filter(Boolean).join('；'),
      role: 0
    })
  }
  selectedTemplateIds.value = new Set()
  showTemplateDialog.value = false
}

const COLORS = ['#409eff','#67c23a','#e6a23c','#f56c6c','#909399','#9b59b6','#1abc9c','#e67e22']
const memberColor = (idx) => COLORS[idx % COLORS.length]

const form = reactive({
  title: '', description: '', projectKey: '', projectContext: '', workflowTemplateKey: '', maxRounds: 5, creatorId: userStore.userId || 1,
  slotBindings: [],
  members: [
    { memberName: '理性分析者', modelType: '', persona: '你擅长逻辑推理和数据分析，注重事实依据，观点客观中立，不轻易受情感影响。', role: 0 },
    { memberName: '批判性思维者', modelType: '', persona: '你善于发现论点中的漏洞和假设，提出尖锐的反问，但始终基于逻辑而非情感。', role: 0 },
    { memberName: '创新思考者', modelType: '', persona: '你擅长发散思维，敢于提出非主流观点，能从新颖角度看待问题，但同样注重论证质量。', role: 0 },
  ]
})

const currentWorkflowTemplate = computed(() =>
  workflowTemplates.value.find(tpl => tpl.templateKey === form.workflowTemplateKey) || null
)
const templateActorSlots = computed(() => currentWorkflowTemplate.value?.actorSlots || [])
const usesActorSlots = computed(() => templateActorSlots.value.length > 0)
const slotStageMap = computed(() => {
  const map = {}
  for (const stage of currentWorkflowTemplate.value?.stages || []) {
    if (!stage.actorSlotKey) continue
    if (!map[stage.actorSlotKey]) map[stage.actorSlotKey] = []
    map[stage.actorSlotKey].push(stage.stageName || stage.stageKey)
  }
  return map
})
const selectedModelWarnings = computed(() => {
  if (usesActorSlots.value) {
    const warnings = []
    for (const slot of form.slotBindings) {
      for (const binding of slot.bindings || []) {
        const meta = getModelBadge(binding.modelType)
        if (!binding.modelType || (meta && meta.type !== 'danger')) continue
        warnings.push({
          key: `${slot.slotKey}-${binding.uid}`,
          label: `${slot.slotName || slot.slotKey} / ${binding.memberName || '未命名议员'} 使用了未验证模型`
        })
      }
    }
    return warnings
  }
  return form.members.flatMap((member, idx) => {
    const meta = getModelBadge(member.modelType)
    if (!member.modelType || (meta && meta.type !== 'danger')) return []
    return [{
      key: `${idx}-${member.modelType}`,
      label: `成员 ${idx + 1}（${member.memberName || '未命名'}）使用了未验证模型`
    }]
  })
})

watch(() => form.workflowTemplateKey, () => {
  syncSlotBindingsFromTemplate()
})

function syncSlotBindingsFromTemplate() {
  if (!usesActorSlots.value) {
    form.slotBindings = []
    return
  }
  form.slotBindings = templateActorSlots.value.map(slot => {
    const existing = form.slotBindings.find(item => item.slotKey === slot.slotKey)
    let bindings
    if (Array.isArray(existing?.bindings) && existing.bindings.length) {
      bindings = existing.bindings.map(binding => newSlotMemberBinding({
        memberName: binding.memberName || '',
        modelType: binding.modelType || slot.defaultModelType || defaultModelKey(),
        persona: binding.persona || slot.defaultPersona || '',
        role: binding.role ?? 0,
        templateId: binding.templateId ?? null,
        templateMissing: binding.templateMissing === true
      }))
    } else if (Array.isArray(slot.defaultMembers) && slot.defaultMembers.length) {
      bindings = slot.defaultMembers.map(m => newSlotMemberBinding({
        memberName: m.memberName || slot.slotName || '',
        modelType: m.modelType || slot.defaultModelType || defaultModelKey(),
        persona: m.persona || slot.defaultPersona || '',
        role: 0,
        templateId: m.memberTemplateId ?? null,
        templateMissing: m.templateExists === false
      }))
    } else {
      bindings = [newSlotMemberBinding({
        memberName: slot.slotName || '',
        modelType: slot.defaultModelType || defaultModelKey(),
        persona: slot.defaultPersona || '',
        role: 0,
        templateId: null
      })]
    }
    return {
      slotKey: slot.slotKey,
      slotName: slot.slotName,
      description: slot.description || '',
      required: Number(slot.required ?? 1),
      allowMultiple: Number(slot.allowMultiple ?? 0),
      bindings
    }
  })
}

const hasPrefilledRoster = computed(() =>
  templateActorSlots.value.some(slot => Array.isArray(slot.defaultMembers) && slot.defaultMembers.length)
)

const rules = { title: [{ required: true, message: '请输入会议主题', trigger: 'blur' }] }

const presets = [
  { name: '三方辩论（3人）', members: [
    { memberName: '正方代表', modelType: '', persona: '支持主题观点，从正面论证其合理性，但必须基于事实。', role: 0 },
    { memberName: '反方代表', modelType: '', persona: '反对主题观点，从负面指出其问题，必须提供具体论据。', role: 0 },
    { memberName: '中立评判者', modelType: '', persona: '保持中立，综合评判双方论点质量，最终给出公正结论。', role: 1 },
  ]},
  { name: '专家委员会（4人）', members: [
    { memberName: '技术专家', modelType: '', persona: '从技术可行性和工程实现角度分析，注重实际操作层面。', role: 0 },
    { memberName: '经济学家', modelType: '', persona: '从经济效益、成本收益、市场影响角度分析。', role: 0 },
    { memberName: '伦理学家', modelType: '', persona: '从道德、社会影响、长远风险角度进行评估。', role: 0 },
    { memberName: '决策主席', modelType: '', persona: '综合各方意见，推动讨论达成共识，确保每个观点都有充分表达。', role: 1 },
  ]},
  { name: '双模型对话（2人）', members: [
    { memberName: '乐观派', modelType: '', persona: '倾向于看到问题的积极面，但仍需用事实支撑观点，不盲目乐观。', role: 0 },
    { memberName: '悲观派', modelType: '', persona: '倾向于识别风险和潜在问题，用事实数据支撑，不情绪化。', role: 0 },
  ]},
]

function applyPreset(preset) { form.members = preset.members.map((m, idx) => ({ ...m, modelType: preferredModelForIndex(idx) })) }
function addMember() { form.members.push({ memberName: '', modelType: preferredModelForIndex(form.members.length), persona: '', role: 0 }) }
function removeMember(idx) { form.members.splice(idx, 1) }

function newSlotMemberBinding(overrides = {}) {
  return {
    uid: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    memberName: '',
    modelType: defaultModelKey(),
    persona: '',
    role: 0,
    templateId: null,
    templateMissing: false,
    ...overrides
  }
}

function addSlotBinding(slotIndex) {
  const slot = form.slotBindings[slotIndex]
  if (!slot) return
  slot.bindings.push(newSlotMemberBinding())
}

function removeSlotBinding(slotIndex, bindingIndex) {
  const slot = form.slotBindings[slotIndex]
  if (!slot || slot.bindings.length <= 1) return
  slot.bindings.splice(bindingIndex, 1)
}

function applySlotTemplate(binding) {
  const tpl = templates.value.find(item => item.id === binding.templateId)
  if (!tpl) return
  binding.templateMissing = false
  binding.memberName = tpl.name || binding.memberName
  binding.modelType = (tpl.modelType && availableModels.value.some(item => (item.modelKey || item.modelId) === tpl.modelType))
    ? tpl.modelType
    : (binding.modelType || defaultModelKey())
  binding.persona = [tpl.persona, tpl.skills, tpl.personality].filter(Boolean).join('；') || binding.persona
}

async function handleSubmit() {
  await formRef.value.validate()
  submitting.value = true
  try {
    const payload = {
      title: form.title,
      description: form.description,
      projectKey: form.projectKey,
      projectContext: form.projectContext,
      workflowTemplateKey: form.workflowTemplateKey,
      maxRounds: form.maxRounds,
      creatorId: form.creatorId,
      members: usesActorSlots.value
        ? form.slotBindings
            .flatMap(item => (item.bindings || [])
              .filter(binding => binding.memberName?.trim() && binding.modelType?.trim() && binding.persona?.trim())
              .map(binding => ({
                memberName: binding.memberName.trim(),
                modelType: binding.modelType.trim(),
                persona: binding.persona.trim(),
                actorSlotKey: item.slotKey,
                actorSlotName: item.slotName,
                role: binding.role ?? 0
              })))
        : form.members
    }
    if (!payload.members.length) {
      ElMessage.error('当前流程模板至少需要配置一位有效议员后才能创建会议')
      return
    }
    const createRes = await meetingApi.create(payload)
    const meetingId = createRes.data
    await meetingApi.start(meetingId)
    ElMessage.success('会议已创建并启动，AI成员正在讨论中...')
    router.push(`/meetings/${meetingId}`)
  } catch (err) {
    ElMessage.error(err?.response?.data?.msg || err?.message || '会议创建失败，请检查流程模板和议员配置')
  } finally { submitting.value = false }
}
</script>

<style scoped>
.meeting-create { max-width: 900px; margin: 0 auto; }
.create-card { border-radius: 12px; }
.card-title { font-size: 17px; font-weight: 700; }
.card-desc { font-size: 13px; color: #909399; margin-top: 4px; }
.slider-wrap { width: 100%; max-width: 400px; }
.workflow-hint { font-size: 12px; color: #909399; margin-top: 6px; line-height: 1.5; }
.workflow-template-summary {
  margin-top: 12px;
  padding: 14px;
  border-radius: 12px;
  border: 1px solid #dbeafe;
  background: linear-gradient(180deg, #f8fbff 0%, #eef6ff 100%);
}
.workflow-template-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 12px;
}
.workflow-template-name { font-size: 14px; font-weight: 700; color: #1f2937; }
.workflow-template-desc { margin-top: 4px; font-size: 12px; color: #607085; line-height: 1.6; }
.workflow-template-tags { display: flex; gap: 6px; flex-wrap: wrap; justify-content: flex-end; }
.workflow-stage-preview-list { display: flex; flex-direction: column; gap: 8px; }
.workflow-stage-preview-item {
  padding: 10px 12px;
  border-radius: 10px;
  border: 1px solid #d9e7ff;
  background: rgba(255, 255, 255, 0.86);
}
.workflow-stage-preview-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 6px;
  color: #1f2937;
}
.workflow-stage-order {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 24px;
  height: 24px;
  padding: 0 6px;
  border-radius: 999px;
  background: #dbeafe;
  color: #1d4ed8;
  font-size: 12px;
  font-weight: 700;
}
.workflow-stage-preview-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 12px;
  font-size: 12px;
  color: #5b6b80;
}
.slot-hint { margin-bottom: 12px; }
.slot-desc { font-size: 12px; color: #606266; line-height: 1.6; margin-bottom: 8px; }
.slot-member-list { display: flex; flex-direction: column; gap: 12px; }
.slot-member-item { padding: 12px; border: 1px solid #e5e7eb; border-radius: 10px; background: #fff; }
.slot-member-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; color: #374151; }
.members-container { display: flex; flex-direction: column; gap: 14px; }
.member-card { background: #f8f9fb; border-radius: 10px; padding: 14px; border: 1px solid #e4e7ed; }
.member-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
.model-option { display: flex; justify-content: space-between; align-items: center; gap: 12px; width: 100%; }
.model-badge-line { display: flex; align-items: center; gap: 8px; margin-top: 6px; font-size: 12px; color: #606266; line-height: 1.5; }
.model-warning-list { display: flex; flex-direction: column; gap: 8px; margin-bottom: 12px; }
.model-warning-item { display: flex; align-items: center; gap: 8px; padding: 10px 12px; border-radius: 10px; background: #fff7ed; border: 1px solid #fed7aa; color: #9a3412; font-size: 13px; }
.add-member-btn {
  border: 2px dashed #d0d7de; height: 46px; border-radius: 10px;
  color: #909399; font-size: 14px; width: 100%;
}
.add-member-btn:hover { border-color: #409eff; color: #409eff; }

.template-list { display: flex; flex-direction: column; gap: 8px; max-height: 420px; overflow-y: auto; }
.template-item {
  padding: 12px; border: 1px solid #e4e7ed; border-radius: 8px; cursor: pointer;
  transition: all .2s;
}
.template-item:hover { border-color: #409eff; background: #f0f7ff; }
.template-item.selected { border-color: #409eff; background: #ecf5ff; }
.tpl-header { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.tpl-name { font-weight: 600; font-size: 14px; flex: 1; }
.tpl-tags { margin-bottom: 4px; }
.tpl-persona { font-size: 12px; color: #606266; line-height: 1.5; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
@media (max-width: 768px) {
  .workflow-template-head { flex-direction: column; }
  .workflow-template-tags { justify-content: flex-start; }
}
</style>
