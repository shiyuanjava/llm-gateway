import { ref, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

/**
 * 通用 CRUD 弹窗逻辑:列表加载、新增/编辑弹窗、提交(校验失败不卡 saving)、删除(带确认与按行 loading)。
 *
 * @param {Object} options
 * @param {Object} options.api        含 list/create/update/remove 的 API 对象
 * @param {Function} options.blankForm 返回一份空白表单对象的工厂
 * @param {Function} [options.confirmText] (row) => 删除确认文案
 * @param {Function} [options.buildPayload] (form) => 提交前对表单做清洗,默认浅拷贝
 */
export function useCrudDialog({ api, blankForm, confirmText, buildPayload }) {
  const loading = ref(false)
  const rows = ref([])
  const dialog = reactive({ visible: false, mode: 'create', saving: false })
  const formRef = ref()
  const form = reactive(blankForm())
  const deleting = reactive({})

  async function load() {
    loading.value = true
    try {
      rows.value = await api.list()
    } catch (e) {
      /* 错误已由拦截器提示;吞掉 rejection,调用方可安全地 fire-and-forget */
    } finally {
      loading.value = false
    }
  }

  function openCreate() {
    Object.assign(form, blankForm())
    dialog.mode = 'create'
    dialog.visible = true
  }

  function openEdit(row) {
    Object.assign(form, blankForm(), JSON.parse(JSON.stringify(row)))
    dialog.mode = 'edit'
    dialog.visible = true
  }

  async function submit() {
    try {
      await formRef.value.validate()
    } catch {
      return // 校验失败:让用户改完再试
    }
    dialog.saving = true
    try {
      const payload = buildPayload ? buildPayload(form) : { ...form }
      if (dialog.mode === 'create') await api.create(payload)
      else await api.update(form.id, payload)
      ElMessage.success('保存成功')
      dialog.visible = false
      load()
    } catch (e) {
      /* 错误已由拦截器提示,保留弹窗让用户重试 */
    } finally {
      dialog.saving = false
    }
  }

  async function remove(row) {
    const confirmed = await ElMessageBox.confirm(
      confirmText ? confirmText(row) : '确认删除该记录?',
      '删除确认',
      {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger',
      }
    )
      .then(() => true)
      .catch(() => false) // 取消不是错误
    if (!confirmed) return
    deleting[row.id] = true
    try {
      await api.remove(row.id)
      ElMessage.success('已删除')
      load()
    } catch (e) {
      /* 错误已由拦截器提示 */
    } finally {
      deleting[row.id] = false
    }
  }

  return {
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
  }
}
