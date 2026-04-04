import axios from 'axios'
import { ElMessage } from 'element-plus'

const http = axios.create({ baseURL: '/api', timeout: 15000 })

http.interceptors.response.use(
  res => res.data.code === 200 ? res.data : Promise.reject(new Error(res.data.msg || '请求失败')),
  err => { ElMessage.error(err.message); return Promise.reject(err) }
)

export const configApi = {
  getModelConfig: () => http.get('/config/models'),
  saveModelConfig: (data) => http.post('/config/models', data),
  testConnection: (provider, data) => http.post(`/config/models/test/${provider}`, data),
}
