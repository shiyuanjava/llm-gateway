<script setup>
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  UserRound,
  LockKeyhole,
  ArrowRight,
  Cpu,
  Zap,
  ShieldCheck,
  Eye,
  EyeOff,
  RadioTower,
} from 'lucide-vue-next'
import { authApi } from '../api'
import { extractGatewayMessage } from '../api/error'
import { setSession } from '../auth/session'
import GatewayLogo from '../components/GatewayLogo.vue'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const form = reactive({ username: '', password: '' })
const passwordVisible = ref(false)

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
    const requestId = e?.requestId ? ` · 请求 ID ${e.requestId}` : ''
    if (e?.response?.status === 401) ElMessage.error(`用户名或密码错误${requestId}`)
    else if (e?.response?.status === 423)
      ElMessage.error(`登录失败次数过多,请 5 分钟后再试${requestId}`)
    else ElMessage.error(`${extractGatewayMessage(e, '请求失败')}${requestId}`)
  } finally {
    loading.value = false
  }
}

/* ============================================================
   粒子物理场:速度 + 摩擦 + 鼠标斥力的节点网络,连线随距离衰减。
   - 只画 transform 无关的 canvas 像素,不参与布局,滚动无重排
   - prefers-reduced-motion:只静态绘制一帧,不进动画循环
   - 页面隐藏时暂停 rAF,省电
   ============================================================ */
const canvasEl = ref(null)
let rafId = 0
let cleanupFns = []

function initParticles() {
  const canvas = canvasEl.value
  if (!canvas) return
  const ctx = canvas.getContext('2d')
  const dpr = Math.min(window.devicePixelRatio || 1, 2)
  const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches

  let w = 0
  let h = 0
  const mouse = { x: -9999, y: -9999 }
  const COUNT = 72
  const LINK_DIST = 130
  const REPULSE = 150
  const particles = []

  function resize() {
    w = canvas.clientWidth
    h = canvas.clientHeight
    canvas.width = Math.round(w * dpr)
    canvas.height = Math.round(h * dpr)
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  }

  function seed() {
    particles.length = 0
    for (let i = 0; i < COUNT; i++) {
      particles.push({
        x: Math.random() * w,
        y: Math.random() * h,
        vx: (Math.random() - 0.5) * 0.4,
        vy: (Math.random() - 0.5) * 0.4,
        r: 1 + Math.random() * 1.6,
      })
    }
  }

  function step() {
    for (const p of particles) {
      // 鼠标斥力:距离越近推力越大,带摩擦衰减,产生真实的"拨开"手感
      const dx = p.x - mouse.x
      const dy = p.y - mouse.y
      const d2 = dx * dx + dy * dy
      if (d2 < REPULSE * REPULSE && d2 > 0.01) {
        const d = Math.sqrt(d2)
        const force = ((REPULSE - d) / REPULSE) * 0.6
        p.vx += (dx / d) * force
        p.vy += (dy / d) * force
      }
      p.vx *= 0.96
      p.vy *= 0.96
      // 保底漂移:摩擦不至于把场冻死
      p.vx += (Math.random() - 0.5) * 0.012
      p.vy += (Math.random() - 0.5) * 0.012
      p.x += p.vx
      p.y += p.vy
      if (p.x < -20) p.x = w + 20
      if (p.x > w + 20) p.x = -20
      if (p.y < -20) p.y = h + 20
      if (p.y > h + 20) p.y = -20
    }
  }

  function draw() {
    ctx.clearRect(0, 0, w, h)
    for (let i = 0; i < particles.length; i++) {
      const a = particles[i]
      for (let j = i + 1; j < particles.length; j++) {
        const b = particles[j]
        const dx = a.x - b.x
        const dy = a.y - b.y
        const d2 = dx * dx + dy * dy
        if (d2 < LINK_DIST * LINK_DIST) {
          const t = 1 - Math.sqrt(d2) / LINK_DIST
          ctx.strokeStyle = `rgba(124, 92, 255, ${0.16 * t})`
          ctx.lineWidth = 1
          ctx.beginPath()
          ctx.moveTo(a.x, a.y)
          ctx.lineTo(b.x, b.y)
          ctx.stroke()
        }
      }
    }
    for (const p of particles) {
      ctx.fillStyle = 'rgba(165, 243, 252, 0.55)'
      ctx.beginPath()
      ctx.arc(p.x, p.y, p.r, 0, Math.PI * 2)
      ctx.fill()
    }
  }

  function loop() {
    step()
    draw()
    rafId = requestAnimationFrame(loop)
  }

  function onMouse(e) {
    const rect = canvas.getBoundingClientRect()
    mouse.x = e.clientX - rect.left
    mouse.y = e.clientY - rect.top
  }
  function onLeave() {
    mouse.x = -9999
    mouse.y = -9999
  }
  function onResize() {
    resize()
  }
  function onVisibility() {
    if (document.hidden) {
      cancelAnimationFrame(rafId)
    } else if (!reduced) {
      rafId = requestAnimationFrame(loop)
    }
  }

  resize()
  seed()
  if (reduced) {
    draw() // 静态一帧,信息完整
  } else {
    window.addEventListener('mousemove', onMouse, { passive: true })
    window.addEventListener('mouseout', onLeave, { passive: true })
    document.addEventListener('visibilitychange', onVisibility)
    rafId = requestAnimationFrame(loop)
    cleanupFns.push(() => {
      window.removeEventListener('mousemove', onMouse)
      window.removeEventListener('mouseout', onLeave)
      document.removeEventListener('visibilitychange', onVisibility)
    })
  }
  window.addEventListener('resize', onResize)
  cleanupFns.push(() => window.removeEventListener('resize', onResize))
}

onMounted(initParticles)
onBeforeUnmount(() => {
  cancelAnimationFrame(rafId)
  cleanupFns.forEach((f) => f())
  cleanupFns = []
})

const features = [
  { icon: Cpu, text: '多供应商智能路由' },
  { icon: Zap, text: 'SSE 流式转发' },
  { icon: ShieldCheck, text: '租户级护栏与审计' },
]
</script>

<template>
  <div class="login-page">
    <canvas ref="canvasEl" class="field" aria-hidden="true"></canvas>
    <div class="vignette" aria-hidden="true"></div>

    <!-- 左半:实验排版秀场 -->
    <section class="hero">
      <div class="kicker mono rise" style="--i: 0">// LLM INFRASTRUCTURE CONSOLE</div>
      <h1 class="mega">
        <span class="mega-solid rise" style="--i: 1">LLM</span>
        <span class="mega-outline rise" style="--i: 2">GATEWAY</span>
      </h1>
      <p class="hero-sub rise" style="--i: 3">
        一个入口,路由所有大模型。<br />
        计费、护栏、降级与审计,尽在掌握。
      </p>
      <div class="chips rise" style="--i: 4">
        <span v-for="f in features" :key="f.text" class="chip">
          <component :is="f.icon" :size="14" :stroke-width="1.8" />
          {{ f.text }}
        </span>
      </div>
      <div class="hero-stamp mono rise" style="--i: 5">
        <RadioTower :size="14" :stroke-width="1.7" />
        <span>EDGE CONTROL / BUILD 01</span>
      </div>
      <!-- 跑马灯:路由别名流,水平循环 -->
      <div class="marquee mono" aria-hidden="true">
        <div class="marquee-track">
          <span v-for="n in 2" :key="n" class="marquee-seg">
            auto / deepseek-v4-pro &nbsp;/&nbsp; cheap / mock-small &nbsp;/&nbsp; smart / escalate
            &gt; 4k &nbsp;/&nbsp; fallback chain &nbsp;/&nbsp; sse streaming &nbsp;/&nbsp; cost
            attribution &nbsp;/&nbsp;
          </span>
        </div>
      </div>
    </section>

    <!-- 右半:玻璃登录卡 -->
    <section class="panel">
      <div class="login-card rise" style="--i: 2">
        <div class="login-brand">
          <div class="login-logo rise" style="--i: 3">
            <GatewayLogo :size="60" />
          </div>
          <div class="brand-name rise" style="--i: 4">LLM Gateway</div>
          <div class="brand-sub mono rise" style="--i: 5">SIGN IN // 管理控制台</div>
        </div>
        <el-form @keyup.enter="submit">
          <el-form-item class="rise" style="--i: 6">
            <el-input v-model="form.username" placeholder="用户名" size="large">
              <template #prefix>
                <el-icon><UserRound :stroke-width="1.8" /></el-icon>
              </template>
            </el-input>
          </el-form-item>
          <el-form-item class="rise" style="--i: 7">
            <el-input
              v-model="form.password"
              :type="passwordVisible ? 'text' : 'password'"
              placeholder="密码"
              size="large"
            >
              <template #prefix>
                <el-icon><LockKeyhole :stroke-width="1.8" /></el-icon>
              </template>
              <template #suffix>
                <button
                  class="password-action"
                  type="button"
                  :aria-label="passwordVisible ? '隐藏密码' : '显示密码'"
                  @click="passwordVisible = !passwordVisible"
                >
                  <EyeOff v-if="passwordVisible" :size="16" :stroke-width="1.7" />
                  <Eye v-else :size="16" :stroke-width="1.7" />
                </button>
              </template>
            </el-input>
          </el-form-item>
          <el-button
            type="primary"
            size="large"
            class="submit rise"
            style="--i: 8"
            :loading="loading"
            @click="submit"
          >
            <span>进入控制台</span>
            <el-icon class="submit-arrow"><ArrowRight :stroke-width="2" /></el-icon>
          </el-button>
        </el-form>
      </div>
    </section>
  </div>
</template>

<style scoped>
.login-page {
  position: relative;
  height: 100vh;
  display: grid;
  grid-template-columns: 1.2fr 1fr;
  overflow: hidden;
  background:
    radial-gradient(90% 90% at 15% 10%, rgba(124, 92, 255, 0.14), transparent 60%),
    radial-gradient(70% 80% at 95% 90%, rgba(34, 211, 238, 0.1), transparent 60%), #08090b;
}
.field {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  z-index: 0;
}
.vignette {
  position: absolute;
  inset: 0;
  z-index: 1;
  pointer-events: none;
  background: radial-gradient(120% 100% at 50% 50%, transparent 55%, rgba(3, 4, 10, 0.7) 100%);
}

/* ===== 左半:超大字排版 ===== */
.hero {
  position: relative;
  z-index: 2;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 0 4vw 0 6vw;
  min-width: 0;
}
.kicker {
  font-size: 12px;
  letter-spacing: 0.32em;
  color: var(--accent-cyan);
  margin-bottom: 18px;
}
.mega {
  margin: 0;
  font-family: var(--font-display);
  line-height: 0.92;
  letter-spacing: -0.04em;
  display: flex;
  flex-direction: column;
  user-select: none;
}
.mega-solid {
  font-size: clamp(72px, 11vw, 168px);
  font-weight: 700;
  background: linear-gradient(105deg, #ffffff 10%, #b8a6ff 55%, #67e8f9 95%);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  filter: drop-shadow(0 0 26px rgba(124, 92, 255, 0.35));
}
.mega-outline {
  font-size: clamp(52px, 8vw, 120px);
  font-weight: 700;
  color: transparent;
  -webkit-text-stroke: 1.5px rgba(255, 255, 255, 0.32);
  margin-top: 2px;
}
.hero-sub {
  margin: 26px 0 0;
  color: var(--app-text-secondary);
  font-size: 15px;
  line-height: 1.9;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 26px;
}

.hero-stamp {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  width: fit-content;
  margin-top: 28px;
  padding-top: 12px;
  color: var(--app-text-faint);
  font-size: 9px;
  letter-spacing: 0.15em;
  border-top: 1px solid rgba(255, 255, 255, 0.14);
}

.hero-stamp svg {
  color: var(--accent-lime);
}
.chip {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 7px 14px;
  border-radius: 999px;
  font-size: 12.5px;
  color: #cdd2e6;
  border: 1px solid var(--app-border-strong);
  background: rgba(255, 255, 255, 0.04);
}

/* 跑马灯:两段相同内容首尾相接,位移 -50% 无缝循环 */
.marquee {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 34px;
  overflow: hidden;
  font-size: 12px;
  letter-spacing: 0.08em;
  color: var(--app-text-faint);
  mask-image: linear-gradient(90deg, transparent, #000 12%, #000 88%, transparent);
  -webkit-mask-image: linear-gradient(90deg, transparent, #000 12%, #000 88%, transparent);
}
.marquee-track {
  display: inline-flex;
  white-space: nowrap;
  animation: marquee 28s linear infinite;
}
@keyframes marquee {
  to {
    transform: translateX(-50%);
  }
}

/* ===== 右半:玻璃登录卡 ===== */
.panel {
  position: relative;
  z-index: 2;
  display: grid;
  place-items: center;
  padding: 24px;
}
.login-card {
  width: min(380px, 92vw);
  padding: 42px 36px 36px;
  border-radius: var(--radius-lg);
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.06), rgba(255, 255, 255, 0.025));
  border: 1px solid var(--app-border-strong);
  backdrop-filter: blur(20px);
  box-shadow:
    0 0 0 1px rgba(124, 92, 255, 0.1),
    0 32px 90px rgba(0, 0, 0, 0.6);
}
.login-brand {
  text-align: center;
  margin-bottom: 30px;
}
.login-logo {
  display: grid;
  place-items: center;
  margin-bottom: 14px;
}
.brand-name {
  font-family: var(--font-display);
  font-size: 22px;
  font-weight: 700;
  letter-spacing: -0.02em;
  background: linear-gradient(90deg, #b8a6ff, #8b5cf6 55%, #53d9ff);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
}
.brand-sub {
  color: var(--app-text-faint);
  font-size: 11px;
  letter-spacing: 0.22em;
  margin-top: 6px;
}

.password-action {
  display: grid;
  place-items: center;
  padding: 0;
  color: var(--app-text-faint);
  background: transparent;
  border: 0;
  cursor: pointer;
}

.password-action:hover {
  color: var(--accent-cyan);
}
.submit {
  width: 100%;
  height: 46px;
  font-size: 15px;
  letter-spacing: 0.06em;
}
.submit .submit-arrow {
  margin-left: 8px;
  transition: transform 0.25s var(--spring);
}
.submit:hover .submit-arrow {
  transform: translateX(4px);
}

@media (max-width: 900px) {
  .login-page {
    grid-template-columns: 1fr;
  }
  .hero {
    display: none;
  }
}
@media (prefers-reduced-motion: reduce) {
  .marquee-track {
    animation: none;
  }
}
</style>
