import { createRouter, createWebHistory } from 'vue-router'
import { getToken } from '@/utils/auth'

const routes = [
  { path: '/', redirect: '/home' },
  {
    path: '/login',
    name: 'Login',
    // 用 () => import() 懒加载：登录页和首页的代码会被拆成单独的文件，首屏加载更快
    component: () => import('@/views/Login.vue'),
    meta: { title: '登录' },
  },
  {
    path: '/register',
    name: 'Register',
    component: () => import('@/views/Register.vue'),
    meta: { title: '注册' },
  },
  {
    path: '/home',
    name: 'Home',
    component: () => import('@/views/Home.vue'),
    // requiresAuth 是自定义标记，下面守卫靠它判断这个页面要不要登录
    meta: { title: '我的网盘', requiresAuth: true },
  },
  // 兜底：用户手输乱七八糟的地址时不要白屏
  { path: '/:pathMatch(.*)*', redirect: '/home' },
]

const router = createRouter({
  // history 模式：地址栏是干净的 /home，没有 # 号（需要服务端配合，部署时让 Nginx 把未匹配的路径都指回 index.html）
  history: createWebHistory(),
  routes,
})

/**
 * 全局前置守卫：每次跳转前都会执行。
 *
 * 这里只做"前端有没有 token"这一层拦截，属于体验优化：
 * 真正的安全边界在后端拦截器，因为前端代码用户随便改，
 * 就算绕过守卫进了首页，一调接口照样返回 401。
 */
router.beforeEach((to) => {
  const hasToken = !!getToken()

  // 1. 要去需要登录的页面，但没 token -> 踢回登录页，并记住原本想去哪
  if (to.meta.requiresAuth && !hasToken) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  // 2. 已经登录了还想去登录/注册页 -> 直接送去首页，避免重复登录
  if (hasToken && (to.path === '/login' || to.path === '/register')) {
    return '/home'
  }

  // 3. 其余情况放行
  return true
})

// 顺便把标题改了，浏览器标签页能看出当前在哪个页面
router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} - 个人云盘` : '个人云盘'
})

export default router
