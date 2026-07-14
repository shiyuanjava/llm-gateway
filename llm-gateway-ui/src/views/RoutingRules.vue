<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Refresh, Edit, Delete, Right } from '@element-plus/icons-vue'
import { routingApi, metaApi } from '../api'
import { useCrudDialog } from '../composables/useCrudDialog'

const providers = ref([])

const blankForm = () => ({
  id: null,
  alias: '',
  primaryProvider: '',
  primaryModel: '',
  maxPromptTokens: null,
  escalateProvider: '',
  escalateModel: '',
  fallbacks: [],
})

const {
  loading,
  rows,
  dialog,
  formRef,
  form,
  deleting,
  load,
  openCreate,
  openEdit: baseOpenEdit,
  submit,
  remove,
} = useCrudDialog({
  api: routingApi,
  blankForm,
  confirmText: (row) => `确认删除路由别名「${row.alias}」?`,
  buildPayload: (f) => ({ ...f, fallbacks: f.fallbacks.filter((x) => x.provider && x.model) }),
})

function openEdit(row) {
  baseOpenEdit(row)
  if (!Array.isArray(form.fallbacks)) form.fallbacks = []
}

function addFallback() {
  form.fallbacks.push({ provider: '', model: '' })
}
function removeFallback(i) {
  form.fallbacks.splice(i, 1)
}

const rules = {
  alias: [{ required: true, message: '请输入别名', trigger: 'blur' }],
  primaryProvider: [{ required: true, message: '请选择首选供应商', trigger: 'change' }],
  primaryModel: [{ required: true, message: '请输入首选模型', trigger: 'blur' }],
}

onMounted(async () => {
  load()
  try {
    providers.value = (await metaApi.get()).providers || []
  } catch (e) {
    ElMessage.warning('供应商列表加载失败,可手动输入供应商名')
  }
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">路由规则</h2>
        <div class="page-subtitle">别名 → 首选 + 升级阈值 + 降级链,改动即时生效</div>
      </div>
    </div>

    <div class="surface" style="padding: 16px">
      <div class="toolbar">
        <el-button type="primary" @click="openCreate"
          ><el-icon><Plus /></el-icon>&nbsp;新增规则</el-button
        >
        <div class="spacer"></div>
        <el-button :loading="loading" @click="load"
          ><el-icon><Refresh /></el-icon>&nbsp;刷新</el-button
        >
      </div>

      <el-table :data="rows" v-loading="loading" style="width: 100%" empty-text="暂无路由规则">
        <el-table-column prop="alias" label="别名" width="120">
          <template #default="{ row }"
            ><el-tag effect="dark" round>{{ row.alias }}</el-tag></template
          >
        </el-table-column>
        <el-table-column label="首选" min-width="190">
          <template #default="{ row }">
            <span class="mono">{{ row.primaryProvider }}:{{ row.primaryModel }}</span>
          </template>
        </el-table-column>
        <el-table-column label="降级链" min-width="260">
          <template #default="{ row }">
            <span v-if="!row.fallbacks || !row.fallbacks.length" class="muted">—</span>
            <span v-else class="chain">
              <template v-for="(f, i) in row.fallbacks" :key="i">
                <el-icon v-if="i" class="chain-arrow"><Right /></el-icon>
                <el-tag size="small" type="info" effect="plain" class="mono"
                  >{{ f.provider }}:{{ f.model }}</el-tag
                >
              </template>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="升级 (阈值)" min-width="190">
          <template #default="{ row }">
            <span v-if="row.escalateProvider" class="mono">
              {{ row.escalateProvider }}:{{ row.escalateModel }}
              <span class="muted">(&gt;{{ row.maxPromptTokens }})</span>
            </span>
            <span v-else class="muted">—</span>
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
      :title="dialog.mode === 'create' ? '新增路由规则' : '编辑路由规则'"
      width="640px"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="别名" prop="alias">
          <el-input
            v-model="form.alias"
            placeholder="auto / cheap / smart"
            :disabled="dialog.mode === 'edit'"
          />
        </el-form-item>

        <el-divider content-position="left">首选目标</el-divider>
        <div class="row2">
          <el-form-item label="供应商" prop="primaryProvider" class="col">
            <el-select
              v-model="form.primaryProvider"
              placeholder="选择"
              filterable
              allow-create
              style="width: 100%"
            >
              <el-option v-for="p in providers" :key="p" :label="p" :value="p" />
            </el-select>
          </el-form-item>
          <el-form-item label="模型" prop="primaryModel" class="col">
            <el-input v-model="form.primaryModel" placeholder="deepseek-v4-pro" />
          </el-form-item>
        </div>

        <el-divider content-position="left">升级(可选)</el-divider>
        <div class="row2">
          <el-form-item label="阈值 Token" class="col">
            <el-input-number
              v-model="form.maxPromptTokens"
              :min="0"
              :step="500"
              controls-position="right"
              style="width: 100%"
              placeholder="留空不升级"
            />
          </el-form-item>
          <el-form-item label="升级供应商" class="col">
            <el-select
              v-model="form.escalateProvider"
              placeholder="选择"
              filterable
              allow-create
              clearable
              style="width: 100%"
            >
              <el-option v-for="p in providers" :key="p" :label="p" :value="p" />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item label="升级模型">
          <el-input v-model="form.escalateModel" placeholder="超过阈值时改用的物理模型" />
        </el-form-item>

        <el-divider content-position="left">
          降级链
          <el-button link type="primary" @click="addFallback"
            ><el-icon><Plus /></el-icon>添加</el-button
          >
        </el-divider>
        <div v-if="!form.fallbacks.length" class="muted" style="padding: 4px 0 8px">
          暂无降级目标(首选失败时无兜底)
        </div>
        <div v-for="(f, i) in form.fallbacks" :key="i" class="fb-row">
          <span class="fb-seq">{{ i + 1 }}</span>
          <el-select
            v-model="f.provider"
            placeholder="供应商"
            filterable
            allow-create
            style="width: 160px"
          >
            <el-option v-for="p in providers" :key="p" :label="p" :value="p" />
          </el-select>
          <el-input v-model="f.model" placeholder="物理模型" style="flex: 1" />
          <el-button link type="danger" @click="removeFallback(i)"
            ><el-icon><Delete /></el-icon
          ></el-button>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="dialog.saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
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
.chain {
  display: inline-flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}
.chain-arrow {
  color: var(--app-text-secondary);
}
.row2 {
  display: flex;
  gap: 16px;
}
.row2 .col {
  flex: 1;
}
.fb-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
.fb-seq {
  width: 24px;
  height: 24px;
  flex: none;
  border-radius: 50%;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary-dark-2);
  display: grid;
  place-items: center;
  font-size: 12px;
  font-weight: 600;
}
</style>
