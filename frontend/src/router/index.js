import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    component: () => import('@/views/Layout.vue'),
    children: [
      { path: '', redirect: '/meetings' },
      { path: 'meetings', name: 'MeetingList', component: () => import('@/views/MeetingList.vue') },
      { path: 'meetings/create', name: 'MeetingCreate', component: () => import('@/views/MeetingCreate.vue') },
      { path: 'meetings/:id', name: 'MeetingDetail', component: () => import('@/views/MeetingDetail.vue') },
      { path: 'scores', name: 'ScoreBoard', component: () => import('@/views/ScoreBoard.vue') },
    ]
  }
]

export default createRouter({
  history: createWebHistory(),
  routes
})
