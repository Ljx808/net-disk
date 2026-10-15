import request from './request'

/**
 * 用户模块接口。
 * 约定：api 层只负责"发请求 + 定义入参"，不做任何业务判断。
 * 后端的 Result 已经被 request.js 拦截器拆过，所以这里返回的 Promise 直接就是 data。
 */

/** 注册，入参 { username, password, email } */
export function register(data) {
  return request.post('/user/register', data)
}

/** 登录，resolve 出来的是 JWT 字符串 */
export function login(data) {
  return request.post('/user/login', data)
}

/** 查询当前登录用户信息，resolve 出来的是 { id, username, email, storageLimit, createTime } */
export function getUserInfo() {
  return request.get('/user/info')
}
