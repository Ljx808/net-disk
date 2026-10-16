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
 * 后端返回统一是 { code, message, data }，这里做了三件事：
 * 1. 成功（code===200）时直接返回 res.data，所以业务代码里拿到的是纯数据，
 *    不用每次都写 res.data.data 这种绕口的取值；
 * 2. 失败时统一弹提示并 reject，业务代码里只需要处理成功分支，
 *    想额外处理错误再自己 try/catch；
 * 3. 二进制响应（responseType 为 blob 的下载接口）整个跳过第 1、2 条，原样返回，
 *    包括出错时从 Blob 里把后端的 JSON 提示解析出来。
 */
request.interceptors.response.use(
  (response) => {
    // 文件下载这类接口要的是二进制，调用时会传 responseType: 'blob'。
    // 它的响应体不是 Result 结构（没有 code/message），所以必须原样放行，
    // 否则下面 res.code === 200 判断不成立，会掉进失败分支弹一个假的"请求失败"。
    if (response.config.responseType === 'blob') {
      return response.data
    }

    const res = response.data
    if (res.code === 200) {
      return res.data
    }
    // 业务失败（如"用户名已存在"），HTTP 状态码仍是 200，靠 code 判断
    ElMessage.error(res.message || '请求失败')
    return Promise.reject(new Error(res.message || '请求失败'))
  },
  // 这里声明成 async：下面的错误响应体如果是 Blob，需要 await 读文本才能拿到后端文案
  async (error) => {
    const status = error.response?.status
    const body = error.response?.data
    let message

    if (body instanceof Blob) {
      // 请求带了 responseType: 'blob' 时，连错误响应体也是 Blob（里面装的其实是 JSON 文本）。
      // 不解析的话 body.message 永远是 undefined，用户只能看到"请求失败(404)"，
      // 完全不知道是"文件不存在"还是"文件内容缺失"。
      try {
        message = JSON.parse(await body.text()).message
      } catch (e) {
        // 响应体不是 JSON（比如网关吐的 HTML 错误页），解析失败就交给下面的兜底文案
      }
    } else {
      message = body?.message
    }

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
