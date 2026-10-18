<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'
import {
  createFolder,
  deleteFile,
  downloadFile,
  getFileList,
  getUploadConfig,
  uploadFile,
} from '@/api/file'

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

/**
 * 目录导航栈，栈顶就是当前目录。
 * 第一项固定是根目录（id=0），面包屑直接渲染这个数组。
 *
 * 刻意不做 URL 同步（不用 ?parentId= 或 /folder/:id）：刷新页面回到根目录是可接受的行为，
 * 换成 URL 同步要处理"刷新时逐级校验祖先目录是否还存在"，与当前收益不成比例。
 * 需要保留现场的场景（分享链接、直达某个目录）以后单独设计。
 */
const navStack = ref([{ id: 0, name: '全部文件' }])
const currentFolder = computed(() => navStack.value[navStack.value.length - 1])
const currentParentId = computed(() => currentFolder.value.id)
const isRoot = computed(() => navStack.value.length === 1)
const emptyText = computed(() => (isRoot.value ? '还没有文件，点上方按钮上传' : '这个文件夹是空的'))

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

/**
 * 拉取当前目录的文件列表。
 *
 * 用 currentParentId 而不是固定的 0：进入子目录后翻页、上传完刷新，
 * 都必须仍然查当前目录，否则用户会"上传完突然回到根目录"。
 */
async function fetchList() {
  loading.value = true
  try {
    const res = await getFileList({
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      parentId: currentParentId.value,
    })
    files.value = res.records
    total.value = res.total
  } catch (e) {
    // 目录在网络请求期间被别处删掉了：后端对"父目录不存在/已删除"统一返回 404/40401。
    // 此时继续停在旧目录只会一直报错，自动退回根目录并提示一次，用户不会卡死。
    if (e.bizCode === 40401 && !isRoot.value) {
      navStack.value = [{ id: 0, name: '全部文件' }]
      pageNum.value = 1
      ElMessage.warning('目录已不存在，已返回全部文件')
      await fetchList()
    } else {
      // silentError 让拦截器不弹提示，这里统一弹后端原文（网络异常时兜底）
      ElMessage.error(e.bizMessage || '文件列表加载失败')
    }
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

/** 进入某个文件夹：压栈 + 回到第 1 页 + 重新拉列表 */
function openFolder(row) {
  if (!row.isFolder) return
  navStack.value.push({ id: row.id, name: row.filename })
  pageNum.value = 1
  fetchList()
}

/** 双击文件夹进入；文件双击不做任何事，避免误触下载 */
function handleRowDblclick(row) {
  if (row.isFolder) openFolder(row)
}

/** 面包屑跳转：点击第 index 项，把栈截断到这一项 */
function jumpTo(index) {
  if (index === navStack.value.length - 1) return
  navStack.value = navStack.value.slice(0, index + 1)
  pageNum.value = 1
  fetchList()
}

/** 返回上级 */
function goUp() {
  if (isRoot.value) return
  navStack.value.pop()
  pageNum.value = 1
  fetchList()
}

// ---------------------------------------------------------------------------
// 新建文件夹
// ---------------------------------------------------------------------------
const folderDialogVisible = ref(false)
const folderName = ref('')
const creatingFolder = ref(false)

function openFolderDialog() {
  folderName.value = ''
  folderDialogVisible.value = true
}

async function submitFolder() {
  const name = folderName.value.trim()
  // 前端只挡"空名字"这一种；重名(40901)、含 / 或 \、整名 . / ..、超长(40000)
  // 全部以后端判定为准，避免两边规则漂移
  if (!name) {
    ElMessage.warning('文件夹名不能为空')
    return
  }

  creatingFolder.value = true
  try {
    await createFolder(currentParentId.value, name)
    ElMessage.success(`文件夹「${name}」创建成功`)
    folderDialogVisible.value = false
    pageNum.value = 1
    await fetchList()
  } catch (e) {
    // 失败提示由拦截器统一弹后端原文（如"同名文件或文件夹已存在"）
  } finally {
    creatingFolder.value = false
  }
}

// ---------------------------------------------------------------------------
// 上传
// ---------------------------------------------------------------------------

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
    // 传到当前所在目录，而不是永远传根目录
    const res = await uploadFile(file, currentParentId.value)
    ElMessage.success(
      res.reused
        ? `秒传成功：${res.filename}（服务端已有该文件，未重复占用磁盘空间）`
        : `上传成功：${res.filename}（${formatSize(res.fileSize)}）`,
    )
    // 回到第 1 页再刷新：列表按上传时间倒序，刚传的文件在第 1 页最上面。
    // 如果用户停在第 3 页，传完看不到自己刚上传的文件，会以为上传失败了。
    pageNum.value = 1
    await fetchList()
  } catch (e) {
    // 失败提示已经由 axios 响应拦截器统一弹过了（如 40901 上传竞态），这里不再抛
  } finally {
    uploading.value = false
  }
}

// ---------------------------------------------------------------------------
// 删除 / 下载
// ---------------------------------------------------------------------------

/** 软删除（移入回收站）。文件夹必须为空，非空由后端 40902 拦截并提示 */
async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(
      row.isFolder
        ? `确定删除文件夹「${row.filename}」吗？文件夹必须为空才能删除。`
        : `确定删除「${row.filename}」吗？可以在回收站中恢复。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '移入回收站', cancelButtonText: '取消' },
    )
  } catch (e) {
    // 用户点了取消
    return
  }

  try {
    await deleteFile(row.id)
    ElMessage.success(`「${row.filename}」已移入回收站`)
    pageNum.value = 1
    await fetchList()
  } catch (e) {
    // 40902（文件夹非空）等错误由拦截器弹后端原文，页面保持原样
  }
}

function handleLogout() {
  userStore.logout()
  router.push('/login')
}

/** 正在下载的那一行（存 user_file id），用来只给这一行的按钮转圈 */
const downloadingId = ref(null)

/**
 * 把 Blob 保存成本地文件。
 *
 * 流程：Blob → 临时 ObjectURL → 造一个隐藏的 <a download> 点一下 → 释放 ObjectURL。
 * 注意最后一定要 revokeObjectURL：它创建的 URL 会一直持有这块内存，
 * 不释放的话每次下载都漏一点，直到页面关闭。
 */
function saveBlob(blob, filename) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}

async function handleDownload(row) {
  downloadingId.value = row.id
  try {
    const blob = await downloadFile(row.id)

    // 兜底：正常情况下后端出错会返回 HTTP 4xx + JSON，走下面的 catch 分支，
    // 错误 JSON 根本不会到这里。但如果哪天后端把错误又改回 "HTTP 200 + Result JSON"，
    // 这个 Blob 里装的就是报错信息，直接保存会得到一个内容是 JSON 的怪文件。
    // 所以保存前按 code/message 判定一次。
    if (blob.type && blob.type.includes('json')) {
      const parsed = JSON.parse(await blob.text())
      ElMessage.error(parsed.message || '下载失败')
      return
    }

    saveBlob(blob, row.filename)
    ElMessage.success(`已开始下载：${row.filename}`)
  } catch (e) {
    // 失败提示已经由拦截器弹过（它会从 Blob 里解析出 40003/40903 等业务码的 message）
  } finally {
    downloadingId.value = null
  }
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
          <div>
            <el-button text type="primary" @click="router.push('/recycle')">回收站</el-button>
            <el-button text type="primary" @click="handleLogout">退出登录</el-button>
          </div>
        </div>
      </template>

      <el-descriptions :column="1" border>
        <el-descriptions-item label="用户 ID">{{ userStore.userInfo?.id ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="用户名">{{ userStore.userInfo?.username ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="邮箱">{{ userStore.userInfo?.email ?? '未填写' }}</el-descriptions-item>
        <el-descriptions-item label="容量上限">{{ toGB(userStore.userInfo?.storageLimit) }}</el-descriptions-item>
      </el-descriptions>

      <!-- 面包屑：数组每一项都可点，点击即把导航栈截断到这一层 -->
      <div class="nav-row">
        <el-button :disabled="isRoot" @click="goUp">返回上级</el-button>
        <el-breadcrumb separator="/">
          <el-breadcrumb-item v-for="(item, index) in navStack" :key="item.id">
            <!-- underline 用字符串值：Element Plus 2.10+ 已弃用布尔写法，会刷控制台警告 -->
            <el-link type="primary" underline="never" @click="jumpTo(index)">
              {{ item.name }}
            </el-link>
          </el-breadcrumb-item>
        </el-breadcrumb>
      </div>

      <div class="upload-row">
        <el-upload :show-file-list="false" :http-request="handleUpload" :disabled="uploading">
          <el-button type="primary" :loading="uploading">
            {{ uploading ? '上传中...' : '上传到当前目录' }}
          </el-button>
        </el-upload>
        <el-button @click="openFolderDialog">新建文件夹</el-button>
        <!-- 限制值来自后端配置，改 application.yml 这里会跟着变 -->
        <span v-if="maxFileSize" class="hint">单个文件最大 {{ formatSize(maxFileSize) }}</span>
      </div>

      <!-- 双击文件夹进入；文件双击不响应，避免误触下载 -->
      <el-table
        v-loading="loading"
        :data="files"
        class="table"
        :empty-text="emptyText"
        @row-dblclick="handleRowDblclick"
      >
        <el-table-column label="文件名" min-width="260" show-overflow-tooltip>
          <template #default="{ row }">
            <el-tag v-if="row.isFolder" size="small" type="warning" effect="plain" class="folder-tag">
              文件夹
            </el-tag>
            <span :class="{ 'folder-name': row.isFolder }">{{ row.filename }}</span>
          </template>
        </el-table-column>
        <el-table-column label="大小" width="110" align="right">
          <template #default="{ row }">{{ row.isFolder ? '-' : formatSize(row.fileSize) }}</template>
        </el-table-column>
        <el-table-column label="上传时间" width="180">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" align="center">
          <template #default="{ row }">
            <el-button v-if="row.isFolder" text type="primary" @click="openFolder(row)">打开</el-button>
            <el-button
              v-else
              text
              type="primary"
              :loading="downloadingId === row.id"
              @click="handleDownload(row)"
            >
              下载
            </el-button>
            <el-button text type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
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

    <el-dialog v-model="folderDialogVisible" title="新建文件夹" width="420px">
      <el-input
        v-model="folderName"
        maxlength="200"
        show-word-limit
        placeholder="请输入文件夹名"
        @keyup.enter="submitFolder"
      />
      <template #footer>
        <el-button @click="folderDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="creatingFolder" @click="submitFolder">创建</el-button>
      </template>
    </el-dialog>
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

.nav-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 16px;
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

.folder-tag {
  margin-right: 8px;
}

/* 文件夹名用主题色加粗，和普通文件拉开视觉差；图标依赖不引入，用标签+颜色实现 */
.folder-name {
  color: var(--el-color-primary);
  font-weight: 600;
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
