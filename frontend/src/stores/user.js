import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem('token') || '')
  const userId = ref(Number(localStorage.getItem('userId')) || null)
  const nickname = ref(localStorage.getItem('nickname') || '')
  const username = ref(localStorage.getItem('username') || '')

  const isLoggedIn = computed(() => !!token.value)

  function setUser(data) {
    token.value = data.token
    userId.value = data.userId
    nickname.value = data.nickname
    username.value = data.username
    localStorage.setItem('token', data.token)
    localStorage.setItem('userId', data.userId)
    localStorage.setItem('nickname', data.nickname)
    localStorage.setItem('username', data.username)
  }

  function logout() {
    token.value = ''
    userId.value = null
    nickname.value = ''
    username.value = ''
    localStorage.removeItem('token')
    localStorage.removeItem('userId')
    localStorage.removeItem('nickname')
    localStorage.removeItem('username')
  }

  return { token, userId, nickname, username, isLoggedIn, setUser, logout }
})
