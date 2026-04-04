import { createHttp } from '@/api/http'

const http = createHttp(15000)

export const authApi = {
  login: (data) => http.post('/auth/login', data),
  register: (data) => http.post('/auth/register', data),
  me: () => http.get('/auth/me'),
  logout: () => http.post('/auth/logout'),
}
