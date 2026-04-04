<template>
  <div class="model-config">
    <div class="page-header">
      <div>
        <h2>模型密钥配置</h2>
        <p class="subtitle">配置各 AI 服务商的 API Key，保存后立即生效</p>
      </div>
      <el-button type="primary" :loading="saving" @click="saveAll">保存所有配置</el-button>
    </div>

    <el-row :gutter="20">
      <el-col :span="24" v-for="provider in providers" :key="provider.key">
        <el-card shadow="never" class="provider-card" :class="{ 'is-enabled': form[provider.key]?.enabled }">
          <template #header>
            <div class="provider-header">
              <div class="provider-title">
                <el-icon :size="22" :color="provider.color"><component :is="provider.icon" /></el-icon>
                <span class="provider-name">{{ provider.name }}</span>
                <el-tag size="small" :type="provider.type" effect="plain">{{ provider.tag }}</el-tag>
              </div>
              <el-switch
                v-model="form[provider.key].enabled"
                active-text="已启用"
                inactive-text="已禁用"
              />
            </div>
          </template>

          <el-form :model="form[provider.key]" label-width="120px" size="default">
            <el-row :gutter="20">
              <el-col :span="12">
                <el-form-item label="API 地址">
                  <el-input v-model="form[provider.key].apiUrl" placeholder="https://api.example.com/v1/chat/completions" />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="API Key">
                  <el-input
                    v-model="form[provider.key].apiKey"
                    :type="showKey[provider.key] ? 'text' : 'password'"
                    placeholder="sk-..."
                  >
                    <template #suffix>
                      <el-icon style="cursor:pointer" @click="showKey[provider.key] = !showKey[provider.key]">
                        <component :is="showKey[provider.key] ? 'Hide' : 'View'" />
                      </el-icon>
                    </template>
                  </el-input>
                </el-form-item>
              </el-col>

              <template v-if="provider.hasSecret">
                <el-col :span="12">
                  <el-form-item label="Secret Key">
                    <el-input
                      v-model="form[provider.key].secretKey"
                      :type="showSecret[provider.key] ? 'text' : 'password'"
                      placeholder="Secret Key（文心一言专用）"
                    >
                      <template #suffix>
                        <el-icon style="cursor:pointer" @click="showSecret[provider.key] = !showSecret[provider.key]">
                          <component :is="showSecret[provider.key] ? 'Hide' : 'View'" />
                        </el-icon>
                      </template>
                    </el-input>
                  </el-form-item>
                </el-col>
              </template>

              <template v-if="provider.hasVersion">
                <el-col :span="12">
                  <el-form-item label="API Version">
                    <el-input v-model="form[provider.key].apiVersion" placeholder="2023-06-01-preview" />
                  </el-form-item>
                </el-col>
              </template>
            </el-row>

            <el-form-item label="支持的模型">
              <el-space wrap>
                <el-tag v-for="m in provider.models" :key="m" size="small" effect="plain" type="info">{{ m }}</el-tag>
              </el-space>
            </el-form-item>

            <el-form-item label="连通性测试">
              <el-button
                plain
                size="small"
                :loading="testing[provider.key]"
                :disabled="!form[provider.key].enabled || !form[provider.key].apiKey"
                @click="testConnection(provider)"
              >
                <el-icon><Connection /></el-icon> 测试连接
              </el-button>
              <el-tag
                v-if="testResult[provider.key]"
                :type="testResult[provider.key].ok ? 'success' : 'danger'"
                style="margin-left:12px"
              >
                {{ testResult[provider.key].ok ? '✓ 连接成功' : '✗ ' + testResult[provider.key].msg }}
              </el-tag>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>
    </el-row>

    <!-- 会议参数 -->
    <el-card shadow="never" class="provider-card">
      <template #header>
        <div class="provider-header">
          <div class="provider-title">
            <el-icon :size="22" color="#409eff"><Setting /></el-icon>
            <span class="provider-name">会议引擎参数</span>
            <el-tag size="small" effect="plain">高级设置</el-tag>
          </div>
        </div>
      </template>
      <el-form :model="meetingForm" label-width="140px" size="default">
        <el-row :gutter="20">
          <el-col :span="8">
            <el-form-item label="共识阈值">
              <el-slider v-model="meetingForm.consensusThreshold" :min="0.5" :max="1" :step="0.05"
                         :format-tooltip="v => (v * 100).toFixed(0) + '%'" show-input input-size="small" style="width:200px" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="最少认同人数">
              <el-input-number v-model="meetingForm.minAgreementCount" :min="2" :max="10" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="默认最大轮次">
              <el-input-number v-model="meetingForm.maxRoundsDefault" :min="2" :max="20" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="有效结论奖励分">
              <el-input-number v-model="meetingForm.baseReward" :min="10" :max="500" :step="10" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="无效结论扣除分">
              <el-input-number v-model="meetingForm.basePenalty" :min="0" :max="200" :step="10" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="异议方扣分系数">
              <el-input-number v-model="meetingForm.dissentPenaltyFactor" :min="0" :max="1" :step="0.05" :precision="2" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { configApi } from '@/api/config'

const saving = ref(false)
const testing = reactive({})
const testResult = reactive({})
const showKey = reactive({})
const showSecret = reactive({})

const providers = [
  {
    key: 'openai',
    name: 'OpenAI',
    tag: 'GPT 系列',
    color: '#10a37f',
    icon: 'Monitor',
    type: 'success',
    hasSecret: false,
    hasVersion: false,
    models: ['gpt-4o', 'gpt-4-turbo', 'gpt-3.5-turbo'],
  },
  {
    key: 'claude',
    name: 'Anthropic Claude',
    tag: 'Claude 系列',
    color: '#d4824d',
    icon: 'ChatRound',
    type: 'warning',
    hasSecret: false,
    hasVersion: true,
    models: ['claude-3-5-sonnet-20241022', 'claude-3-opus-20240229'],
  },
  {
    key: 'tongyi',
    name: '通义千问（阿里云）',
    tag: 'Qwen 系列',
    color: '#ff6a00',
    icon: 'Cpu',
    type: 'danger',
    hasSecret: false,
    hasVersion: false,
    models: ['qwen-max', 'qwen-plus', 'qwen-turbo'],
  },
  {
    key: 'wenxin',
    name: '文心一言（百度）',
    tag: 'ERNIE 系列',
    color: '#2932e1',
    icon: 'Star',
    type: 'primary',
    hasSecret: true,
    hasVersion: false,
    models: ['ernie-bot-4', 'ernie-bot'],
  },
]

const form = reactive({
  openai:  { apiUrl: '', apiKey: '', secretKey: '', apiVersion: '', enabled: false },
  claude:  { apiUrl: '', apiKey: '', secretKey: '', apiVersion: '', enabled: false },
  tongyi:  { apiUrl: '', apiKey: '', secretKey: '', apiVersion: '', enabled: false },
  wenxin:  { apiUrl: '', apiKey: '', secretKey: '', apiVersion: '', enabled: false },
})

const meetingForm = reactive({
  consensusThreshold: 0.70,
  minAgreementCount: 2,
  maxRoundsDefault: 10,
  baseReward: 100,
  basePenalty: 50,
  dissentPenaltyFactor: 0.30,
})

async function loadConfig() {
  try {
    const res = await configApi.getModelConfig()
    const data = res.data || {}
    for (const key of Object.keys(form)) {
      if (data.models?.[key]) Object.assign(form[key], data.models[key])
    }
    if (data.meeting) Object.assign(meetingForm, data.meeting)
  } catch (e) {
    // 首次使用时后端可能没有配置，静默失败
  }
}

async function saveAll() {
  saving.value = true
  try {
    await configApi.saveModelConfig({ models: form, meeting: meetingForm })
    ElMessage.success('配置已保存，重启服务后生效')
  } finally {
    saving.value = false
  }
}

async function testConnection(provider) {
  testing[provider.key] = true
  testResult[provider.key] = null
  try {
    const res = await configApi.testConnection(provider.key, {
      apiUrl: form[provider.key].apiUrl,
      apiKey: form[provider.key].apiKey,
      secretKey: form[provider.key].secretKey,
      apiVersion: form[provider.key].apiVersion,
    })
    testResult[provider.key] = { ok: res.data?.success, msg: res.data?.message || '连接失败' }
  } catch (e) {
    testResult[provider.key] = { ok: false, msg: e.message || '请求异常' }
  } finally {
    testing[provider.key] = false
  }
}

onMounted(loadConfig)
</script>

<style scoped>
.model-config { display: flex; flex-direction: column; gap: 20px; }
.page-header { display: flex; justify-content: space-between; align-items: center; }
.page-header h2 { font-size: 22px; font-weight: 700; }
.subtitle { color: #909399; font-size: 13px; margin-top: 4px; }
.provider-card { border-radius: 12px; border: 1px solid #e4e7ed; transition: border-color .2s; }
.provider-card.is-enabled { border-color: #b3d8ff; }
.provider-header { display: flex; justify-content: space-between; align-items: center; }
.provider-title { display: flex; align-items: center; gap: 10px; }
.provider-name { font-size: 15px; font-weight: 700; }
</style>
