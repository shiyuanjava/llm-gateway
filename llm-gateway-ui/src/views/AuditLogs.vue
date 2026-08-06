<script setup>
import { computed, ref, reactive, onMounted } from 'vue'
import {
  Search,
  RotateCcw,
  ShieldCheck,
  CircleCheck,
  TriangleAlert,
  UsersRound,
  CircleX,
} from 'lucide-vue-next'
import { auditApi } from '../api'
import PageIntro from '../components/PageIntro.vue'
import EmptyState from '../components/EmptyState.vue'

const loading = ref(false)
const rows = ref([])
const total = ref(0)
/** 加载失败标记:与「暂无数据」区分,给用户原地重试入口 */
const loadError = ref(false)
/** 请求序号:快速翻页/改条件时后发先至的旧响应会被丢弃,防止列表显示旧数据 */
let loadSeq = 0
const query = reactive({ username: '', action: '', page: 1, size: 20 })
const timeRange = ref([])
const successCount = computed(() => rows.value.filter((row) => String(row.action || '').endsWith('_OK')).length)
const failureCount = computed(() => rows.value.filter((row) => String(row.action || '').includes('FAIL') || String(row.action || '').includes('LOCKED')).length)
const userCount = computed(() => new Set(rows.value.map((row) => row.username).filter(Boolean)).size)

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
  const seq = ++loadSeq
  loading.value = true
  try {
    const params = { ...query }
    if (timeRange.value && timeRange.value.length === 2) {
      params.from = timeRange.value[0]
      params.to = timeRange.value[1]
    }
    const data = await auditApi.list(params)
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
    <PageIntro
      index="05"
      eyebrow="Trust layer / admin activity"
      title="操作审计"
      subtitle="管理面登录与写操作的完整轨迹，帮助你确认谁在什么时间改变了什么。"
    />

    <div class="metric-strip rise" style="--i: 1">
      <div class="metric-cell" style="--metric-color: var(--accent-cyan)">
        <div class="metric-label"><ShieldCheck :size="14" :stroke-width="1.7" /> EVENTS</div>
        <div class="metric-value tabular-nums">{{ total.toLocaleString('en-US') }}</div>
        <div class="metric-note">审计事件总数</div>
      </div>
      <div class="metric-cell" style="--metric-color: var(--accent-lime)">
        <div class="metric-label"><CircleCheck :size="14" :stroke-width="1.7" /> ACCEPTED</div>
        <div class="metric-value tabular-nums">{{ successCount }}</div>
        <div class="metric-note">当前页成功事件</div>
      </div>
      <div class="metric-cell" style="--metric-color: var(--accent-pink)">
        <div class="metric-label"><TriangleAlert :size="14" :stroke-width="1.7" /> ATTENTION</div>
        <div class="metric-value tabular-nums">{{ failureCount }}</div>
        <div class="metric-note">需要关注事件</div>
      </div>
      <div class="metric-cell" style="--metric-color: var(--accent-violet)">
        <div class="metric-label"><UsersRound :size="14" :stroke-width="1.7" /> ACTORS</div>
        <div class="metric-value tabular-nums">{{ userCount }}</div>
        <div class="metric-note">当前页操作用户</div>
      </div>
    </div>

    <div class="surface data-panel rise" style="--i: 2">
      <div class="panel-heading">
        <div class="panel-heading-icon"><ShieldCheck :size="17" :stroke-width="1.7" /></div>
        <div class="panel-heading-copy">
          <div class="panel-heading-title">操作事件流</div>
          <div class="panel-heading-note">LOGIN / WRITE / CONFIG RELOAD</div>
        </div>
        <div class="panel-heading-rule"></div>
        <span class="live-counter mono">PAGE {{ query.page.toString().padStart(2, '0') }}</span>
      </div>
      <div class="filter-deck">
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
        <el-button type="primary" :loading="loading" @click="search"
          ><el-icon><Search :stroke-width="1.8" /></el-icon>&nbsp;查询</el-button
        >
        <div class="filter-spacer"></div>
        <el-button @click="reset"
          ><el-icon><RotateCcw :stroke-width="1.8" /></el-icon>&nbsp;重置</el-button
        >
      </div>

      <el-table :data="rows" v-loading="loading" style="width: 100%">
        <template #empty>
          <EmptyState
            :icon="loadError ? CircleX : ShieldCheck"
            :title="loadError ? '读取审计失败' : '暂无审计记录'"
            :hint="loadError ? '检查网关连接后重新拉取' : '管理面动作出现后会在这里留下不可变轨迹'"
          >
            <el-button v-if="loadError" type="primary" size="small" @click="load">重试</el-button>
          </EmptyState>
        </template>
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
      <div class="data-footnote mono">AUDIT TRAIL / {{ rows.length }} EVENTS IN CURRENT PAGE</div>
    </div>
  </div>
</template>

<style scoped>
.live-counter {
  color: var(--accent-lime);
  font-size: 9px;
  letter-spacing: 0.12em;
}

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
