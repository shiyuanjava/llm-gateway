<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  CircleAlert,
  Clock3,
  Gauge,
  Globe2,
  ListFilter,
  Plus,
  RefreshCw,
  ShieldBan,
  ShieldCheck,
  UnlockKeyhole,
} from 'lucide-vue-next'
import { ipControlApi } from '../api'
import PageIntro from '../components/PageIntro.vue'
import EmptyState from '../components/EmptyState.vue'

const defaultRule = () => ({
  id: 1,
  enabled: false,
  windowSeconds: 60,
  maxRequests: 120,
  blockSeconds: 900,
  whitelist: '',
})

const rule = reactive(defaultRule())
const ruleLoading = ref(false)
const ruleSaving = ref(false)
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const loadError = ref(false)
const query = reactive({ ipAddress: '', source: '', active: true, page: 1, size: 20 })
const manualVisible = ref(false)
const manualSaving = ref(false)
const manual = reactive({ ipAddress: '', durationSeconds: 3600, reason: '' })

const activeOnPage = computed(() => rows.value.filter((row) => row.active).length)
const whitelistCount = computed(
  () =>
    String(rule.whitelist || '')
      .split(/[\n,]+/)
      .map((item) => item.trim())
      .filter(Boolean).length
)
const ruleState = computed(() => (rule.enabled ? 'ENABLED' : 'DISABLED'))

async function loadRule() {
  ruleLoading.value = true
  try {
    Object.assign(rule, defaultRule(), await ipControlApi.getRule())
  } catch (e) {
    /* 错误已由拦截器提示 */
  } finally {
    ruleLoading.value = false
  }
}

function listParams() {
  return {
    ipAddress: query.ipAddress.trim() || undefined,
    source: query.source || undefined,
    active: query.active === '' ? undefined : query.active,
    page: query.page,
    size: query.size,
  }
}

async function loadBlocks() {
  loading.value = true
  loadError.value = false
  try {
    const data = await ipControlApi.listBlocks(listParams())
    rows.value = data.records || []
    total.value = data.total || 0
  } catch (e) {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

async function loadAll() {
  await Promise.all([loadRule(), loadBlocks()])
}

async function saveRule() {
  if (rule.windowSeconds < 1 || rule.maxRequests < 1 || rule.blockSeconds < 0) {
    ElMessage.warning('请填写有效的规则参数')
    return
  }
  ruleSaving.value = true
  try {
    const data = await ipControlApi.updateRule({
      enabled: rule.enabled,
      windowSeconds: rule.windowSeconds,
      maxRequests: rule.maxRequests,
      blockSeconds: rule.blockSeconds,
      whitelist: rule.whitelist,
    })
    Object.assign(rule, data)
    ElMessage.success('IP 防护规则已保存')
    await loadBlocks()
  } catch (e) {
    /* 错误已由拦截器提示 */
  } finally {
    ruleSaving.value = false
  }
}

function openManual() {
  Object.assign(manual, { ipAddress: '', durationSeconds: 3600, reason: '' })
  manualVisible.value = true
}

async function submitManual() {
  if (!manual.ipAddress.trim()) {
    ElMessage.warning('请输入 IP 地址')
    return
  }
  if (manual.durationSeconds == null || manual.durationSeconds < 0) {
    ElMessage.warning('封禁时长不能小于 0')
    return
  }
  manualSaving.value = true
  try {
    await ipControlApi.block({
      ipAddress: manual.ipAddress.trim(),
      durationSeconds: manual.durationSeconds,
      reason: manual.reason.trim(),
    })
    ElMessage.success('IP 已加入封禁名单')
    manualVisible.value = false
    query.active = true
    query.page = 1
    await loadBlocks()
  } catch (e) {
    /* 错误已由拦截器提示 */
  } finally {
    manualSaving.value = false
  }
}

async function unblock(row) {
  const confirmed = await ElMessageBox.confirm(`确认解除 ${row.ipAddress} 的封禁吗？`, '解除封禁', {
    type: 'warning',
    confirmButtonText: '解除',
    cancelButtonText: '取消',
  })
    .then(() => true)
    .catch(() => false)
  if (!confirmed) return

  try {
    await ipControlApi.unblock(row.id)
    ElMessage.success('IP 已解封')
    await loadBlocks()
  } catch (e) {
    /* 错误已由拦截器提示 */
  }
}

function search() {
  query.page = 1
  loadBlocks()
}

function resetFilters() {
  Object.assign(query, { ipAddress: '', source: '', active: true, page: 1 })
  loadBlocks()
}

function onPage(page) {
  query.page = page
  loadBlocks()
}

function onSize(size) {
  query.size = size
  query.page = 1
  loadBlocks()
}

function formatDuration(seconds) {
  if (seconds == null || Number(seconds) === 0) return '永久'
  const value = Number(seconds)
  if (value % 86400 === 0) return `${value / 86400} 天`
  if (value % 3600 === 0) return `${value / 3600} 小时`
  if (value % 60 === 0) return `${value / 60} 分钟`
  return `${value} 秒`
}

function formatTime(value) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : '—'
}

function sourceLabel(source) {
  return source === 'AUTO' ? '自动封禁' : '手动封禁'
}

onMounted(loadAll)
</script>

<template>
  <div class="page ip-control-page">
    <PageIntro
      index="06"
      eyebrow="Trust layer / network perimeter"
      title="IP 防护"
      subtitle="按请求频率自动封禁异常来源，也可以手动维护临时或永久封禁名单。"
    >
      <template #actions>
        <el-button :loading="loading || ruleLoading" @click="loadAll">
          <el-icon><RefreshCw :stroke-width="1.8" /></el-icon>&nbsp;刷新
        </el-button>
        <el-button type="primary" @click="openManual">
          <el-icon><Plus :stroke-width="1.8" /></el-icon>&nbsp;手动封禁
        </el-button>
      </template>
    </PageIntro>

    <div class="metric-strip rise" style="--i: 1">
      <div class="metric-cell" style="--metric-color: var(--accent-cyan)">
        <div class="metric-label"><ShieldCheck :size="14" :stroke-width="1.7" /> POLICY</div>
        <div class="metric-value" :class="rule.enabled ? 'text-good' : 'text-muted'">
          {{ ruleState }}
        </div>
        <div class="metric-note">自动频率检测状态</div>
      </div>
      <div class="metric-cell" style="--metric-color: var(--accent-lime)">
        <div class="metric-label"><Gauge :size="14" :stroke-width="1.7" /> THRESHOLD</div>
        <div class="metric-value tabular-nums">{{ rule.maxRequests.toLocaleString('en-US') }}</div>
        <div class="metric-note">每 {{ formatDuration(rule.windowSeconds) }} 请求</div>
      </div>
      <div class="metric-cell" style="--metric-color: var(--accent-violet)">
        <div class="metric-label"><Clock3 :size="14" :stroke-width="1.7" /> BLOCK TIME</div>
        <div class="metric-value">{{ formatDuration(rule.blockSeconds) }}</div>
        <div class="metric-note">自动封禁时长</div>
      </div>
      <div class="metric-cell" style="--metric-color: var(--accent-pink)">
        <div class="metric-label"><ShieldBan :size="14" :stroke-width="1.7" /> ACTIVE</div>
        <div class="metric-value tabular-nums">{{ total.toLocaleString('en-US') }}</div>
        <div class="metric-note">当前筛选下的封禁记录</div>
      </div>
    </div>

    <div class="ip-control-grid rise" style="--i: 2">
      <section class="surface guard-card">
        <div class="panel-heading compact-heading">
          <div class="panel-heading-icon"><Gauge :size="17" :stroke-width="1.7" /></div>
          <div class="panel-heading-copy">
            <div class="panel-heading-title">自动封禁规则</div>
            <div class="panel-heading-note">FIXED WINDOW / PER-IP COUNTER</div>
          </div>
          <div class="panel-heading-rule"></div>
          <span class="rule-state" :class="{ 'is-enabled': rule.enabled }">{{ ruleState }}</span>
        </div>

        <el-skeleton v-if="ruleLoading" :rows="5" animated />
        <el-form v-else label-position="top" class="rule-form">
          <div class="form-row two-col">
            <el-form-item label="启用自动检测">
              <el-switch v-model="rule.enabled" />
              <span class="field-hint">关闭后不再产生新的自动封禁，已有记录仍按到期时间处理。</span>
            </el-form-item>
            <el-form-item label="白名单条目">
              <div class="field-stat"><Globe2 :size="14" /> {{ whitelistCount }} 条</div>
              <span class="field-hint">支持单个 IP 或 CIDR，每行一条。</span>
            </el-form-item>
          </div>
          <div class="form-row three-col">
            <el-form-item label="统计窗口（秒）">
              <el-input-number
                v-model="rule.windowSeconds"
                :min="1"
                :max="86400"
                :step="10"
                controls-position="right"
              />
            </el-form-item>
            <el-form-item label="最大请求数">
              <el-input-number
                v-model="rule.maxRequests"
                :min="1"
                :max="1000000"
                :step="10"
                controls-position="right"
              />
            </el-form-item>
            <el-form-item label="自动封禁（秒）">
              <el-input-number
                v-model="rule.blockSeconds"
                :min="0"
                :max="31536000"
                :step="60"
                controls-position="right"
              />
              <span class="field-hint">填 0 表示永久。</span>
            </el-form-item>
          </div>
          <el-form-item label="IP 白名单">
            <el-input
              v-model="rule.whitelist"
              type="textarea"
              :rows="3"
              resize="vertical"
              placeholder="例如：203.0.113.10&#10;10.0.0.0/8&#10;2001:db8::/32"
            />
          </el-form-item>
          <div class="rule-footer">
            <span class="rule-note mono"
              ><CircleAlert :size="13" /> 仅统计 /v1/* 请求，管理端不会被自动封禁。</span
            >
            <el-button type="primary" :loading="ruleSaving" @click="saveRule">保存规则</el-button>
          </div>
        </el-form>
      </section>

      <section class="surface guard-card guard-help">
        <div class="panel-heading compact-heading">
          <div class="panel-heading-icon"><ShieldBan :size="17" :stroke-width="1.7" /></div>
          <div class="panel-heading-copy">
            <div class="panel-heading-title">策略说明</div>
            <div class="panel-heading-note">HOW IP GUARD RESPONDS</div>
          </div>
        </div>
        <div class="help-list">
          <div class="help-item">
            <span class="help-index mono">01</span>
            <div>
              <strong>先检查封禁</strong>
              <p>自动封禁在本机缓存中即时生效，手动封禁返回 403。</p>
            </div>
          </div>
          <div class="help-item">
            <span class="help-index mono">02</span>
            <div>
              <strong>超过阈值</strong>
              <p>窗口内第 N+1 次请求触发自动封禁，返回 429 并带 Retry-After。</p>
            </div>
          </div>
          <div class="help-item">
            <span class="help-index mono">03</span>
            <div>
              <strong>自动规则白名单</strong>
              <p>
                白名单支持 IPv4、IPv6 和 CIDR，命中后不会自动计数或封禁；管理员手动封禁仍然优先。
              </p>
            </div>
          </div>
        </div>
      </section>
    </div>

    <div class="surface data-panel rise" style="--i: 3">
      <div class="panel-heading">
        <div class="panel-heading-icon"><ShieldBan :size="17" :stroke-width="1.7" /></div>
        <div class="panel-heading-copy">
          <div class="panel-heading-title">封禁名单</div>
          <div class="panel-heading-note">ACTIVE / EXPIRED / MANUAL OVERRIDE</div>
        </div>
        <div class="panel-heading-rule"></div>
        <span class="live-counter mono">{{ activeOnPage }} ACTIVE ON PAGE</span>
      </div>

      <div class="filter-deck">
        <el-input
          v-model="query.ipAddress"
          clearable
          placeholder="搜索 IP"
          style="width: 190px"
          @keyup.enter="search"
        />
        <el-select v-model="query.source" clearable placeholder="来源" style="width: 140px">
          <el-option label="自动封禁" value="AUTO" />
          <el-option label="手动封禁" value="MANUAL" />
        </el-select>
        <el-select v-model="query.active" placeholder="状态" style="width: 140px">
          <el-option label="当前有效" :value="true" />
          <el-option label="已结束" :value="false" />
          <el-option label="全部" value="" />
        </el-select>
        <el-button type="primary" :loading="loading" @click="search">
          <el-icon><ListFilter :stroke-width="1.8" /></el-icon>&nbsp;筛选
        </el-button>
        <div class="filter-spacer"></div>
        <el-button @click="resetFilters"
          ><el-icon><RefreshCw :stroke-width="1.8" /></el-icon>&nbsp;重置</el-button
        >
      </div>

      <el-table :data="rows" v-loading="loading" style="width: 100%">
        <template #empty>
          <EmptyState
            :icon="loadError ? CircleAlert : ShieldBan"
            :title="loadError ? '封禁记录读取失败' : '暂无封禁记录'"
            :hint="
              loadError ? '检查网关连接后重新加载' : '触发自动规则或手动封禁后，记录会显示在这里。'
            "
          >
            <el-button v-if="loadError" type="primary" size="small" @click="loadBlocks"
              >重试</el-button
            >
          </EmptyState>
        </template>
        <el-table-column prop="ipAddress" label="IP 地址" min-width="170">
          <template #default="{ row }"
            ><span class="mono ip-value">{{ row.ipAddress }}</span></template
          >
        </el-table-column>
        <el-table-column label="来源" width="110">
          <template #default="{ row }"
            ><el-tag :type="row.blockSource === 'AUTO' ? 'warning' : 'danger'" size="small">{{
              sourceLabel(row.blockSource)
            }}</el-tag></template
          >
        </el-table-column>
        <el-table-column prop="reason" label="原因" min-width="240" show-overflow-tooltip />
        <el-table-column label="封禁时长" width="130">
          <template #default="{ row }">{{
            formatDuration(
              row.blockedUntil
                ? Math.max(
                    0,
                    Math.round((new Date(row.blockedUntil) - new Date(row.blockedAt)) / 1000)
                  )
                : 0
            )
          }}</template>
        </el-table-column>
        <el-table-column label="开始时间" width="170">
          <template #default="{ row }"
            ><span class="tabular-nums">{{ formatTime(row.blockedAt) }}</span></template
          >
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }"
            ><span class="status" :class="row.active ? 'is-danger' : 'is-info'">{{
              row.active ? '生效中' : '已结束'
            }}</span></template
          >
        </el-table-column>
        <el-table-column label="操作" width="100" align="right">
          <template #default="{ row }">
            <el-button v-if="row.active" link type="primary" @click="unblock(row)">
              <el-icon><UnlockKeyhole :stroke-width="1.8" /></el-icon>解封
            </el-button>
            <span v-else class="muted">—</span>
          </template>
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
      <div class="data-footnote mono">IP CONTROL / {{ rows.length }} RECORDS IN CURRENT PAGE</div>
    </div>

    <el-dialog v-model="manualVisible" title="手动封禁 IP" width="520px">
      <el-form label-width="110px">
        <el-form-item label="IP 地址" required>
          <el-input v-model="manual.ipAddress" placeholder="203.0.113.10 或 2001:db8::1" />
        </el-form-item>
        <el-form-item label="封禁时长（秒）" required>
          <el-input-number
            v-model="manual.durationSeconds"
            :min="0"
            :max="31536000"
            :step="300"
            controls-position="right"
            style="width: 100%"
          />
          <span class="field-hint">0 表示永久封禁。</span>
        </el-form-item>
        <el-form-item label="原因">
          <el-input
            v-model="manual.reason"
            type="textarea"
            :rows="3"
            maxlength="255"
            show-word-limit
            placeholder="例如：异常扫描、密钥暴力尝试"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="manualVisible = false">取消</el-button>
        <el-button type="danger" :loading="manualSaving" @click="submitManual">确认封禁</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.ip-control-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.55fr) minmax(300px, 0.75fr);
  gap: 16px;
  margin-bottom: 16px;
}

.guard-card {
  min-width: 0;
  padding: 20px;
}

.compact-heading {
  margin-bottom: 20px;
}

.rule-state,
.live-counter {
  color: var(--app-text-faint);
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: 0.12em;
}

.rule-state.is-enabled {
  color: var(--accent-lime);
}

.rule-form :deep(.el-form-item) {
  margin-bottom: 16px;
}

.form-row {
  display: grid;
  gap: 14px;
}

.two-col {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.three-col {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.rule-form :deep(.el-input-number) {
  width: 100%;
}

.field-hint {
  display: block;
  margin-top: 6px;
  color: var(--app-text-faint);
  font-size: 11px;
  line-height: 1.5;
}

.field-stat {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  height: 32px;
  color: var(--accent-cyan);
  font-family: var(--font-mono);
  font-size: 12px;
}

.rule-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding-top: 4px;
}

.rule-note {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--app-text-faint);
  font-size: 10px;
}

.help-list {
  display: grid;
  gap: 18px;
}

.help-item {
  display: grid;
  grid-template-columns: 28px minmax(0, 1fr);
  gap: 10px;
  padding-bottom: 16px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.help-item:last-child {
  padding-bottom: 0;
  border-bottom: 0;
}

.help-index {
  color: var(--accent-lime);
  font-size: 10px;
}

.help-item strong {
  display: block;
  color: var(--app-text);
  font-size: 13px;
}

.help-item p {
  margin: 5px 0 0;
  color: var(--app-text-faint);
  font-size: 11px;
  line-height: 1.6;
}

.text-good {
  color: var(--accent-lime);
}

.text-muted {
  color: var(--app-text-faint);
}

.ip-value {
  color: var(--accent-cyan);
}

.muted {
  color: var(--app-text-faint);
}

@media (max-width: 1100px) {
  .ip-control-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 700px) {
  .guard-card {
    padding: 16px;
  }

  .two-col,
  .three-col {
    grid-template-columns: 1fr;
  }

  .rule-footer {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
