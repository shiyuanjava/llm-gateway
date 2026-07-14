<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { authApi } from '../api'
import { setSession } from '../auth/session'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const form = reactive({ username: '', password: '' })

async function submit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    const data = await authApi.login({ ...form })
    setSession(data.token, data.username, data.expiresAt)
    router.push(String(route.query.redirect || '/dashboard'))
  } catch (e) {
    // 登录页的错误提示由这里统一负责(http 拦截器在登录页保持静默)
    if (e?.response?.status === 401) ElMessage.error('用户名或密码错误')
    else if (e?.response?.status === 423) ElMessage.error('登录失败次数过多,请 5 分钟后再试')
    else ElMessage.error(e?.response?.data?.msg || e?.message || '网络错误,请稍后重试')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-card surface">
      <div class="login-brand">
        <div class="brand-name">LLM Gateway</div>
        <div class="brand-sub">管理控制台登录</div>
      </div>
      <el-form @keyup.enter="submit">
        <el-form-item>
          <el-input v-model="form.username" placeholder="用户名" size="large">
            <template #prefix
              ><el-icon><User /></el-icon
            ></template>
          </el-input>
        </el-form-item>
        <el-form-item>
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码"
            size="large"
            show-password
          >
            <template #prefix
              ><el-icon><Lock /></el-icon
            ></template>
          </el-input>
        </el-form-item>
        <el-button
          type="primary"
          size="large"
          style="width: 100%"
          :loading="loading"
          @click="submit"
        >
          登 录
        </el-button>
      </el-form>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  height: 100vh;
  display: grid;
  place-items: center;
  background: var(--app-sidebar-bg, #f5f7fa);
}
.login-card {
  width: 360px;
  padding: 40px 36px 32px;
}
.login-brand {
  text-align: center;
  margin-bottom: 28px;
}
.brand-name {
  font-size: 22px;
  font-weight: 700;
  letter-spacing: -0.02em;
}
.brand-sub {
  color: var(--app-text-secondary, #909399);
  font-size: 13px;
  margin-top: 4px;
}
</style>
