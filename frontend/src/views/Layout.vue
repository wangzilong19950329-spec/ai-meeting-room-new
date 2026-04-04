<template>
  <el-container class="app-layout">
    <!-- 桌面侧边栏 -->
    <el-aside width="220px" class="sidebar desktop-sidebar">
      <div class="logo">
        <el-icon size="26" color="#409eff"><ChatDotRound /></el-icon>
        <span>AI 会议室</span>
      </div>
      <el-menu :default-active="activeMenu" router
        background-color="#1a1f2e" text-color="#a0aec0" active-text-color="#ffffff">
        <el-menu-item index="/meetings"><el-icon><Grid /></el-icon><span>会议列表</span></el-menu-item>
        <el-menu-item index="/meetings/create"><el-icon><Plus /></el-icon><span>发起会议</span></el-menu-item>
        <el-menu-item index="/scores"><el-icon><TrophyBase /></el-icon><span>积分排行</span></el-menu-item>
        <el-menu-item index="/config"><el-icon><Setting /></el-icon><span>模型配置</span></el-menu-item>
      </el-menu>
    </el-aside>

    <!-- 移动端抽屉侧边栏 -->
    <el-drawer v-model="drawerOpen" direction="ltr" size="220px" :with-header="false"
               class="mobile-drawer">
      <div class="logo" style="background:#1a1f2e; padding:20px 20px 16px; border-bottom:1px solid #2d3448">
        <el-icon size="26" color="#409eff"><ChatDotRound /></el-icon>
        <span style="color:#fff; font-size:18px; font-weight:700">AI 会议室</span>
      </div>
      <el-menu :default-active="activeMenu" router @select="drawerOpen = false"
        background-color="#1a1f2e" text-color="#a0aec0" active-text-color="#ffffff"
        style="border-right:none; height:100%">
        <el-menu-item index="/meetings"><el-icon><Grid /></el-icon><span>会议列表</span></el-menu-item>
        <el-menu-item index="/meetings/create"><el-icon><Plus /></el-icon><span>发起会议</span></el-menu-item>
        <el-menu-item index="/scores"><el-icon><TrophyBase /></el-icon><span>积分排行</span></el-menu-item>
        <el-menu-item index="/config"><el-icon><Setting /></el-icon><span>模型配置</span></el-menu-item>
      </el-menu>
    </el-drawer>

    <!-- 主内容区 -->
    <el-container>
      <el-header class="header">
        <div class="header-left">
          <!-- 移动端汉堡按钮 -->
          <el-button class="hamburger" :icon="Expand" text @click="drawerOpen = true" />
          <el-breadcrumb separator="/">
            <el-breadcrumb-item :to="{ path: '/' }">首页</el-breadcrumb-item>
            <el-breadcrumb-item>{{ currentTitle }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div class="header-right">
          <el-tag type="success" effect="dark" size="small" class="status-tag">
            <el-icon><Connection /></el-icon> <span class="tag-text">多模型协作</span>
          </el-tag>
        </div>
      </el-header>
      <el-main class="main-content">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import { Expand } from '@element-plus/icons-vue'

const route = useRoute()
const drawerOpen = ref(false)

const activeMenu = computed(() => {
  if (route.path.startsWith('/meetings/create')) return '/meetings/create'
  if (route.path.startsWith('/meetings/')) return '/meetings'
  return route.path
})
const currentTitle = computed(() => {
  const map = {
    '/meetings': '会议列表',
    '/meetings/create': '发起会议',
    '/scores': '积分排行',
    '/config': '模型配置',
  }
  if (route.path.startsWith('/meetings/') && route.params.id) return '会议详情'
  return map[route.path] || ''
})
</script>

<style scoped>
.app-layout { height: 100vh; }
.sidebar { background: #1a1f2e; display: flex; flex-direction: column; }
.logo { display: flex; align-items: center; gap: 10px; padding: 20px 20px 16px;
  font-size: 18px; font-weight: 700; color: #fff; border-bottom: 1px solid #2d3448; }
.el-menu { border-right: none; flex: 1; }
.header { background: #fff; border-bottom: 1px solid #ebeef5;
  display: flex; align-items: center; justify-content: space-between; padding: 0 16px; }
.header-left { display: flex; align-items: center; gap: 8px; }
.main-content { background: #f5f7fa; padding: 16px; }
.hamburger { display: none; font-size: 20px; }

@media (max-width: 768px) {
  .desktop-sidebar { display: none !important; }
  .hamburger { display: inline-flex !important; }
  .main-content { padding: 12px 10px; }
  .tag-text { display: none; }
}
</style>

<style>
* { box-sizing: border-box; margin: 0; padding: 0; }
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; }
/* 移动端抽屉背景色 */
.mobile-drawer .el-drawer__body { background: #1a1f2e; padding: 0; }
</style>
