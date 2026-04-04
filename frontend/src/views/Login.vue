<template>
  <div class="login-page">
    <div class="login-box">
      <div class="login-logo">
        <el-icon size="48" color="#409eff"><ChatDotRound /></el-icon>
        <h1>AI 会议室</h1>
        <p>多智能体协作决策平台</p>
      </div>

      <el-tabs v-model="activeTab" class="login-tabs">
        <el-tab-pane label="登录" name="login">
          <el-form :model="loginForm" ref="loginRef" @keyup.enter="handleLogin" autocomplete="off">
            <el-form-item prop="username" :rules="[{required:true,message:'请输入用户名'}]">
              <el-input v-model="loginForm.username" placeholder="用户名" size="large" :prefix-icon="User"
                        name="login-username" autocomplete="username" />
            </el-form-item>
            <el-form-item prop="password" :rules="[{required:true,message:'请输入密码'}]">
              <el-input v-model="loginForm.password" type="password" placeholder="密码"
                        size="large" :prefix-icon="Lock" show-password
                        name="login-password" autocomplete="current-password" />
            </el-form-item>
            <el-button type="primary" size="large" :loading="loading" @click="handleLogin" style="width:100%">
              登 录
            </el-button>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="注册" name="register">
          <el-form :model="regForm" ref="regRef" @keyup.enter="handleRegister" autocomplete="off">
            <el-form-item prop="username" :rules="[{required:true,message:'请输入用户名'},{min:3,max:20,message:'3-20个字符'}]">
              <el-input v-model="regForm.username" placeholder="用户名（3-20字符）" size="large" :prefix-icon="User"
                        name="reg-username" autocomplete="username" />
            </el-form-item>
            <el-form-item prop="password" :rules="[{required:true,message:'请输入密码'},{min:6,message:'至少6位'}]">
              <el-input v-model="regForm.password" type="password" placeholder="密码（至少6位）"
                        size="large" :prefix-icon="Lock" show-password
                        name="reg-password" autocomplete="new-password" />
            </el-form-item>
            <el-button type="primary" size="large" :loading="loading" @click="handleRegister" style="width:100%">
              注 册
            </el-button>
          </el-form>
        </el-tab-pane>
      </el-tabs>

      <div class="login-hint">默认账号：admin / admin123</div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { authApi } from '@/api/auth'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()
const activeTab = ref('login')
const loading = ref(false)
const loginRef = ref()
const regRef = ref()

const loginForm = reactive({ username: '', password: '' })
const regForm = reactive({ username: '', password: '' })

async function handleLogin() {
  await loginRef.value.validate()
  loading.value = true
  try {
    const res = await authApi.login(loginForm)
    userStore.setUser(res.data)
    ElMessage.success(`欢迎回来，${res.data.nickname}！`)
    router.replace('/meetings')
  } finally { loading.value = false }
}

async function handleRegister() {
  await regRef.value.validate()
  loading.value = true
  try {
    await authApi.register(regForm)
    ElMessage.success('注册成功，请登录')
    activeTab.value = 'login'
    loginForm.username = regForm.username
    loginForm.password = ''
  } finally { loading.value = false }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh; display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #1a1f2e 0%, #2d3448 100%);
  padding: 20px;
}
.login-box {
  background: #fff; border-radius: 16px; padding: 40px 36px;
  width: 100%; max-width: 400px; box-shadow: 0 20px 60px rgba(0,0,0,0.3);
}
.login-logo { text-align: center; margin-bottom: 28px; }
.login-logo h1 { font-size: 24px; font-weight: 700; color: #1a1f2e; margin: 12px 0 6px; }
.login-logo p { font-size: 13px; color: #909399; }
.login-tabs { margin-bottom: 8px; }
:deep(.el-tabs__nav-wrap::after) { display: none; }
:deep(.el-tabs__item) { font-size: 15px; }
.login-hint { text-align: center; font-size: 12px; color: #c0c4cc; margin-top: 16px; }
</style>
