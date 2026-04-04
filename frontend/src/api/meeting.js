import axios from 'axios'
import { ElMessage } from 'element-plus'

const http = axios.create({ baseURL: '/api', timeout: 30000 })

http.interceptors.request.use(config => {
  const token = localStorage.getItem('token')
  if (token) config.headers['X-Token'] = token
  return config
})

http.interceptors.response.use(
  res => {
    if (res.data.code !== 200) {
      ElMessage.error(res.data.msg || '请求失败')
      return Promise.reject(res.data)
    }
    return res.data
  },
  err => {
    ElMessage.error(err.message || '网络错误')
    return Promise.reject(err)
  }
)

export const meetingApi = {
  create: (data) => http.post('/meetings', data),
  start: (id) => http.post(`/meetings/${id}/start`),
  getDetail: (id) => http.get(`/meetings/${id}`),
  list: (creatorId) => http.get('/meetings', { params: { creatorId } }),
  cancel: (id) => http.post(`/meetings/${id}/cancel`),
  verify: (id, valid) => http.post(`/meetings/${id}/verify`, null, { params: { valid } }),
  getContributions: (id) => http.get(`/meetings/${id}/contributions`),
  getMemberScores: (memberId) => http.get(`/members/${memberId}/scores`),
}
