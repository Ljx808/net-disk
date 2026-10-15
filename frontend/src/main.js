import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'

import './style.css'
import App from './App.vue'
import router from './router'

const app = createApp(App)

// 顺序无所谓，但三者缺一不可：
// Pinia 管状态、Router 管页面跳转、ElementPlus 提供组件
app.use(createPinia())
app.use(router)
// 全局注册组件并设置中文（分页、弹窗等内置文案走这个语言包）
app.use(ElementPlus, { locale: zhCn })

app.mount('#app')
