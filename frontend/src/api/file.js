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
 *
 * parentId 是目标文件夹的 user_file.id，0 表示根目录。
 * 它和 file 一样走表单字段：后端 upload 方法的两个参数都是 @RequestParam。
 */
export function uploadFile(file, parentId = 0) {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('parentId', parentId)
  return request.post('/file/upload', formData, { timeout: 60000 })
}

/**
 * 分页查询当前用户的文件列表。
 *
 * 用 get 的第二个参数传 query 参数，axios 会自动拼成 ?pageNum=1&pageSize=10，
 * 不用自己手拼字符串（手拼迟早会漏 encodeURIComponent）。
 *
 * silentError：列表加载失败由页面自己决定怎么提示。
 * 典型场景是"进入的目录刚被删掉"（40401），页面要自动退回根目录 + 一条提示，
 * 如果拦截器先弹一次、页面再弹一次，用户会看到两条重复的错误。
 *
 * @param {{pageNum: number, pageSize: number, parentId?: number}} params
 */
export function getFileList(params) {
  return request.get('/file/list', { params, silentError: true })
}

/**
 * 新建文件夹。
 *
 * 参数放 JSON 请求体（后端是 @Valid @RequestBody CreateFolderDTO）：
 * 文件夹名可能含空格/中文，放 body 不用自己处理 URL 编码。
 *
 * 重名 409/40901、非法名 400/40000 都由后端判定，前端不重复实现一套规则，
 * 避免两边规则不一致（前端放行、后端拒绝，或者反过来）。
 */
export function createFolder(parentId, foldername) {
  return request.post('/file/folder', { parentId, foldername })
}

/** 软删除（移入回收站）。文件夹必须为空，否则后端返回 409/40902 */
export function deleteFile(id) {
  return request.delete(`/file/${id}`)
}

/** 从回收站恢复。同目录已有同名项时后端返回 409/40901，不自动改名 */
export function restoreFile(id) {
  return request.put(`/file/${id}/restore`)
}

/**
 * 回收站列表。
 *
 * 同样 silentError：回收站页自己处理"加载失败"的提示，
 * 避免拦截器和页面各弹一次。
 */
export function getRecycleList(params) {
  return request.get('/file/recycle', { params, silentError: true })
}

/**
 * 彻底删除回收站中的一条（不可恢复）。
 *
 * 返回 PurgeItemVO：refCountAfter / shared / physicalDeleted / status / reason。
 * shared=true 表示物理文件还被别的记录引用，本次只删记录、不删盘，页面要如实告诉用户。
 */
export function purgeFile(id) {
  return request.delete(`/file/recycle/${id}`)
}

/**
 * 清空当前用户的回收站。
 *
 * 返回 PurgeBatchVO：requestedCount / deletedCount / failedCount / items。
 * 允许部分成功——某一条可能刚好被另一个请求恢复/删掉，
 * 页面必须按 failedCount 和 items 如实展示，不能 HTTP 200 就报"全部成功"。
 */
export function purgeAll() {
  return request.delete('/file/recycle')
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
 *
 * 出错时（40003 下载文件夹、40903 恢复冲突等）后端返回的是 HTTP 4xx + JSON，
 * axios 会走错误分支，业务代码拿到的是 reject，不会把错误 JSON 交给 saveBlob。
 */
export function downloadFile(id) {
  return request.get(`/file/download/${id}`, { responseType: 'blob', timeout: 60000 })
}
