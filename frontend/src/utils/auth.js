/**
 * token 的本地存取。
 *
 * 单独抽一个文件的原因：axios 封装、Pinia store、路由守卫都要读写 token，
 * 如果放在 store 里，axios 又去 import store，很容易绕成循环依赖。
 *
 * 为什么用 localStorage 而不是 sessionStorage / cookie：
 * - localStorage 关掉浏览器再打开还在，用户不用每次重新登录；
 * - cookie 每次请求都会自动带上，容易踩 CSRF，而 JWT 放在 Header 里更可控。
 * 注意 localStorage 会被 XSS 偷走，所以生产项目更严格的做法是 httpOnly Cookie + CSRF Token，
 * 学习项目用 localStorage 是主流选择，面试被问到要能说出这个取舍。
 */
const TOKEN_KEY = 'net_disk_token'

export function getToken() {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(token) {
  localStorage.setItem(TOKEN_KEY, token)
}

export function removeToken() {
  localStorage.removeItem(TOKEN_KEY)
}
