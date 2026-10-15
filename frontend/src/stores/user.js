import { ref } from 'vue'
import { defineStore } from 'pinia'
import { login as loginApi, getUserInfo } from '@/api/user'
import { getToken, setToken, removeToken } from '@/utils/auth'

/**
 * 用户状态（Pinia setup 写法，用起来和写组件几乎一样）。
 *
 * 为什么 token 除了 localStorage 还要在 store 里存一份：
 * localStorage 是"硬盘"，读它拿不到响应式效果；
 * store 里的 ref 是"内存"，登录后界面能立刻跟着变。
 * 初始值从 localStorage 读，是为了刷新页面后不掉登录态。
 */
export const useUserStore = defineStore('user', () => {
  const token = ref(getToken() || '')
  const userInfo = ref(null)

  /** 登录：拿 token 并存下来 */
  async function login(form) {
    const jwt = await loginApi(form)
    token.value = jwt
    setToken(jwt)
  }

  /** 拉取用户信息，首页展示用 */
  async function fetchUserInfo() {
    // 用户信息是"每次进首页都可能是新的"数据，所以不做本地缓存，直接查一次
    userInfo.value = await getUserInfo()
    return userInfo.value
  }

  /** 退出登录：只清本地。JWT 是无状态的，服务端那边这个 token 依然有效（见总结里的说明） */
  function logout() {
    token.value = ''
    userInfo.value = null
    removeToken()
  }

  return { token, userInfo, login, fetchUserInfo, logout }
})
