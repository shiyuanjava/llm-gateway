<script setup>
import { ref, computed, onMounted } from 'vue'
import { Refresh, DataLine, Coin, Money, User } from '@element-plus/icons-vue'
import { logApi } from '../api'

const loading = ref(false)
const rows = ref([])
const MAX_ROWS = 100
const truncated = ref(false)
// 指标卡总量基于完整数据计算,表格仅展示截断后的前 MAX_ROWS 行
const totals = ref({ requests: 0, tokens: 0, cost: 0, tenants: 0 })

async function load() {
  loading.value = true
  try {
    const data = await logApi.stats()
    totals.value = {
      requests: data.reduce((s, x) => s + (x.requests || 0), 0),
      tokens: data.reduce((s, x) => s + (x.tokens || 0), 0),
      cost: data.reduce((s, x) => s + (x.cost || 0), 0),
      tenants: data.length
    }
    truncated.value = data.length > MAX_ROWS
    // 按 Token 用量降序,截断时保证展示的是用量最高的租户
    rows.value = [...data].sort((a, b) => (b.tokens || 0) - (a.tokens || 0)).slice(0, MAX_ROWS)
  } finally {
    loading.value = false
  }
}

const maxTokens = computed(() => Math.max(1, ...rows.value.map((x) => x.tokens || 0)))

const cards = computed(() => [
  { label: '总请求数', value: fmtInt(totals.value.requests), icon: DataLine, tint: '#4f46e5' },
  { label: '总 Token', value: fmtInt(totals.value.tokens), icon: Coin, tint: '#0ea5e9' },
  { label: '上游总成本 (USD)', value: '$' + totals.value.cost.toFixed(6), icon: Money, tint: '#16a34a' },
  { label: '租户数', value: fmtInt(totals.value.tenants), icon: User, tint: '#d97706' }
])

function fmtInt(n) {
  return (n || 0).toLocaleString('en-US')
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">概览</h2>
        <div class="page-subtitle">按租户聚合的用量与上游成本（缓存命中不计成本;数据源:request_log 表）</div>
      </div>
      <el-button :loading="loading" @click="load">
        <el-icon><Refresh /></el-icon>&nbsp;刷新
      </el-button>
    </div>

    <!-- 指标卡 -->
    <div class="stat-grid" v-loading="loading">
      <div v-for="c in cards" :key="c.label" class="surface stat-card">
        <div class="stat-icon" :style="{ background: c.tint + '1a', color: c.tint }">
          <el-icon :size="22"><component :is="c.icon" /></el-icon>
        </div>
        <div>
          <div class="stat-label">{{ c.label }}</div>
          <div class="stat-value tabular-nums">{{ c.value }}</div>
        </div>
      </div>
    </div>

    <!-- 租户明细 -->
    <div class="surface table-wrap">
      <div class="table-head">
        租户用量明细
        <el-tag v-if="truncated" size="small" type="warning" style="margin-left:8px">仅显示 Token 用量前 {{ rows.length }} 个租户</el-tag>
      </div>
      <el-table :data="rows" v-loading="loading" style="width: 100%"
                empty-text="暂无数据,先调用 /v1/chat/completions 产生记录">
        <el-table-column prop="tenant" label="租户" min-width="140" />
        <el-table-column prop="requests" label="请求数" width="120" align="right">
          <template #default="{ row }"><span class="tabular-nums">{{ fmtInt(row.requests) }}</span></template>
        </el-table-column>
        <el-table-column prop="cacheHits" label="缓存命中" width="110" align="right">
          <template #default="{ row }"><span class="tabular-nums">{{ fmtInt(row.cacheHits) }}</span></template>
        </el-table-column>
        <el-table-column label="Token 占比" min-width="240">
          <template #default="{ row }">
            <div class="bar-row">
              <el-progress
                :percentage="Math.round((row.tokens / maxTokens) * 100)"
                :stroke-width="10" :show-text="false" style="flex:1" />
              <span class="tabular-nums bar-val">{{ fmtInt(row.tokens) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="上游成本 (USD)" width="160" align="right">
          <template #default="{ row }">
            <span class="tabular-nums">${{ (row.cost || 0).toFixed(6) }}</span>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<style scoped>
.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 24px;
}
@media (max-width: 1100px) { .stat-grid { grid-template-columns: repeat(2, 1fr); } }
.stat-card {
  padding: 18px 20px;
  display: flex;
  align-items: center;
  gap: 16px;
}
.stat-icon {
  width: 48px; height: 48px;
  border-radius: 12px;
  display: grid; place-items: center;
}
.stat-label { font-size: 13px; color: var(--app-text-secondary); }
.stat-value { font-size: 24px; font-weight: 700; letter-spacing: -.02em; margin-top: 2px; }

.table-wrap { padding: 8px 8px 4px; }
.table-head { font-weight: 600; padding: 12px 12px 14px; }
.bar-row { display: flex; align-items: center; gap: 12px; }
.bar-val { min-width: 80px; text-align: right; color: var(--app-text-secondary); font-size: 13px; }
</style>
