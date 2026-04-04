<template>
  <div class="meeting-create">
    <el-card shadow="never" class="create-card">
      <template #header>
        <span class="card-title">🧠 发起新会议</span>
        <p class="card-desc">配置会议主题与AI成员，启动多模型协作讨论</p>
      </template>

      <el-form :model="form" :rules="rules" ref="formRef" label-width="100px" size="large">
        <!-- 基本信息 -->
        <el-divider content-position="left"><b>基本信息</b></el-divider>
        <el-form-item label="会议主题" prop="title">
          <el-input v-model="form.title" placeholder="例如：量子计算会在10年内超越传统计算机吗？" />
        </el-form-item>
        <el-form-item label="背景描述" prop="description">
          <el-input v-model="form.description" type="textarea" :rows="3"
            placeholder="补充议题背景、约束条件等（可选）" />
        </el-form-item>
        <el-form-item label="最大轮数">
          <el-slider v-model="form.maxRounds" :min="2" :max="20" show-input input-size="small" style="width:400px" />
        </el-form-item>

        <!-- 成员配置 -->
        <el-divider content-position="left"><b>AI 成员配置</b></el-divider>

        <!-- 预设方案 -->
        <el-form-item label="快速预设">
          <el-space wrap>
            <el-button v-for="preset in presets" :key="preset.name" plain size="small"
                       @click="applyPreset(preset)">
              {{ preset.name }}
            </el-button>
          </el-space>
        </el-form-item>

        <div class="members-container">
          <div v-for="(member, idx) in form.members" :key="idx" class="member-card">
            <div class="member-header">
              <el-tag :color="memberColor(idx)" effect="dark" round>成员 {{ idx + 1 }}</el-tag>
              <el-button link type="danger" @click="removeMember(idx)" :disabled="form.members.length <= 2">
                <el-icon><Delete /></el-icon>
              </el-button>
            </div>
            <el-row :gutter="12">
              <el-col :span="8">
                <el-form-item :prop="`members.${idx}.memberName`" :rules="[{required:true,message:'必填'}]" label="名称" label-width="50px">
                  <el-input v-model="member.memberName" placeholder="如：理性分析者" />
                </el-form-item>
              </el-col>
              <el-col :span="8">
                <el-form-item :prop="`members.${idx}.modelType`" :rules="[{required:true,message:'必填'}]" label="模型" label-width="50px">
                  <el-select v-model="member.modelType" placeholder="选择模型" style="width:100%">
                    <el-option-group label="OpenAI">
                      <el-option label="GPT-4o" value="gpt-4o" />
                      <el-option label="GPT-4 Turbo" value="gpt-4-turbo" />
                      <el-option label="GPT-3.5 Turbo" value="gpt-3.5-turbo" />
                    </el-option-group>
                    <el-option-group label="Anthropic">
                      <el-option label="Claude 3.5 Sonnet" value="claude-3-5-sonnet-20241022" />
                      <el-option label="Claude 3 Opus" value="claude-3-opus-20240229" />
                    </el-option-group>
                    <el-option-group label="国内模型">
                      <el-option label="通义千问 Max" value="qwen-max" />
                      <el-option label="通义千问 Plus" value="qwen-plus" />
                      <el-option label="文心一言 4.0" value="ernie-bot-4" />
                    </el-option-group>
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col :span="8">
                <el-form-item label="角色" label-width="50px">
                  <el-select v-model="member.role" style="width:100%">
                    <el-option label="参与者" :value="0" />
                    <el-option label="主持人" :value="1" />
                  </el-select>
                </el-form-item>
              </el-col>
            </el-row>
            <el-form-item :prop="`members.${idx}.persona`" :rules="[{required:true,message:'必填'}]" label="人设" label-width="50px">
              <el-input v-model="member.persona" type="textarea" :rows="2"
                placeholder="描述该AI成员的思维风格、专业背景、分析角度..." />
            </el-form-item>
          </div>

          <el-button dashed class="add-member-btn" @click="addMember" :disabled="form.members.length >= 8">
            <el-icon><Plus /></el-icon> 添加成员（最多8人）
          </el-button>
        </div>

        <!-- 提交 -->
        <el-form-item style="margin-top:24px">
          <el-button type="primary" size="large" :loading="submitting" @click="handleSubmit" style="width:160px">
            创建并启动
          </el-button>
          <el-button size="large" @click="$router.back()">取消</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { meetingApi } from '@/api/meeting'

const router = useRouter()
const formRef = ref()
const submitting = ref(false)

const COLORS = ['#409eff','#67c23a','#e6a23c','#f56c6c','#909399','#9b59b6','#1abc9c','#e67e22']
const memberColor = (idx) => COLORS[idx % COLORS.length]

const form = reactive({
  title: '',
  description: '',
  maxRounds: 5,
  creatorId: 1,
  members: [
    { memberName: '理性分析者', modelType: 'gpt-4o', persona: '你擅长逻辑推理和数据分析，注重事实依据，观点客观中立，不轻易受情感影响。', role: 0 },
    { memberName: '批判性思维者', modelType: 'claude-3-5-sonnet-20241022', persona: '你善于发现论点中的漏洞和假设，提出尖锐的反问，但始终基于逻辑而非情感。', role: 0 },
    { memberName: '创新思考者', modelType: 'qwen-max', persona: '你擅长发散思维，敢于提出非主流观点，能从新颖角度看待问题，但同样注重论证质量。', role: 0 },
  ]
})

const rules = {
  title: [{ required: true, message: '请输入会议主题', trigger: 'blur' }],
}

const presets = [
  {
    name: '三方辩论（3人）',
    members: [
      { memberName: '正方代表', modelType: 'gpt-4o', persona: '支持主题观点，从正面论证其合理性，但必须基于事实。', role: 0 },
      { memberName: '反方代表', modelType: 'claude-3-5-sonnet-20241022', persona: '反对主题观点，从负面指出其问题，必须提供具体论据。', role: 0 },
      { memberName: '中立评判者', modelType: 'qwen-max', persona: '保持中立，综合评判双方论点质量，最终给出公正结论。', role: 1 },
    ]
  },
  {
    name: '专家委员会（4人）',
    members: [
      { memberName: '技术专家', modelType: 'gpt-4o', persona: '从技术可行性和工程实现角度分析，注重实际操作层面。', role: 0 },
      { memberName: '经济学家', modelType: 'claude-3-5-sonnet-20241022', persona: '从经济效益、成本收益、市场影响角度分析。', role: 0 },
      { memberName: '伦理学家', modelType: 'qwen-max', persona: '从道德、社会影响、长远风险角度进行评估。', role: 0 },
      { memberName: '决策主席', modelType: 'gpt-4-turbo', persona: '综合各方意见，推动讨论达成共识，确保每个观点都有充分表达。', role: 1 },
    ]
  },
  {
    name: '双模型对话（2人）',
    members: [
      { memberName: '乐观派', modelType: 'gpt-4o', persona: '倾向于看到问题的积极面，但仍需用事实支撑观点，不盲目乐观。', role: 0 },
      { memberName: '悲观派', modelType: 'claude-3-5-sonnet-20241022', persona: '倾向于识别风险和潜在问题，用事实数据支撑，不情绪化。', role: 0 },
    ]
  },
]

function applyPreset(preset) {
  form.members = preset.members.map(m => ({ ...m }))
}

function addMember() {
  form.members.push({ memberName: '', modelType: 'gpt-4o', persona: '', role: 0 })
}

function removeMember(idx) {
  form.members.splice(idx, 1)
}

async function handleSubmit() {
  await formRef.value.validate()
  submitting.value = true
  try {
    const createRes = await meetingApi.create(form)
    const meetingId = createRes.data
    await meetingApi.start(meetingId)
    ElMessage.success('会议已创建并启动，AI成员正在讨论中...')
    router.push(`/meetings/${meetingId}`)
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.meeting-create { max-width: 900px; margin: 0 auto; }
.create-card { border-radius: 12px; }
.card-title { font-size: 18px; font-weight: 700; }
.card-desc { font-size: 13px; color: #909399; margin-top: 4px; }
.members-container { display: flex; flex-direction: column; gap: 16px; padding-left: 100px; }
.member-card {
  background: #f8f9fb;
  border-radius: 10px;
  padding: 16px;
  border: 1px solid #e4e7ed;
}
.member-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.add-member-btn {
  border: 2px dashed #d0d7de;
  height: 48px;
  border-radius: 10px;
  color: #909399;
  font-size: 14px;
}
.add-member-btn:hover { border-color: #409eff; color: #409eff; }
</style>
