<script setup>
import { h, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getRecycleList, purgeAll, purgeFile, restoreFile } from '@/api/file'

const router = useRouter()

const items = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const loading = ref(false)

/** 去掉小数末尾多余的 0，100.00 显示成 100 */
function trimZero(num) {
  return parseFloat(num.toFixed(2))
}

/** 字节转带单位的大小；文件夹没有物理文件，显示 '-' */
function formatSize(row) {
  if (row.isFolder) return '-'
  const n = Number(row.fileSize) || 0
  if (n <= 0) return '0 B'
  if (n < 1024) return n + ' B'
  if (n < 1024 * 1024) return trimZero(n / 1024) + ' KB'
  if (n < 1024 * 1024 * 1024) return trimZero(n / 1024 / 1024) + ' MB'
  return trimZero(n / 1024 / 1024 / 1024) + ' GB'
}

/** 后端时间是 ISO-8601，把 T 换成空格更符合中文阅读习惯 */
function formatTime(time) {
  return time ? time.replace('T', ' ') : '-'
}

async function fetchList() {
  loading.value = true
  try {
    const res = await getRecycleList({ pageNum: pageNum.value, pageSize: pageSize.value })
    items.value = res.records
    total.value = res.total
  } catch (e) {
    // 回收站接口用了 silentError，这里统一弹后端原文
    ElMessage.error(e.bizMessage || '回收站加载失败')
  } finally {
    loading.value = false
  }
}

function handleSizeChange() {
  pageNum.value = 1
  fetchList()
}

// ---------------------------------------------------------------------------
// 恢复：失败原因是 40901（同目录已有同名项），由拦截器原样弹出
// ---------------------------------------------------------------------------
async function handleRestore(row) {
  try {
    await ElMessageBox.confirm(`确定恢复「${row.filename}」吗？`, '恢复确认', {
      type: 'info',
      confirmButtonText: '恢复',
      cancelButtonText: '取消',
    })
  } catch (e) {
    return
  }

  try {
    await restoreFile(row.id)
    ElMessage.success(`「${row.filename}」已恢复`)
    // 当前页可能只剩 0 条，回第 1 页最稳
    pageNum.value = 1
    await fetchList()
  } catch (e) {
    // 40901 等同名冲突由拦截器弹后端原文，页面保持原状
  }
}

// ---------------------------------------------------------------------------
// 彻底删除：不可恢复，确认文案要说清楚
// ---------------------------------------------------------------------------
async function handlePurge(row) {
  try {
    await ElMessageBox.confirm(`彻底删除「${row.filename}」后无法恢复，确定继续吗？`, '彻底删除', {
      type: 'warning',
      confirmButtonText: '彻底删除',
      cancelButtonText: '取消',
    })
  } catch (e) {
    return
  }

  try {
    const res = await purgeFile(row.id)
    // shared=true：物理文件还被别的用户/记录引用，本次只删这条记录、不删盘。
    // 这不是失败，但必须如实告诉用户，否则会以为磁盘空间已经释放了。
    ElMessage.success(
      res.shared
        ? `已彻底删除「${row.filename}」；该文件仍被其他记录共享，物理文件已保留`
        : `已彻底删除「${row.filename}」`,
    )
    pageNum.value = 1
    await fetchList()
  } catch (e) {
    // 失败原因（如已被别处恢复）由拦截器弹后端原文
  }
}

// ---------------------------------------------------------------------------
// 清空回收站
// ---------------------------------------------------------------------------
async function handlePurgeAll() {
  if (total.value === 0) {
    ElMessage.info('回收站已经是空的')
    return
  }

  try {
    // 弹窗里写明条数：清空是不可逆操作，用户必须知道这次会影响多少条
    await ElMessageBox.confirm(
      `回收站里共 ${total.value} 条记录，清空后全部无法恢复，确定继续吗？`,
      '清空回收站',
      { type: 'warning', confirmButtonText: '清空', cancelButtonText: '取消' },
    )
  } catch (e) {
    return
  }

  try {
    const res = await purgeAll()

    // PurgeBatchVO 允许部分成功：HTTP 200 不等于所有条目都删掉了。
    // 有失败条目时必须逐条展示 filename + reason，不能一句"清空成功"糊过去。
    if (res.failedCount > 0) {
      const failures = res.items.filter((item) => item.status !== 'deleted')
      ElMessageBox.alert(
        h(
          'div',
          [
            h('p', `本次共处理 ${res.requestedCount} 条：成功 ${res.deletedCount} 条，失败 ${res.failedCount} 条。`),
            h('p', '失败条目：'),
            ...failures.map((item) => h('p', `${item.filename}：${item.reason || '未知原因'}`)),
          ],
        ),
        '清空未全部成功',
        { type: 'warning', confirmButtonText: '知道了' },
      )
    } else {
      ElMessage.success(`已清空回收站，共删除 ${res.deletedCount} 条`)
    }

    pageNum.value = 1
    await fetchList()
  } catch (e) {
    // 整批请求失败（网络/服务端异常）由拦截器弹提示
  }
}

onMounted(fetchList)
</script>

<template>
  <div class="page">
    <el-card class="card">
      <template #header>
        <div class="header">
          <span>回收站</span>
          <div>
            <el-button @click="router.push('/home')">返回我的网盘</el-button>
            <el-button type="danger" :disabled="total === 0" @click="handlePurgeAll">
              清空回收站
            </el-button>
          </div>
        </div>
      </template>

      <el-table
        v-loading="loading"
        :data="items"
        class="table"
        empty-text="回收站是空的"
      >
        <el-table-column label="文件名" min-width="260" show-overflow-tooltip>
          <template #default="{ row }">
            <el-tag v-if="row.isFolder" size="small" type="warning" effect="plain" class="folder-tag">
              文件夹
            </el-tag>
            <span>{{ row.filename }}</span>
          </template>
        </el-table-column>
        <el-table-column label="大小" width="110" align="right">
          <template #default="{ row }">{{ formatSize(row) }}</template>
        </el-table-column>
        <el-table-column label="删除时间" width="180">
          <template #default="{ row }">{{ formatTime(row.deleteTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="170" align="center">
          <template #default="{ row }">
            <el-button text type="primary" @click="handleRestore(row)">恢复</el-button>
            <el-button text type="danger" @click="handlePurge(row)">彻底删除</el-button>
          </template>
        </el-table-column>
      </el-table>

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

.folder-tag {
  margin-right: 8px;
}

.table {
  width: 100%;
}

.pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
