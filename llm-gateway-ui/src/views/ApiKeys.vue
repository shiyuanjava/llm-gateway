<script setup>
import { onMounted, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Refresh, Edit, Delete, CopyDocument } from '@element-plus/icons-vue'
import { apiKeyApi } from '../api'
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
    <div class="page-header">
      <div>
        <h2 class="page-title">API Key</h2>
        <div class="page-subtitle">密钥 → 租户 / 角色 / 可用模型,改动即时生效</div>
      </div>
    </div>

    <div class="surface" style="padding: 16px">
      <div class="toolbar">
        <el-button type="primary" @click="openCreate"
          ><el-icon><Plus /></el-icon>&nbsp;新增 Key</el-button
        >
        <div class="spacer"></div>
        <el-button :loading="loading" @click="load"
          ><el-icon><Refresh /></el-icon>&nbsp;刷新</el-button
        >
      </div>

      <el-table :data="rows" v-loading="loading" style="width: 100%" empty-text="暂无 API Key">
        <el-table-column prop="keyPrefix" label="Key 前缀" min-width="160">
          <template #default="{ row }"
            ><code>{{ row.keyPrefix }}…</code></template
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
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.enabled !== false ? 'success' : 'info'" effect="light" size="small">
              {{ row.enabled !== false ? '启用' : '停用' }}
            </el-tag>
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
      :title="dialog.mode === 'create' ? '新增 API Key' : '编辑 API Key'"
      width="520px"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-alert
          v-if="dialog.mode === 'create'"
          type="info"
          :closable="false"
          style="margin-bottom: 16px"
          title="Key 由服务端生成,创建成功后仅展示一次"
        />
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
        title="请立即保存,关闭后无法再次查看完整 Key"
        style="margin-bottom: 16px"
      />
      <div class="key-box">
        <code>{{ created.apiKey }}</code>
        <el-button link type="primary" @click="copyKey"
          ><el-icon><CopyDocument /></el-icon>复制</el-button
        >
      </div>
      <template #footer>
        <el-button type="primary" @click="dismissCreated">我已保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
code {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary-dark-2);
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
