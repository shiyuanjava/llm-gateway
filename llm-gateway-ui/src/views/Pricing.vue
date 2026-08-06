<script setup>
import { computed, onMounted } from 'vue'
import {
  Plus,
  RefreshCw,
  SquarePen,
  Trash2,
  CircleDollarSign,
  Database,
  ArrowDownUp,
  ReceiptText,
} from 'lucide-vue-next'
import { pricingApi } from '../api'
import { useCrudDialog } from '../composables/useCrudDialog'
import PageIntro from '../components/PageIntro.vue'
import EmptyState from '../components/EmptyState.vue'

const {
  loading,
  rows,
  dialog,
  formRef,
  form,
  deleting,
  load,
  openCreate,
  openEdit,
  submit,
  remove,
} = useCrudDialog({
  api: pricingApi,
  blankForm: () => ({
    id: null,
    model: '',
    inputPer1k: 0,
    outputPer1k: 0,
    cacheReadPer1k: null,
    cacheWritePer1k: null,
  }),
  confirmText: (row) => `确认删除模型「${row.model}」的计费?`,
})

const averageInput = computed(() => {
  if (!rows.value.length) return '$0.00000'
  return '$' + (rows.value.reduce((sum, row) => sum + Number(row.inputPer1k || 0), 0) / rows.value.length).toFixed(5)
})
const averageOutput = computed(() => {
  if (!rows.value.length) return '$0.00000'
  return '$' + (rows.value.reduce((sum, row) => sum + Number(row.outputPer1k || 0), 0) / rows.value.length).toFixed(5)
})

const rules = {
  model: [{ required: true, message: '请输入模型名', trigger: 'blur' }],
}

onMounted(load)
</script>

<template>
  <div class="page">
    <PageIntro
      index="03"
      eyebrow="Economics layer / token ledger"
      title="计费单价"
      subtitle="每 1K Token 的输入、输出与缓存费率，用于把上游消耗折算成可追踪成本。"
    >
      <template #actions>
        <el-button type="primary" @click="openCreate">
          <el-icon><Plus :stroke-width="1.8" /></el-icon>&nbsp;新增单价
        </el-button>
      </template>
    </PageIntro>

    <div class="metric-strip rise" style="--i: 1">
      <div class="metric-cell" style="--metric-color: var(--accent-cyan)">
        <div class="metric-label"><Database :size="14" :stroke-width="1.7" /> MODELS</div>
        <div class="metric-value tabular-nums">{{ rows.length }}</div>
        <div class="metric-note">计费模型</div>
      </div>
      <div class="metric-cell" style="--metric-color: var(--accent-lime)">
        <div class="metric-label"><ArrowDownUp :size="14" :stroke-width="1.7" /> AVG INPUT</div>
        <div class="metric-value tabular-nums">{{ averageInput }}</div>
        <div class="metric-note">平均输入 / 1K</div>
      </div>
      <div class="metric-cell" style="--metric-color: var(--accent-violet)">
        <div class="metric-label"><CircleDollarSign :size="14" :stroke-width="1.7" /> AVG OUTPUT</div>
        <div class="metric-value tabular-nums">{{ averageOutput }}</div>
        <div class="metric-note">平均输出 / 1K</div>
      </div>
      <div class="metric-cell" style="--metric-color: var(--accent-pink)">
        <div class="metric-label"><ReceiptText :size="14" :stroke-width="1.7" /> UNIT</div>
        <div class="metric-value">USD / 1K</div>
        <div class="metric-note">统一核算单位</div>
      </div>
    </div>

    <div class="surface data-panel rise" style="--i: 2">
      <div class="panel-heading">
        <div class="panel-heading-icon"><CircleDollarSign :size="17" :stroke-width="1.7" /></div>
        <div class="panel-heading-copy">
          <div class="panel-heading-title">费率矩阵</div>
          <div class="panel-heading-note">MODEL COST ATTRIBUTION / USD PER 1K</div>
        </div>
        <div class="panel-heading-rule"></div>
        <el-button :loading="loading" @click="load"
          ><el-icon><RefreshCw :stroke-width="1.8" /></el-icon>&nbsp;刷新</el-button
        >
      </div>

      <el-table :data="rows" v-loading="loading" style="width: 100%">
        <template #empty>
          <EmptyState :icon="CircleDollarSign" title="暂无计费数据" hint="新增一个模型费率,开始成本归因" />
        </template>
        <el-table-column prop="model" label="模型" min-width="200" />
        <el-table-column label="输入 / 1K (USD)" width="180" align="right">
          <template #default="{ row }"
            ><span class="tabular-nums">${{ Number(row.inputPer1k).toFixed(5) }}</span></template
          >
        </el-table-column>
        <el-table-column label="输出 / 1K (USD)" width="180" align="right">
          <template #default="{ row }"
            ><span class="tabular-nums">${{ Number(row.outputPer1k).toFixed(5) }}</span></template
          >
        </el-table-column>
        <el-table-column label="缓存读 / 1K (USD)" width="160" align="right">
          <template #default="{ row }">
            <span class="tabular-nums">{{
              row.cacheReadPer1k == null ? '—' : '$' + Number(row.cacheReadPer1k).toFixed(5)
            }}</span>
          </template>
        </el-table-column>
        <el-table-column label="缓存写 / 1K (USD)" width="160" align="right">
          <template #default="{ row }">
            <span class="tabular-nums">{{
              row.cacheWritePer1k == null ? '—' : '$' + Number(row.cacheWritePer1k).toFixed(5)
            }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" align="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)"
              ><el-icon><SquarePen :stroke-width="1.8" /></el-icon>编辑</el-button
            >
            <el-button link type="danger" :loading="deleting[row.id]" @click="remove(row)"
              ><el-icon><Trash2 :stroke-width="1.8" /></el-icon>删除</el-button
            >
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog
      v-model="dialog.visible"
      :title="dialog.mode === 'create' ? '新增计费' : '编辑计费'"
      width="480px"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="130px">
        <el-form-item label="模型" prop="model">
          <el-input
            v-model="form.model"
            placeholder="deepseek-v4-pro 或 mock*（尾部通配）"
            :disabled="dialog.mode === 'edit'"
          />
        </el-form-item>
        <el-form-item label="输入 / 1K (USD)">
          <el-input-number
            v-model="form.inputPer1k"
            :precision="5"
            :step="0.0001"
            :min="0"
            controls-position="right"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="输出 / 1K (USD)">
          <el-input-number
            v-model="form.outputPer1k"
            :precision="5"
            :step="0.0001"
            :min="0"
            controls-position="right"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="缓存读 / 1K (USD)">
          <el-input-number
            v-model="form.cacheReadPer1k"
            :precision="5"
            :step="0.0001"
            :min="0"
            :value-on-clear="null"
            controls-position="right"
            style="width: 100%"
            placeholder="留空按输入单价"
          />
        </el-form-item>
        <el-form-item label="缓存写 / 1K (USD)">
          <el-input-number
            v-model="form.cacheWritePer1k"
            :precision="5"
            :step="0.0001"
            :min="0"
            :value-on-clear="null"
            controls-position="right"
            style="width: 100%"
            placeholder="留空按输入单价"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="dialog.saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
