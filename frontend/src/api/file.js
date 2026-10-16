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
