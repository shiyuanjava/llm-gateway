<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Search, RefreshLeft } from '@element-plus/icons-vue'
import { logApi } from '../api'

const loading = ref(false)
const rows = ref([])
const total = ref(0)
/** 加载失败标记:与「暂无数据」区分,给用户原地重试入口 */
const loadError = ref(false)
/** 请求序号:快速翻页/改条件时后发先至的旧响应会被丢弃,防止列表显示旧数据 */
let loadSeq = 0
const query = reactive({ tenant: '', status: '', model: '', page: 1, size: 20 })
const timeRange = ref([])

const statusMeta = {
  success: { type: 'success', label: '成功' },
  cache_hit: { type: 'primary', label: '缓存命中' },
  error: { type: 'danger', label: '失败' },
  client_aborted: { type: 'warning', label: '客户端断开' },
  guardrail_truncated: { type: 'danger', label: '护栏截断' },
}

async function load() {
  const seq = ++loadSeq
  loading.value = true
  try {
    const params = { ...query }
    if (timeRange.value && timeRange.value.length === 2) {
      params.from = timeRange.value[0]
      params.to = timeRange.value[1]
    }
    const data = await logApi.list(params)
    if (seq !== loadSeq) return // 过期响应:已有更新的请求在途/完成,丢弃
    rows.value = data.records || []
    total.value = data.total || 0
    loadError.value = false
  } catch (e) {
    /* 错误已由拦截器提示;标记错误态给出重试入口 */
    if (seq === loadSeq) loadError.value = true
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}
function search() {
  if (query.tenant.length > 100 || query.model.length > 100) {
    ElMessage.warning('搜索条件过长(≤100 字符)')
    return
  }
  query.page = 1
  load()
}
function reset() {
  query.tenant = ''
  query.status = ''
  query.model = ''
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
  if (!t) return '—'
  return String(t).replace('T', ' ').slice(0, 19)
}
function fmtInt(n) {
  return (n || 0).toLocaleString('en-US')
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">请求日志</h2>
      </div>
    </div>

    <div class="surface" style="padding: 16px">
      <div class="toolbar">
        <el-input
          v-model="query.tenant"
          placeholder="租户"
          clearable
          style="width: 150px"
          @keyup.enter="search"
        />
        <el-select v-model="query.status" placeholder="状态" clearable style="width: 140px">
          <el-option label="成功" value="success" />
          <el-option label="缓存命中" value="cache_hit" />
          <el-option label="失败" value="error" />
          <el-option label="客户端断开" value="client_aborted" />
          <el-option label="护栏截断" value="guardrail_truncated" />
        </el-select>
        <el-input
          v-model="query.model"
          placeholder="模型(模糊)"
          clearable
          style="width: 180px"
          @keyup.enter="search"
        />
        <el-date-picker
          v-model="timeRange"
          type="datetimerange"
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          value-format="YYYY-MM-DDTHH:mm:ss"
          style="width: 340px"
        />
        <el-button type="primary" :loading="loading" @click="search"
          ><el-icon><Search /></el-icon>&nbsp;查询</el-button
        >
        <el-button @click="reset"
          ><el-icon><RefreshLeft /></el-icon>&nbsp;重置</el-button
        >
      </div>

      <el-table :data="rows" v-loading="loading" style="width: 100%">
        <template #empty>
          <el-empty v-if="loadError" description="加载失败" :image-size="60">
            <el-button type="primary" size="small" @click="load">重试</el-button>
          </el-empty>
          <span v-else>暂无日志</span>
        </template>
        <el-table-column label="时间" width="170">
          <template #default="{ row }"
            ><span class="tabular-nums">{{ fmtTime(row.createdAt) }}</span></template
          >
        </el-table-column>
        <el-table-column prop="tenant" label="租户" width="110" />
        <el-table-column label="请求 → 实际模型" min-width="220">
          <template #default="{ row }">
            <span class="mono">{{ row.requestedModel }}</span>
            <span class="muted"> → </span>
            <span class="mono">{{ row.servedModel || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="Token(入/出/合)" width="170" align="right">
          <template #default="{ row }">
            <el-tooltip
              v-if="(row.cacheReadTokens || 0) + (row.cacheCreationTokens || 0) > 0"
              :content="`缓存读 ${row.cacheReadTokens || 0} / 缓存写 ${row.cacheCreationTokens || 0}`"
              placement="top"
            >
              <span class="tabular-nums has-cache"
                >{{ row.promptTokens }}/{{ row.completionTokens }}/<b>{{
                  row.totalTokens
                }}</b></span
              >
            </el-tooltip>
            <span v-else class="tabular-nums"
              >{{ row.promptTokens }}/{{ row.completionTokens }}/<b>{{ row.totalTokens }}</b></span
            >
          </template>
        </el-table-column>
        <el-table-column label="成本(USD)" width="130" align="right">
          <template #default="{ row }"
            ><span class="tabular-nums">${{ Number(row.costUsd || 0).toFixed(6) }}</span></template
          >
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag
              :type="(statusMeta[row.status] || {}).type || 'info'"
              effect="light"
              size="small"
            >
              {{ (statusMeta[row.status] || {}).label || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="延迟" width="100" align="right">
          <template #default="{ row }"
            ><span class="tabular-nums">{{ fmtInt(row.latencyMs) }} ms</span></template
          >
        </el-table-column>
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
  font-size: 13px;
}
.muted {
  color: var(--app-text-secondary);
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
.has-cache {
  text-decoration: underline dotted;
  cursor: help;
}
</style>
