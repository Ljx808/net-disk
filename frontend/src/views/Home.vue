<script setup>
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()

/** 字节转 GB，存的是字节数，展示给人看要换算 */
function toGB(bytes) {
  if (!bytes) return '0 GB'
  return (bytes / 1024 / 1024 / 1024).toFixed(2) + ' GB'
}

function handleLogout() {
  userStore.logout()
  router.push('/login')
}

onMounted(() => {
  // 这次请求同时验证了两件事：token 能通过后端拦截器、info 接口不返回密码。
  // 如果 token 失效，拦截器会弹出提示并自动跳回登录页，所以这里只需吞掉异常。
  userStore.fetchUserInfo().catch(() => {})
})
</script>

<template>
  <div class="page">
    <el-card class="card">
      <template #header>
        <div class="header">
          <span>我的网盘（占位页）</span>
          <el-button text type="primary" @click="handleLogout">退出登录</el-button>
        </div>
      </template>

      <el-descriptions :column="1" border>
        <el-descriptions-item label="用户 ID">{{ userStore.userInfo?.id ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="用户名">{{ userStore.userInfo?.username ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="邮箱">{{ userStore.userInfo?.email ?? '未填写' }}</el-descriptions-item>
        <el-descriptions-item label="容量上限">{{ toGB(userStore.userInfo?.storageLimit) }}</el-descriptions-item>
      </el-descriptions>

      <el-empty description="文件列表还没做，Day 4 见" />
    </el-card>
  </div>
</template>

<style scoped>
.page {
  height: 100%;
  display: flex;
  justify-content: center;
  padding: 40px 20px;
}

.card {
  width: 640px;
  height: fit-content;
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
</style>
