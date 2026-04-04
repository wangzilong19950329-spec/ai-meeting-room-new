import axios from 'axios'
import { ElMessage } from 'element-plus'

const http = axios.create({ baseURL: '/api', timeout: 15000 })

http.interceptors.request.use(config => {
  const token = localStorage.getItem('token')
  if (token) config.headers['X-Token'] = token
  return config
})

http.interceptors.response.use(
  res => res.data.code === 200 ? res.data : Promise.reject(new Error(res.data.msg || '请求失败')),
  err => {
    ElMessage.error(err.message || '网络错误')
    return Promise.reject(err)
  }
)

export const authApi = {
  login: (data) => http.post('/auth/login', data),
  register: (data) => http.post('/auth/register', data),
  me: () => http.get('/auth/me'),
  logout: () => http.post('/auth/logout'),
}
