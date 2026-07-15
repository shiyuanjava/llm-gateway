<script setup>
import { onMounted } from 'vue'
import { Plus, Refresh, Edit, Delete } from '@element-plus/icons-vue'
import { pricingApi } from '../api'
import { useCrudDialog } from '../composables/useCrudDialog'

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

const rules = {
  model: [{ required: true, message: '请输入模型名', trigger: 'blur' }],
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">计费单价</h2>
        <div class="page-subtitle">各模型每 1K Token 单价(美元),用于成本归因</div>
      </div>
    </div>

    <div class="surface" style="padding: 16px">
      <div class="toolbar">
        <el-button type="primary" @click="openCreate"
          ><el-icon><Plus /></el-icon>&nbsp;新增单价</el-button
        >
        <div class="spacer"></div>
        <el-button :loading="loading" @click="load"
          ><el-icon><Refresh /></el-icon>&nbsp;刷新</el-button
        >
      </div>

      <el-table :data="rows" v-loading="loading" style="width: 100%" empty-text="暂无计费数据">
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
              ><el-icon><Edit /></el-icon>编辑</el-button
            >
            <el-button link type="danger" :loading="deleting[row.id]" @click="remove(row)"
              ><el-icon><Delete /></el-icon>删除</el-button
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
