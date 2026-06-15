<template>
  <div class="member-templates">
    <div class="page-header">
      <div>
        <h2>议员模版库</h2>
        <p class="subtitle">预配置议员，创建会议时直接引用，无需重复填写</p>
      </div>
      <el-button type="primary" @click="openCreate">
        <el-icon><Plus /></el-icon> 新建模版
      </el-button>
    </div>

    <div v-loading="loading" class="template-grid">
      <el-empty v-if="!loading && templates.length === 0"
        description="暂无模版，点击右上角新建" :image-size="80" />

      <el-card v-for="t in templates" :key="t.id" shadow="hover" class="tpl-card">
        <div class="tpl-top">
          <div class="tpl-avatar" :style="{ background: avatarColor(t.name) }">
            {{ t.name.charAt(0) }}
          </div>
          <div class="tpl-meta">
            <div class="tpl-name">{{ t.name }}</div>
            <div class="tpl-meta-tags">
              <el-tag v-if="t.modelType" size="small" effect="plain">{{ t.modelType }}</el-tag>
              <el-tag v-if="t.usageCount > 0" size="small" type="warning" effect="plain">
                被 {{ t.usageCount }} 场会议引用
              </el-tag>
            </div>
          </div>
          <div class="tpl-actions">
            <el-button link type="primary" @click="openEdit(t)"><el-icon><Edit /></el-icon></el-button>
            <el-button link type="danger" @click="handleDelete(t)"><el-icon><Delete /></el-icon></el-button>
          </div>
        </div>

        <div v-if="t.tags" class="tpl-tags">
          <el-tag v-for="tag in t.tags.split(',')" :key="tag" size="small" type="info"
                  effect="plain" style="margin:2px 2px 0 0">{{ tag.trim() }}</el-tag>
        </div>

        <div class="tpl-fields">
          <div v-if="t.persona" class="tpl-field">
            <span class="field-label">立场背景</span>
            <span class="field-val">{{ t.persona }}</span>
          </div>
          <div v-if="t.skills" class="tpl-field">
            <span class="field-label">专业技能</span>
            <span class="field-val">{{ t.skills }}</span>
          </div>
          <div v-if="t.personality" class="tpl-field">
            <span class="field-label">性格特点</span>
            <span class="field-val">{{ t.personality }}</span>
          </div>
        </div>
      </el-card>
    </div>

    <!-- 创建/编辑弹窗 -->
    <el-dialog v-model="dialogVisible" :title="editing ? '编辑模版' : '新建议员模版'"
               width="560px" :close-on-click-modal="false">
      <el-form :model="form" ref="formRef" label-width="80px">
        <el-form-item label="名称" prop="name" :rules="[{required:true,message:'请输入名称'}]">
          <el-input v-model="form.name" placeholder="如：理性经济学家" />
        </el-form-item>
        <el-form-item label="默认模型">
          <el-select v-model="form.modelType" placeholder="选择默认绑定模型（可选）" clearable style="width:100%">
            <el-option v-for="m in availableModels" :key="m.modelId"
                       :label="m.displayName" :value="m.modelId" />
          </el-select>
        </el-form-item>
        <el-form-item label="标签">
          <el-input v-model="form.tags" placeholder="如：经济,金融,宏观（逗号分隔）" />
        </el-form-item>
        <el-form-item label="立场背景">
          <el-input v-model="form.persona" type="textarea" :rows="3"
                    placeholder="该议员的立场、背景和分析角度..." />
        </el-form-item>
        <el-form-item label="专业技能">
          <el-input v-model="form.skills" type="textarea" :rows="2"
                    placeholder="专业领域、擅长方向..." />
        </el-form-item>
        <el-form-item label="性格特点">
          <el-input v-model="form.personality" placeholder="如：严谨、批判性强、善于引用数据" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { templateApi } from '@/api/meeting'
import { configApi } from '@/api/config'

const loading = ref(false)
const saving = ref(false)
const templates = ref([])
const availableModels = ref([])
const dialogVisible = ref(false)
const editing = ref(false)
const formRef = ref()

const emptyForm = () => ({ name: '', modelType: '', tags: '', persona: '', skills: '', personality: '' })
const form = ref(emptyForm())

const COLORS = ['#409eff','#67c23a','#e6a23c','#f56c6c','#9b59b6','#1abc9c','#e67e22','#2980b9']
function avatarColor(name) {
  let h = 0
  for (let c of name) h = (h * 31 + c.charCodeAt(0)) & 0xffff
  return COLORS[h % COLORS.length]
}

async function load() {
  loading.value = true
  try {
    const [tRes, mRes] = await Promise.all([templateApi.list(), configApi.availableModels()])
    templates.value = tRes.data || []
    availableModels.value = mRes.data || []
  } finally { loading.value = false }
}

function openCreate() {
  form.value = emptyForm()
  editing.value = false
  dialogVisible.value = true
}
function openEdit(t) {
  form.value = { ...t }
  editing.value = true
  dialogVisible.value = true
}

async function handleSave() {
  await formRef.value.validate()
  saving.value = true
  try {
    if (editing.value) {
      await templateApi.update(form.value.id, form.value)
      ElMessage.success('已更新')
    } else {
      await templateApi.create(form.value)
      ElMessage.success('模版已创建')
    }
    dialogVisible.value = false
    load()
  } finally { saving.value = false }
}

async function handleDelete(t) {
  const msg = t.usageCount > 0
    ? `模版「${t.name}」已被 ${t.usageCount} 场会议的议员引用。议员是创建会议时复制的，删除模版不会影响已有会议，仅模版库中移除。确认删除？`
    : `确认删除模版「${t.name}」？`
  await ElMessageBox.confirm(msg, '删除模版', {
    type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
    dangerouslyUseHTMLString: false
  })
  await templateApi.remove(t.id)
  ElMessage.success('已删除')
  load()
}

onMounted(load)
</script>

<style scoped>
.member-templates { display: flex; flex-direction: column; gap: 16px; }
.page-header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 10px; }
.page-header h2 { font-size: 20px; font-weight: 700; color: #1a1f2e; }
.subtitle { color: #909399; font-size: 13px; margin-top: 2px; }

.template-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 14px;
}

.tpl-card { border-radius: 10px; }
.tpl-top { display: flex; align-items: flex-start; gap: 10px; margin-bottom: 10px; }
.tpl-avatar {
  width: 40px; height: 40px; border-radius: 50%; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  color: #fff; font-weight: 700; font-size: 16px;
}
.tpl-meta { flex: 1; min-width: 0; }
.tpl-meta-tags { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 3px; }
.tpl-name { font-weight: 600; font-size: 15px; }
.tpl-actions { display: flex; gap: 2px; flex-shrink: 0; }

.tpl-tags { margin-bottom: 8px; }

.tpl-fields { display: flex; flex-direction: column; gap: 6px; }
.tpl-field { display: flex; gap: 6px; font-size: 12px; }
.field-label {
  color: #909399; flex-shrink: 0; white-space: nowrap;
  background: #f5f7fa; padding: 1px 6px; border-radius: 4px;
}
.field-val { color: #303133; line-height: 1.5; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
</style>
