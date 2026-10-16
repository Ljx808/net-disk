<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { getFileList, getUploadConfig, uploadFile } from '@/api/file'

const router = useRouter()
const userStore = useUserStore()

const uploading = ref(false)
const loading = ref(false)

// 文件列表相关状态。分页状态放在这里而不是交给分页组件内部维护，
// 是因为"上传成功后跳到第 1 页"需要我们自己改 pageNum。
const files = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)

/** 单文件大小上限（字节）。从后端配置读，不在页面里写死数字 */
const maxFileSize = ref(0)

/** 字节转 GB，存的是字节数，展示给人看要换算 */
function toGB(bytes) {
  if (!bytes) return '0 GB'
  return (bytes / 1024 / 1024 / 1024).toFixed(2) + ' GB'
}

/** 去掉小数末尾多余的 0，100.00 显示成 100，看起来正常一些 */
function trimZero(num) {
  return parseFloat(num.toFixed(2))
}

/** 字节转带单位的大小，列表和提示语都用它 */
function formatSize(bytes) {
  const n = Number(bytes) || 0
  if (n <= 0) return '0 B'
  if (n < 1024) return n + ' B'
  if (n < 1024 * 1024) return trimZero(n / 1024) + ' KB'
  if (n < 1024 * 1024 * 1024) return trimZero(n / 1024 / 1024) + ' MB'
  return trimZero(n / 1024 / 1024 / 1024) + ' GB'
}

/**
 * 后端返回的是 ISO-8601 格式（2026-10-17T10:30:00），
 * 把中间的 T 换成空格更符合中文阅读习惯。
 * 注意这个时间没有时区信息，是后端机器上的本地时间，直接显示即可。
 */
function formatTime(time) {
  return time ? time.replace('T', ' ') : '-'
}

/** 拉取文件列表。翻页、改每页条数、上传成功后都调它 */
async function fetchList() {
  loading.value = true
  try {
    const res = await getFileList({ pageNum: pageNum.value, pageSize: pageSize.value })
    files.value = res.records
    total.value = res.total
  } catch (e) {
    // 失败提示已经由 axios 响应拦截器统一弹过，这里只需要不再往下抛异常
  } finally {
    loading.value = false
  }
}

/** 单文件大小上限，只用来做提示和前置校验，真正的校验以后端为准 */
async function fetchUploadConfig() {
  try {
    const res = await getUploadConfig()
    maxFileSize.value = res.maxFileSize
  } catch (e) {
    // 拿不到就不显示限制提示，不影响上传本身（后端仍会兜底校验）
  }
}

/** 改了每页条数，当前页很可能已经越界（比如在第 5 页把 10 条/页改成 50 条/页），回第 1 页最稳 */
function handleSizeChange() {
  pageNum.value = 1
  fetchList()
}

/**
 * 自定义上传：el-upload 默认会自己发 XHR，但那样不会带我们的 token、
 * 也不会解析统一 Result 结构，所以这里接管成走自己的 axios 封装。
 * 参数是 el-upload 传进来的对象，file 是浏览器原生的 File 对象。
 */
async function handleUpload({ file }) {
  // 前置校验：文件太大在本地直接拦下，不用等 100MB 传完才被后端拒绝。
  // maxFileSize 为 0 表示配置还没拿到，这时不拦，交给后端判断。
  if (maxFileSize.value > 0 && file.size > maxFileSize.value) {
    ElMessage.warning(`文件太大，单个文件最大 ${formatSize(maxFileSize.value)}`)
    return
  }

  uploading.value = true
  try {
    const res = await uploadFile(file)
    ElMessage.success(
      res.reused
        ? `上传成功：${res.filename}（服务端已有该文件，未重复占用磁盘空间）`
        : `上传成功：${res.filename}（${formatSize(res.fileSize)}）`,
    )
    // 回到第 1 页再刷新：列表按上传时间倒序，刚传的文件在第 1 页最上面。
    // 如果用户停在第 3 页，传完看不到自己刚上传的文件，会以为上传失败了。
    pageNum.value = 1
    await fetchList()
  } catch (e) {
    // 失败提示已经由 axios 响应拦截器统一弹过了，这里只需要不再往下抛异常
  } finally {
    uploading.value = false
  }
}

function handleLogout() {
  userStore.logout()
  router.push('/login')
}

onMounted(() => {
  // userInfo 这次请求同时验证了两件事：token 能通过后端拦截器、info 接口不返回密码。
  // 如果 token 失效，拦截器会弹出提示并自动跳回登录页，所以这里只需吞掉异常。
  userStore.fetchUserInfo().catch(() => {})
  fetchList()
  fetchUploadConfig()
})
</script>

<template>
  <div class="page">
    <el-card class="card">
      <template #header>
        <div class="header">
          <span>我的网盘</span>
          <el-button text type="primary" @click="handleLogout">退出登录</el-button>
        </div>
      </template>

      <el-descriptions :column="1" border>
        <el-descriptions-item label="用户 ID">{{ userStore.userInfo?.id ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="用户名">{{ userStore.userInfo?.username ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="邮箱">{{ userStore.userInfo?.email ?? '未填写' }}</el-descriptions-item>
        <el-descriptions-item label="容量上限">{{ toGB(userStore.userInfo?.storageLimit) }}</el-descriptions-item>
      </el-descriptions>

      <div class="upload-row">
        <el-upload :show-file-list="false" :http-request="handleUpload" :disabled="uploading">
          <el-button type="primary" :loading="uploading">
            {{ uploading ? '上传中...' : '上传文件' }}
          </el-button>
        </el-upload>
        <!-- 限制值来自后端配置，改 application.yml 这里会跟着变 -->
        <span v-if="maxFileSize" class="hint">单个文件最大 {{ formatSize(maxFileSize) }}</span>
      </div>

      <el-table
        v-loading="loading"
        :data="files"
        class="table"
        empty-text="还没有文件，点上方按钮上传"
      >
        <el-table-column prop="filename" label="文件名" min-width="240" show-overflow-tooltip />
        <el-table-column label="大小" width="110" align="right">
          <template #default="{ row }">{{ formatSize(row.fileSize) }}</template>
        </el-table-column>
        <el-table-column label="上传时间" width="180">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
      </el-table>

      <!--
        分页组件内部不再自己维护页码，而是双向绑定到 pageNum/pageSize，
        这样代码里改 pageNum.value = 1 就能让分页高亮跟着回到第 1 页，
        否则会出现"数据是第 1 页的，分页高亮还停在第 3 页"这种不一致。
      -->
      <el-pagination
        v-model:current-page="pageNum"
        v-model:page-size="pageSize"
        :page-sizes="[10, 20, 50]"
        :total="total"
        layout="total, sizes, prev, pager, next"
        class="pagination"
        @current-change="fetchList"
        @size-change="handleSizeChange"
      />
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
  width: 880px;
  height: fit-content;
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.upload-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 16px 0;
}

.hint {
  font-size: 13px;
  color: #909399;
}

/* 竖向滚动条固定在表格内部，避免文件名过长时把整张卡片撑宽 */
.table {
  width: 100%;
}

.pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
