<script setup>
import { ref, computed, onMounted } from 'vue'
import {
  RefreshCw,
  Activity,
  Coins,
  CircleDollarSign,
  Users,
  BarChart3,
  Gauge,
  ArrowUpRight,
  Orbit,
} from 'lucide-vue-next'
import { logApi } from '../api'
import PageIntro from '../components/PageIntro.vue'
import EmptyState from '../components/EmptyState.vue'

const loading = ref(false)
const rows = ref([])
const MAX_ROWS = 100
const truncated = ref(false)
/** 请求序号:连点刷新时后发先至的旧响应会被丢弃 */
let loadSeq = 0
// 指标卡总量基于完整数据计算,表格仅展示截断后的前 MAX_ROWS 行
const totals = ref({ requests: 0, tokens: 0, cost: 0, tenants: 0 })

async function load() {
  const seq = ++loadSeq
  loading.value = true
  try {
    const data = await logApi.stats()
    if (seq !== loadSeq) return // 过期响应:已有更新的请求在途/完成,丢弃
    totals.value = {
      requests: data.reduce((s, x) => s + (x.requests || 0), 0),
      tokens: data.reduce((s, x) => s + (x.tokens || 0), 0),
      cost: data.reduce((s, x) => s + (x.cost || 0), 0),
      tenants: data.length,
    }
    truncated.value = data.length > MAX_ROWS
    // 按 Token 用量降序,截断时保证展示的是用量最高的租户
    rows.value = [...data].sort((a, b) => (b.tokens || 0) - (a.tokens || 0)).slice(0, MAX_ROWS)
  } catch (e) {
    /* 错误已由拦截器提示;吞掉 rejection,onMounted/刷新按钮可安全地 fire-and-forget */
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

const maxTokens = computed(() => Math.max(1, ...rows.value.map((x) => x.tokens || 0)))
const topTenants = computed(() => rows.value.slice(0, 4))
const averageTokens = computed(() => {
  if (!totals.value.requests) return 0
  return Math.round(totals.value.tokens / totals.value.requests)
})

const cards = computed(() => [
  {
    label: '总请求数',
    en: 'REQUESTS',
    value: fmtInt(totals.value.requests),
    icon: Activity,
    tint: '#7c5cff',
  },
  { label: '总 Token', en: 'TOKENS', value: fmtInt(totals.value.tokens), icon: Coins, tint: '#53d9ff' },
  {
    label: '上游总成本 (USD)',
    en: 'COST',
    value: '$' + totals.value.cost.toFixed(6),
    icon: CircleDollarSign,
    tint: '#c8ff3d',
  },
  { label: '租户数', en: 'TENANTS', value: fmtInt(totals.value.tenants), icon: Users, tint: '#f472b6' },
])

function fmtInt(n) {
  return (n || 0).toLocaleString('en-US')
}

function waveHeight(n) {
  return `${18 + ((n * 17) % 62)}%`
}

function meterScale(tokens) {
  return `scaleX(${(tokens || 0) / maxTokens.value})`
}

function rankWidth(tokens) {
  return `${Math.max(6, ((tokens || 0) / maxTokens.value) * 100)}%`
}

onMounted(load)
</script>

<template>
  <div class="page dashboard-page">
    <PageIntro
      index="00"
      eyebrow="Realtime telemetry / tenant graph"
      title="运行总览"
      subtitle="按租户聚合的用量与上游成本。缓存命中不计成本，所有读数来自当前网关遥测。"
    >
      <template #actions>
        <el-button :loading="loading" @click="load">
          <el-icon><RefreshCw :stroke-width="1.8" /></el-icon>&nbsp;刷新数据
        </el-button>
      </template>
    </PageIntro>

    <div class="dashboard-hero-grid">
      <section class="surface dashboard-hero rise" style="--i: 1">
        <div class="hero-topline mono">
          <span><Activity :size="13" :stroke-width="1.8" /> TRAFFIC / ALL TENANTS</span>
          <span class="hero-live"><i></i> LIVE</span>
        </div>
        <div class="hero-number tabular-nums">{{ cards[0].value }}</div>
        <div class="hero-label">累计请求数</div>
        <div class="hero-baseline">
          <span>平均请求 / {{ fmtInt(averageTokens) }} tokens</span>
          <span class="mono">SOURCE: /admin/logs/stats</span>
        </div>
        <div class="hero-wave" aria-hidden="true">
          <span v-for="n in 18" :key="n" :style="{ '--h': waveHeight(n) }"></span>
        </div>
      </section>

      <section class="surface signal-panel rise" style="--i: 2">
        <div class="panel-kicker mono"><Gauge :size="13" :stroke-width="1.8" /> SIGNAL HEALTH</div>
        <div class="signal-ring"><span>{{ totals.tenants }}</span><small>TENANTS</small></div>
        <div class="signal-copy">当前数据窗口覆盖的租户节点</div>
        <div class="signal-line"><span></span><b></b></div>
        <div class="signal-meta mono"><span>LATENCY</span><strong>STREAM READY</strong></div>
      </section>
    </div>

    <div class="metric-strip rise" style="--i: 3" v-loading="loading">
      <div
        v-for="c in cards"
        :key="c.label"
        class="metric-cell"
        :style="{ '--metric-color': c.tint }"
      >
        <div class="metric-label">
          <component :is="c.icon" :size="14" :stroke-width="1.7" />{{ c.en }}
        </div>
        <div class="metric-value tabular-nums">{{ c.value }}</div>
        <div class="metric-note">{{ c.label }}</div>
      </div>
    </div>

    <div class="dashboard-lower-grid">
      <section class="surface data-panel rise" style="--i: 4">
        <div class="panel-heading">
          <div class="panel-heading-icon"><BarChart3 :size="17" :stroke-width="1.7" /></div>
          <div class="panel-heading-copy">
            <div class="panel-heading-title">租户用量明细</div>
            <div class="panel-heading-note">TOKEN DISTRIBUTION / DESCENDING</div>
          </div>
          <div class="panel-heading-rule"></div>
          <el-tag v-if="truncated" size="small" type="warning">TOP {{ rows.length }}</el-tag>
        </div>
        <el-table :data="rows" v-loading="loading" style="width: 100%">
          <template #empty>
            <EmptyState
              :icon="Orbit"
              title="暂无租户遥测"
              hint="调用 /v1/chat/completions 产生第一条网关记录"
            />
          </template>
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
                <div class="meter"><div class="meter-fill" :style="{ transform: meterScale(row.tokens) }"></div></div>
                <span class="tabular-nums bar-val">{{ fmtInt(row.tokens) }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="上游成本 (USD)" width="160" align="right">
            <template #default="{ row }"><span class="tabular-nums">${{ (row.cost || 0).toFixed(6) }}</span></template>
          </el-table-column>
        </el-table>
        <div class="data-footnote mono">{{ truncated ? 'DISPLAYING HIGHEST TOKEN TENANTS' : 'FULL DATASET IN VIEW' }}</div>
      </section>

      <aside class="surface data-panel ranking-panel rise" style="--i: 5">
        <div class="panel-heading">
          <div class="panel-heading-icon"><ArrowUpRight :size="17" :stroke-width="1.7" /></div>
          <div class="panel-heading-copy">
            <div class="panel-heading-title">流量重心</div>
            <div class="panel-heading-note">TOP TENANT SIGNALS</div>
          </div>
        </div>
        <div v-if="topTenants.length" class="ranking-list">
          <div v-for="(row, i) in topTenants" :key="row.tenant" class="ranking-row">
            <span class="ranking-index mono">0{{ i + 1 }}</span>
            <div class="ranking-copy">
              <div class="ranking-name">{{ row.tenant }}</div>
              <div class="ranking-meter"><span :style="{ width: rankWidth(row.tokens) }"></span></div>
            </div>
            <span class="ranking-value mono">{{ fmtInt(row.tokens) }}</span>
          </div>
        </div>
        <EmptyState v-else :icon="Orbit" title="等待流量" hint="租户数据出现后会在此形成排序信号" />
      </aside>
    </div>
  </div>
</template>

<style scoped>
.dashboard-hero-grid,
.dashboard-lower-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.55fr) minmax(260px, 0.75fr);
  gap: 14px;
}

.dashboard-hero-grid {
  margin-bottom: 14px;
}

.dashboard-hero,
.signal-panel {
  min-height: 238px;
}

.dashboard-hero {
  position: relative;
  overflow: hidden;
  padding: 22px 24px;
  background:
    radial-gradient(70% 110% at 96% 8%, rgba(124, 92, 255, 0.24), transparent 62%),
    linear-gradient(145deg, rgba(255, 255, 255, 0.05), rgba(255, 255, 255, 0.012));
}

.dashboard-hero::after {
  content: '00 / OVERVIEW';
  position: absolute;
  right: 22px;
  bottom: 20px;
  color: rgba(255, 255, 255, 0.13);
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.16em;
}

.hero-topline,
.hero-baseline,
.signal-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.hero-topline {
  color: var(--app-text-faint);
  font-size: 9px;
  letter-spacing: 0.13em;
}

.hero-topline span:first-child {
  display: inline-flex;
  align-items: center;
  gap: 7px;
}

.hero-live {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--accent-lime);
}

.hero-live i {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentColor;
  box-shadow: 0 0 10px currentColor;
}

.hero-number {
  position: relative;
  z-index: 1;
  margin-top: 27px;
  color: var(--app-text);
  font-family: var(--font-display);
  font-size: clamp(54px, 8vw, 104px);
  font-weight: 600;
  line-height: 0.9;
  letter-spacing: -0.09em;
}

.hero-label {
  position: relative;
  z-index: 1;
  margin-top: 10px;
  color: var(--accent-cyan);
  font-size: 13px;
}

.hero-baseline {
  position: relative;
  z-index: 1;
  max-width: 640px;
  margin-top: 26px;
  color: var(--app-text-secondary);
  font-size: 11px;
}

.hero-baseline .mono {
  color: var(--app-text-faint);
  font-size: 9px;
}

.hero-wave {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 38%;
  display: flex;
  align-items: end;
  gap: 5px;
  height: 82px;
  padding: 0 24px 22px 0;
  opacity: 0.5;
  mask-image: linear-gradient(90deg, transparent, #000 25%, #000);
}

.hero-wave span {
  flex: 1;
  height: var(--h);
  min-width: 2px;
  background: linear-gradient(180deg, var(--accent-cyan), var(--accent-violet));
  transform-origin: bottom;
  animation: wave-breathe 2.6s ease-in-out infinite alternate;
  animation-delay: calc(var(--i, 1) * 40ms);
}

.signal-panel {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 24px;
  text-align: center;
  background:
    radial-gradient(circle at 50% 38%, rgba(200, 255, 61, 0.09), transparent 32%),
    rgba(11, 13, 14, 0.78);
}

.panel-kicker {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  align-self: stretch;
  color: var(--app-text-faint);
  font-size: 9px;
  letter-spacing: 0.16em;
  text-align: left;
}

.signal-ring {
  width: 120px;
  height: 120px;
  display: grid;
  place-content: center;
  margin: 12px 0 10px;
  border: 1px solid rgba(200, 255, 61, 0.36);
  border-radius: 50%;
  box-shadow: 0 0 0 10px rgba(200, 255, 61, 0.025), 0 0 34px rgba(124, 92, 255, 0.18);
}

.signal-ring span {
  color: var(--accent-lime);
  font-family: var(--font-display);
  font-size: 35px;
  line-height: 1;
}

.signal-ring small {
  margin-top: 6px;
  color: var(--app-text-faint);
  font-family: var(--font-mono);
  font-size: 8px;
  letter-spacing: 0.17em;
}

.signal-copy {
  color: var(--app-text-secondary);
  font-size: 11px;
}

.signal-line {
  position: relative;
  width: 100%;
  height: 1px;
  margin: 20px 0 12px;
  background: rgba(255, 255, 255, 0.08);
}

.signal-line span {
  display: block;
  width: 68%;
  height: 100%;
  background: linear-gradient(90deg, var(--accent-lime), var(--accent-cyan));
  box-shadow: 0 0 12px rgba(200, 255, 61, 0.55);
}

.signal-line b {
  position: absolute;
  top: -2px;
  left: 68%;
  width: 5px;
  height: 5px;
  background: var(--accent-lime);
  transform: rotate(45deg);
}

.signal-meta {
  width: 100%;
  color: var(--app-text-faint);
  font-size: 8px;
  letter-spacing: 0.12em;
}

.signal-meta strong {
  color: var(--accent-cyan);
  font-weight: 500;
}

.ranking-panel {
  min-width: 0;
}

.ranking-list {
  display: grid;
  gap: 18px;
  padding: 5px 0 3px;
}

.ranking-row {
  display: grid;
  grid-template-columns: 28px minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
}

.ranking-index {
  color: var(--app-text-faint);
  font-size: 9px;
}

.ranking-name {
  overflow: hidden;
  color: var(--app-text);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ranking-meter {
  height: 4px;
  margin-top: 7px;
  overflow: hidden;
  background: rgba(255, 255, 255, 0.08);
}

.ranking-meter span {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, var(--accent-violet), var(--accent-cyan));
  box-shadow: 0 0 12px rgba(83, 217, 255, 0.45);
  transition: width 0.7s var(--out-expo);
}

.ranking-value {
  color: var(--app-text-secondary);
  font-size: 10px;
}

.bar-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.meter {
  flex: 1;
  height: 6px;
  overflow: hidden;
  background: rgba(255, 255, 255, 0.07);
}

.meter-fill {
  height: 100%;
  transform-origin: left center;
  background: linear-gradient(90deg, var(--accent-violet), var(--accent-cyan));
  box-shadow: 0 0 12px rgba(124, 92, 255, 0.5);
  transition: transform 0.7s var(--out-expo);
}

.bar-val {
  min-width: 80px;
  color: var(--app-text-secondary);
  font-size: 12px;
  text-align: right;
}

@keyframes wave-breathe {
  from { transform: scaleY(0.65); opacity: 0.55; }
  to { transform: scaleY(1); opacity: 1; }
}

@media (prefers-reduced-motion: reduce) {
  .hero-wave span {
    animation: none;
  }
}

@media (max-width: 900px) {
  .dashboard-hero-grid,
  .dashboard-lower-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 560px) {
  .hero-baseline {
    align-items: flex-start;
    flex-direction: column;
    gap: 5px;
  }

  .hero-wave {
    left: 0;
    opacity: 0.25;
  }
}
</style>
