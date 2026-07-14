<script setup>
import { ref, reactive, onMounted } from 'vue'
import { Search, RefreshLeft } from '@element-plus/icons-vue'
import { auditApi } from '../api'

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const query = reactive({ username: '', action: '', page: 1, size: 20 })
const timeRange = ref([])

const actionMeta = {
  LOGIN_OK: { type: 'success', label: '登录成功' },
  LOGIN_FAIL: { type: 'danger', label: '登录失败' },
  LOGIN_LOCKED: { type: 'warning', label: '登录锁定' },
  CREATE: { type: 'primary', label: '新增' },
  UPDATE: { type: 'warning', label: '修改' },
  DELETE: { type: 'danger', label: '删除' },
  RELOAD: { type: 'info', label: '刷新配置' },
}

async function load() {
  loading.value = true
  try {
    const params = { ...query }
    if (timeRange.value && timeRange.value.length === 2) {
      params.from = timeRange.value[0]
      params.to = timeRange.value[1]
    }
    const data = await auditApi.list(params)
    rows.value = data.records || []
    total.value = data.total || 0
  } finally {
    loading.value = false
  }
}
function search() {
  query.page = 1
  load()
}
function reset() {
  query.username = ''
  query.action = ''
  timeRange.value = []
  query.page = 1
  load()
}
function onPage(p) {
  query.page = p
  load()
}
function onSize(s) {
  query.size = s
  query.page = 1
  load()
}
function fmtTime(t) {
  return t ? String(t).replace('T', ' ').slice(0, 19) : '—'
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">操作审计</h2>
      </div>
    </div>

    <div class="surface" style="padding: 16px">
      <div class="toolbar">
        <el-input
          v-model="query.username"
          placeholder="用户名"
          clearable
          style="width: 150px"
          @keyup.enter="search"
        />
        <el-select v-model="query.action" placeholder="动作" clearable style="width: 150px">
          <el-option v-for="(m, k) in actionMeta" :key="k" :label="m.label" :value="k" />
        </el-select>
        <el-date-picker
          v-model="timeRange"
          type="datetimerange"
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          value-format="YYYY-MM-DDTHH:mm:ss"
          style="width: 340px"
        />
        <el-button type="primary" @click="search"
          ><el-icon><Search /></el-icon>&nbsp;查询</el-button
        >
        <el-button @click="reset"
          ><el-icon><RefreshLeft /></el-icon>&nbsp;重置</el-button
        >
      </div>

      <el-table :data="rows" v-loading="loading" style="width: 100%" empty-text="暂无审计记录">
        <el-table-column label="时间" width="170">
          <template #default="{ row }"
            ><span class="tabular-nums">{{ fmtTime(row.createdAt) }}</span></template
          >
        </el-table-column>
        <el-table-column prop="username" label="用户" width="110" />
        <el-table-column label="动作" width="110">
          <template #default="{ row }">
            <el-tag
              :type="(actionMeta[row.action] || {}).type || 'info'"
              effect="light"
              size="small"
            >
              {{ (actionMeta[row.action] || {}).label || row.action }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="resource" label="资源" min-width="160" show-overflow-tooltip />
        <el-table-column prop="detail" label="详情" min-width="240" show-overflow-tooltip>
          <template #default="{ row }"
            ><span class="mono">{{ row.detail || '—' }}</span></template
          >
        </el-table-column>
        <el-table-column prop="clientIp" label="来源 IP" width="130" />
        <el-table-column prop="status" label="状态码" width="90" align="right" />
      </el-table>

      <div class="pager">
        <el-pagination
          background
          layout="total, sizes, prev, pager, next"
          :total="total"
          :current-page="query.page"
          :page-size="query.size"
          :page-sizes="[10, 20, 50, 100]"
          @current-change="onPage"
          @size-change="onSize"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
