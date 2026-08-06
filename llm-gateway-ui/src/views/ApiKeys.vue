<script setup>
import { computed, onMounted, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import {
  Plus,
  RefreshCw,
  SquarePen,
  Trash2,
  Copy,
  KeyRound,
  TriangleAlert,
  UsersRound,
  ShieldCheck,
} from 'lucide-vue-next'
import { apiKeyApi } from '../api'
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
  api: apiKeyApi,
  blankForm: () => ({ id: null, tenant: '', roles: 'user', allowedModels: '*', enabled: true }),
  confirmText: (row) => `确认删除 API Key「${row.keyPrefix}…」?`,
  buildPayload: (f) => ({ ...f, enabled: f.enabled !== false }),
  // 创建成功后后端返回 { entity, apiKey };接管默认行为,弹一次性明文展示对话框
  onCreated: (data) => {
    dialog.visible = false
    created.apiKey = data.apiKey
    created.visible = true
    return false
  },
})

// 一次性展示的明文 Key(仅存内存,关闭即清)
const created = reactive({ visible: false, apiKey: '' })
const activeCount = computed(() => rows.value.filter((row) => row.enabled !== false).length)
const tenantCount = computed(() => new Set(rows.value.map((row) => row.tenant).filter(Boolean)).size)

async function copyKey() {
  try {
    await navigator.clipboard.writeText(created.apiKey)
    ElMessage.success('已复制')
  } catch {
    ElMessage.warning('复制失败,请手动选择复制')
  }
}

/** 关闭一次性弹窗并立即清除内存中的明文 Key。 */
function dismissCreated() {
  created.visible = false
  created.apiKey = ''
}

const rules = {
  tenant: [{ required: true, message: '请输入租户', trigger: 'blur' }],
  allowedModels: [
    { required: true, message: '请输入可用模型(逗号分隔,* 表示全部)', trigger: 'blur' },
  ],
}

onMounted(load)
</script>

<template>
  <div class="page">
    <PageIntro
      index="01"
      eyebrow="Identity layer / credentials"
      title="API Key"
      subtitle="密钥映射到租户、角色与可用模型。创建后的明文只在本次会话中出现。"
    >
      <template #actions>
        <el-button type="primary" @click="openCreate">
          <el-icon><Plus :stroke-width="1.8" /></el-icon>&nbsp;新增 Key
        </el-button>
      </template>
    </PageIntro>

    <div class="metric-strip rise" style="--i: 1">
      <div class="metric-cell" style="--metric-color: var(--accent-cyan)">
        <div class="metric-label"><KeyRound :size="14" :stroke-width="1.7" /> CREDENTIALS</div>
        <div class="metric-value tabular-nums">{{ rows.length }}</div>
        <div class="metric-note">密钥总数</div>
      </div>
      <div class="metric-cell" style="--metric-color: var(--accent-lime)">
        <div class="metric-label"><ShieldCheck :size="14" :stroke-width="1.7" /> ACTIVE</div>
        <div class="metric-value tabular-nums">{{ activeCount }}</div>
        <div class="metric-note">当前启用</div>
      </div>
      <div class="metric-cell" style="--metric-color: var(--accent-violet)">
        <div class="metric-label"><UsersRound :size="14" :stroke-width="1.7" /> TENANTS</div>
        <div class="metric-value tabular-nums">{{ tenantCount }}</div>
        <div class="metric-note">已接入租户</div>
      </div>
      <div class="metric-cell" style="--metric-color: var(--accent-pink)">
        <div class="metric-label"><TriangleAlert :size="14" :stroke-width="1.7" /> EXPOSURE</div>
        <div class="metric-value">ONE-TIME</div>
        <div class="metric-note">明文展示策略</div>
      </div>
    </div>

    <div class="surface data-panel rise" style="--i: 2">
      <div class="panel-heading">
        <div class="panel-heading-icon"><KeyRound :size="17" :stroke-width="1.7" /></div>
        <div class="panel-heading-copy">
          <div class="panel-heading-title">密钥清单</div>
          <div class="panel-heading-note">ACTIVE CREDENTIALS / SHA-256 REGISTRY</div>
        </div>
        <div class="panel-heading-rule"></div>
        <el-button :loading="loading" @click="load">
          <el-icon><RefreshCw :stroke-width="1.8" /></el-icon>&nbsp;刷新
        </el-button>
      </div>

      <el-table :data="rows" v-loading="loading" style="width: 100%">
        <template #empty>
          <EmptyState
            :icon="KeyRound"
            title="尚无 API Key"
            hint="新建一个密钥,把租户接入网关"
          />
        </template>
        <el-table-column prop="keyPrefix" label="Key 前缀" min-width="160">
          <template #default="{ row }"
            ><span class="code-chip">{{ row.keyPrefix }}…</span></template
          >
        </el-table-column>
        <el-table-column prop="tenant" label="租户" width="130" />
        <el-table-column prop="roles" label="角色" width="120" />
        <el-table-column
          prop="allowedModels"
          label="可用模型"
          min-width="160"
          show-overflow-tooltip
        />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <span class="status" :class="row.enabled !== false ? 'is-success' : 'is-info'">
              {{ row.enabled !== false ? '启用' : '停用' }}
            </span>
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
      :title="dialog.mode === 'create' ? '新增 API Key' : '编辑 API Key'"
      width="520px"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-alert
          v-if="dialog.mode === 'create'"
          type="info"
          :closable="false"
          :show-icon="false"
          style="margin-bottom: 16px"
        ><template #title><span class="alert-title"><ShieldCheck :size="15" />Key 由服务端生成,创建成功后仅展示一次</span></template></el-alert>
        <el-form-item label="租户" prop="tenant">
          <el-input v-model="form.tenant" placeholder="tenant-a" />
        </el-form-item>
        <el-form-item label="角色">
          <el-input v-model="form.roles" placeholder="user,admin(逗号分隔)" />
        </el-form-item>
        <el-form-item label="可用模型" prop="allowedModels">
          <el-input v-model="form.allowedModels" placeholder="* 或 auto,cheap(逗号分隔)" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="dialog.saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="created.visible"
      title="API Key 创建成功"
      width="560px"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      :show-close="false"
    >
      <el-alert
        type="warning"
        :closable="false"
        :show-icon="false"
        style="margin-bottom: 16px"
      ><template #title><span class="alert-title"><TriangleAlert :size="15" />请立即保存,关闭后无法再次查看完整 Key</span></template></el-alert>
      <div class="key-box">
        <code>{{ created.apiKey }}</code>
        <el-button link type="primary" @click="copyKey"
          ><el-icon><Copy :stroke-width="1.8" /></el-icon>复制</el-button
        >
      </div>
      <template #footer>
        <el-button type="primary" @click="dismissCreated">我已保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.alert-title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

code {
  font-family: var(--font-mono);
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary-dark-2);
  border: 1px solid var(--el-color-primary-light-7);
  padding: 2px 6px;
  border-radius: 6px;
  font-size: 13px;
}
.key-box {
  display: flex;
  align-items: center;
  gap: 12px;
}
.key-box code {
  flex: 1;
  word-break: break-all;
}
</style>
