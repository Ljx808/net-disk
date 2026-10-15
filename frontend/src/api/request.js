import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'
import { getToken, removeToken } from '@/utils/auth'

/**
 * axios 实例。整个项目只导出这一个实例，所有接口都走它，
 * 这样"带 token""解析 Result""处理 401"只需要在一个地方写。
 */
const request = axios.create({
  // 用相对路径，请求会打到当前域名（开发时是 5173），再由 Vite 代理转发给后端 8080
  baseURL: '/api',
  timeout: 10000,
})

/**
 * 请求拦截器：发出去之前，自动把 token 塞进请求头。
 * 这样每个接口调用处都不用关心 token 的事。
 */
request.interceptors.request.use(
  (config) => {
    const token = getToken()
    if (token) {
      // 后端拦截器期望的格式是 "Bearer xxx"
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error),
)

/**
 * 响应拦截器：统一拆包后端的 Result 结构。
 *
 * 后端返回统一是 { code, message, data }，这里做了两件事：
 * 1. 成功（code===200）时直接返回 res.data，所以业务代码里拿到的是纯数据，
 *    不用每次都写 res.data.data 这种绕口的取值；
 * 2. 失败时统一弹提示并 reject，业务代码里只需要处理成功分支，
 *    想额外处理错误再自己 try/catch。
 */
request.interceptors.response.use(
  (response) => {
    const res = response.data
    if (res.code === 200) {
      return res.data
    }
    // 业务失败（如"用户名已存在"），HTTP 状态码仍是 200，靠 code 判断
    ElMessage.error(res.message || '请求失败')
    return Promise.reject(new Error(res.message || '请求失败'))
  },
  (error) => {
    const status = error.response?.status
    const message = error.response?.data?.message

    if (status === 401) {
      // token 缺失、伪造或过期：清掉本地 token 并回登录页
      removeToken()
      ElMessage.error(message || '登录已过期，请重新登录')
      // 带上当前位置，登录成功后能跳回来
      if (router.currentRoute.value.path !== '/login') {
        router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
      }
    } else if (status) {
      ElMessage.error(message || `请求失败(${status})`)
    } else {
      // 没有 response 说明请求根本没到服务器：后端没启动、代理转发失败、超时等
      ElMessage.error('网络异常，请检查后端服务是否已启动')
    }
    return Promise.reject(error)
  },
)

export default request
