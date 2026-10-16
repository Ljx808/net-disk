import request from './request'

/**
 * 上传单个文件。
 *
 * 两个容易踩的点：
 * 1. FormData 的字段名必须叫 file，和后端 @RequestParam("file") 严格对应，拼错就是 400；
 * 2. 不要手动设置 'Content-Type': 'multipart/form-data'。浏览器需要自己往这个头里补一个
 *    boundary 分隔符，手动设置会把 boundary 丢掉，后端直接解析失败。
 *    传 FormData 给 axios，它会交给浏览器自动处理。
 * 3. 单独把超时调到 60 秒：request.js 里默认 10 秒是给普通接口的，
 *    传大文件很容易超时误报失败。
 */
export function uploadFile(file) {
  const formData = new FormData()
  formData.append('file', file)
  return request.post('/file/upload', formData, { timeout: 60000 })
}

/**
 * 分页查询当前用户的文件列表。
 *
 * 用 get 的第二个参数传 query 参数，axios 会自动拼成 ?pageNum=1&pageSize=10，
 * 不用自己手拼字符串（手拼迟早会漏 encodeURIComponent）。
 *
 * @param {{pageNum: number, pageSize: number}} params
 */
export function getFileList(params) {
  return request.get('/file/list', { params })
}

/** 上传配置（目前只有单文件大小上限），用于页面提示和上传前的前置校验 */
export function getUploadConfig() {
  return request.get('/file/config')
}

/**
 * 下载文件，返回 Blob。
 *
 * 为什么不能用 window.open(url) 或 <a href>：
 * JWT 存在 localStorage 里，是靠 axios 请求拦截器手动加 Authorization 头的。
 * 而 window.open / <a href> 是浏览器自己发起的导航请求，我们没有任何机会插手加头，
 * 后端拦截器拿不到 token → 必然 401。
 * （反过来说：如果认证走 Cookie，浏览器会自动带上，那时 <a href> 是能直接用的。
 *   token 方案换来了免疫 CSRF，代价就是下载这类请求必须自己用 XHR 拉。)
 *
 * responseType: 'blob' 是必须的：不加的话 axios 会把二进制按文本解析，文件直接损坏。
 * 超时给 60 秒，和上传同理，大文件下载比普通接口慢得多。
 */
export function downloadFile(id) {
  return request.get(`/file/download/${id}`, { responseType: 'blob', timeout: 60000 })
}
