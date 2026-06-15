import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    meta: { public: true }
  },
  {
    path: '/',
    component: () => import('@/views/Layout.vue'),
    children: [
      { path: '', redirect: '/meetings' },
      { path: 'meetings', name: 'MeetingList', component: () => import('@/views/MeetingList.vue') },
      { path: 'meetings/create', name: 'MeetingCreate', component: () => import('@/views/MeetingCreate.vue') },
      { path: 'meetings/:id', name: 'MeetingDetail', component: () => import('@/views/MeetingDetail.vue') },
      { path: 'stock-watch', name: 'StockWatch', component: () => import('@/views/StockWatch.vue') },
      { path: 'tracking', name: 'TrackingCenter', component: () => import('@/views/TrackingCenter.vue') },
      { path: 'scores', name: 'ScoreBoard', component: () => import('@/views/ScoreBoard.vue') },
      { path: 'config', name: 'ModelConfig', component: () => import('@/views/ModelConfig.vue') },
      { path: 'member-templates', name: 'MemberTemplates', component: () => import('@/views/MemberTemplates.vue') },
      { path: 'workflow-templates', name: 'WorkflowTemplates', component: () => import('@/views/WorkflowTemplates.vue') },
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 路由守卫：未登录跳转到登录页
router.beforeEach((to) => {
  const token = localStorage.getItem('token')
  if (!to.meta.public && !token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.path === '/login' && token) {
    return { path: '/meetings' }
  }
})

export default router
