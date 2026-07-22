<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { authApi } from '../api'
import { setSession } from '../auth/session'
import GatewayLogo from '../components/GatewayLogo.vue'

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
    <div class="login-fx" aria-hidden="true">
      <span class="orb orb-1"></span>
      <span class="orb orb-2"></span>
      <span class="orb orb-3"></span>
      <div class="grid-lines"></div>
    </div>

    <div class="login-card surface">
      <div class="login-brand">
        <div class="login-logo">
          <GatewayLogo :size="64" />
        </div>
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
  position: relative;
  height: 100vh;
  display: grid;
  place-items: center;
  overflow: hidden;
  background: radial-gradient(140% 120% at 20% 0%, #232a4d 0%, #141834 45%, #0b0e20 100%);
}

/* 动态科技背景:漂浮光球 + 缓慢移动的网格 */
.login-fx {
  position: absolute;
  inset: 0;
  z-index: 0;
  pointer-events: none;
}
.orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(60px);
  opacity: 0.55;
  will-change: transform;
}
.orb-1 {
  width: 420px;
  height: 420px;
  left: -80px;
  top: -60px;
  background: radial-gradient(circle, #4f46e5, transparent 70%);
  animation: float-a 18s ease-in-out infinite;
}
.orb-2 {
  width: 360px;
  height: 360px;
  right: -60px;
  bottom: -40px;
  background: radial-gradient(circle, #22d3ee, transparent 70%);
  animation: float-b 22s ease-in-out infinite;
}
.orb-3 {
  width: 300px;
  height: 300px;
  right: 30%;
  top: 20%;
  background: radial-gradient(circle, #8b5cf6, transparent 70%);
  animation: float-a 26s ease-in-out infinite reverse;
}
.grid-lines {
  position: absolute;
  inset: -2px;
  background-image:
    linear-gradient(rgba(148, 163, 255, 0.08) 1px, transparent 1px),
    linear-gradient(90deg, rgba(148, 163, 255, 0.08) 1px, transparent 1px);
  background-size: 40px 40px;
  mask-image: radial-gradient(120% 100% at 50% 40%, #000 30%, transparent 80%);
  -webkit-mask-image: radial-gradient(120% 100% at 50% 40%, #000 30%, transparent 80%);
  animation: grid-drift 30s linear infinite;
}

.login-card {
  position: relative;
  z-index: 1;
  width: 360px;
  padding: 40px 36px 32px;
  background: rgba(255, 255, 255, 0.95);
  border: 1px solid rgba(199, 210, 254, 0.5);
  box-shadow:
    0 0 0 1px rgba(129, 140, 248, 0.12),
    0 24px 70px rgba(8, 11, 32, 0.55);
  animation: card-in 0.5s cubic-bezier(0.16, 1, 0.3, 1) both;
}
.login-brand {
  text-align: center;
  margin-bottom: 28px;
}
.login-logo {
  display: grid;
  place-items: center;
  margin-bottom: 14px;
}
.brand-name {
  font-size: 22px;
  font-weight: 700;
  letter-spacing: -0.02em;
  background: linear-gradient(90deg, #4f46e5, #8b5cf6 55%, #22d3ee);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
}
.brand-sub {
  color: var(--app-text-secondary, #909399);
  font-size: 13px;
  margin-top: 4px;
}

@keyframes float-a {
  0%,
  100% {
    transform: translate(0, 0) scale(1);
  }
  50% {
    transform: translate(40px, 30px) scale(1.08);
  }
}
@keyframes float-b {
  0%,
  100% {
    transform: translate(0, 0) scale(1);
  }
  50% {
    transform: translate(-50px, -30px) scale(1.1);
  }
}
@keyframes grid-drift {
  0% {
    background-position:
      0 0,
      0 0;
  }
  100% {
    background-position:
      40px 40px,
      40px 40px;
  }
}
@keyframes card-in {
  from {
    opacity: 0;
    transform: translateY(16px) scale(0.98);
  }
  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

@media (prefers-reduced-motion: reduce) {
  .orb,
  .grid-lines,
  .login-card {
    animation: none;
  }
}
</style>
