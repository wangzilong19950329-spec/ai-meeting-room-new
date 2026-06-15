import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'

let handlingUnauthorized = false

function handleUnauthorized(message) {
  localStorage.removeItem('token')
  localStorage.removeItem('userId')
  localStorage.removeItem('nickname')
  localStorage.removeItem('username')

  if (handlingUnauthorized) return
  handlingUnauthorized = true
  ElMessage.error(message || '登录已失效，请重新登录')

  const currentPath = router.currentRoute.value.fullPath
  const redirect = currentPath && currentPath !== '/login' ? currentPath : '/meetings'
  router.replace({ path: '/login', query: { redirect } }).finally(() => {
    handlingUnauthorized = false
  })
}

export function createHttp(timeout = 15000) {
  const http = axios.create({ baseURL: '/api', timeout })

  http.interceptors.request.use(config => {
    const token = localStorage.getItem('token')
    if (token) config.headers['X-Token'] = token
    return config
  })

  http.interceptors.response.use(
    res => {
      if (res.status === 401 || res.data?.code === 401) {
        handleUnauthorized(res.data?.msg)
        return Promise.reject(res.data || new Error('未登录'))
      }
      if (res.data?.code !== 200) {
        if (!res.config?.suppressErrorMessage) {
          ElMessage.error(res.data?.msg || '请求失败')
        }
        return Promise.reject(res.data)
      }
      return res.data
    },
    err => {
      if (err.response?.status === 401) {
        handleUnauthorized(err.response?.data?.msg)
      } else if (!err.config?.suppressErrorMessage) {
        ElMessage.error(err.message || '网络错误')
      }
      return Promise.reject(err)
    }
  )

  return http
}
