<script setup>
import { nextTick, onBeforeUnmount, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  SendHorizontal,
  CircleStop,
  Trash2,
  Eye,
  EyeOff,
  KeyRound,
  Boxes,
  TerminalSquare,
  UserRound,
  Bot,
  Timer,
  Gauge,
  Sparkles,
} from 'lucide-vue-next'
import PageIntro from '../components/PageIntro.vue'
import EmptyState from '../components/EmptyState.vue'
import {
  CHAT_COMPLETIONS_PATH,
  STREAM_TIMEOUT_MS,
  apiUrl,
  createRequestId,
} from '../config/runtime'

/**
 * 试运行:管理员直连 /v1/chat/completions 验证网关(含 SSE 流式)。
 * - API Key 只存组件内存,刷新即失,绝不写 localStorage
 * - axios 不支持流式读取,这里用原生 fetch + ReadableStream 解析 SSE
 */
const config = reactive({ apiKey: '', model: 'default' })
const keyVisible = ref(false)
const input = ref('')
const messages = ref([]) // { role: 'user'|'assistant', content, error? }
const streaming = ref(false)
const endpoint = apiUrl(CHAT_COMPLETIONS_PATH)
const stats = reactive({ ttftMs: null, elapsedMs: null, usage: null, requestId: '' })
const listEl = ref(null)
let controller = null
let timeoutId = 0
let timedOut = false

function scrollToBottom() {
  nextTick(() => {
    if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight
  })
}

function clearChat() {
  messages.value = []
  stats.ttftMs = null
  stats.elapsedMs = null
  stats.usage = null
  stats.requestId = ''
}

function stop() {
  if (controller) controller.abort()
}

async function send() {
  if (streaming.value) return
  if (!config.apiKey) {
    ElMessage.warning('请先填入 API Key(sk-gw-…)')
    return
  }
  if (!config.model) {
    ElMessage.warning('请填入模型名或别名')
    return
  }
  const text = input.value.trim()
  if (!text) return

  messages.value.push({ role: 'user', content: text })
  const history = messages.value
    .filter((m) => !m.error)
    .map((m) => ({ role: m.role, content: m.content }))
  const assistant = reactive({ role: 'assistant', content: '', error: false })
  messages.value.push(assistant)
  input.value = ''
  streaming.value = true
  stats.ttftMs = null
  stats.elapsedMs = null
  stats.usage = null
  const requestId = createRequestId()
  stats.requestId = requestId
  controller = new AbortController()
  timedOut = false
  timeoutId = window.setTimeout(() => {
    timedOut = true
    controller?.abort()
  }, STREAM_TIMEOUT_MS)
  const startedAt = performance.now()
  scrollToBottom()

  try {
    const resp = await fetch(endpoint, {
      method: 'POST',
      headers: {
        Accept: 'text/event-stream',
        'Content-Type': 'application/json',
        Authorization: `Bearer ${config.apiKey}`,
        'X-Request-Id': requestId,
      },
      body: JSON.stringify({
        model: config.model,
        messages: history,
        stream: true,
        stream_options: { include_usage: true },
      }),
      signal: controller.signal,
    })
    stats.requestId = resp.headers.get('X-Request-Id') || requestId

    if (!resp.ok) {
      // 流开始前的错误是普通 JSON(网关语义)
      let msg = `HTTP ${resp.status}`
      try {
        const err = await resp.json()
        msg = err?.error?.message || err?.message || err?.msg || msg
      } catch {
        /* 保留状态码信息 */
      }
      if (resp.status === 401) msg = 'API Key 无效或未授权：' + msg
      assistant.content = msg
      assistant.error = true
      return
    }

    if (!resp.body) {
      throw new Error('浏览器未提供可读取的流式响应体')
    }
    const reader = resp.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const frames = buffer.split(/\r?\n\r?\n/)
      buffer = frames.pop() // 半帧留到下一轮
      for (const frame of frames) {
        for (const line of frame.split(/\r?\n/)) {
          if (!line.startsWith('data:')) continue
          const payload = line.slice(5).trim()
          if (payload === '[DONE]') continue
          let evt
          try {
            evt = JSON.parse(payload)
          } catch {
            continue
          }
          if (evt.error) {
            assistant.error = true
            assistant.content += `\n[已中断] ${evt.error.message || evt.error.code || '流被网关终止'}`
            continue
          }
          if (evt.usage) {
            stats.usage = evt.usage
            continue
          }
          const delta = evt.choices?.[0]?.delta?.content
          if (delta) {
            if (stats.ttftMs === null) stats.ttftMs = Math.round(performance.now() - startedAt)
            assistant.content += delta
            scrollToBottom()
          }
        }
      }
    }
  } catch (e) {
    if (e.name === 'AbortError') {
      assistant.content += timedOut
        ? `\n[已超时] 流式请求超过 ${Math.round(STREAM_TIMEOUT_MS / 1000)} 秒`
        : '\n[已停止]'
    } else {
      assistant.error = true
      assistant.content = '网络错误：' + (e.message || e)
    }
  } finally {
    window.clearTimeout(timeoutId)
    timeoutId = 0
    stats.elapsedMs = Math.round(performance.now() - startedAt)
    streaming.value = false
    controller = null
    scrollToBottom()
  }
}

onBeforeUnmount(stop)
</script>

<template>
  <div class="page playground-page">
    <PageIntro
      index="07"
      eyebrow="Edge lab / streaming verification"
      title="试运行"
      subtitle="用租户 API Key 直连 /v1，验证路由、护栏与流式输出。密钥只保存在当前页面内存。"
    />

    <div class="surface playground-shell rise" style="--i: 1">
      <aside class="playground-config">
        <div class="config-kicker mono"><TerminalSquare :size="14" :stroke-width="1.7" /> REQUEST CONFIG</div>
        <div class="config-block">
          <label class="config-label mono" for="playground-key">01 / API KEY</label>
          <el-input
            id="playground-key"
            v-model="config.apiKey"
            :type="keyVisible ? 'text' : 'password'"
            placeholder="sk-gw-…"
          >
            <template #prefix><KeyRound :size="15" :stroke-width="1.7" /></template>
            <template #suffix>
              <button class="input-action" type="button" :aria-label="keyVisible ? '隐藏 API Key' : '显示 API Key'" @click="keyVisible = !keyVisible">
                <EyeOff v-if="keyVisible" :size="15" :stroke-width="1.7" />
                <Eye v-else :size="15" :stroke-width="1.7" />
              </button>
            </template>
          </el-input>
          <div class="config-hint">仅存内存 / 刷新即失</div>
        </div>
        <div class="config-block">
          <label class="config-label mono" for="playground-model">02 / MODEL OR ALIAS</label>
          <el-input id="playground-model" v-model="config.model" placeholder="default / auto / cheap" />
          <div class="model-chips mono">
            <button v-for="model in ['default', 'auto', 'cheap']" :key="model" type="button" :class="{ active: config.model === model }" @click="config.model = model">{{ model }}</button>
          </div>
        </div>
        <div class="config-divider"></div>
        <div class="config-status">
          <div class="config-status-icon"><Boxes :size="17" :stroke-width="1.7" /></div>
          <div>
            <div class="mono">GATEWAY ENDPOINT</div>
            <strong>{{ endpoint }}</strong>
          </div>
        </div>
        <div class="config-footer mono"><span class="status-dot"></span> SSE CHANNEL READY</div>
      </aside>

      <section class="conversation">
        <div class="conversation-head">
          <div>
            <div class="conversation-title">流式会话</div>
            <div class="conversation-note mono">CHAT COMPLETIONS / REALTIME</div>
          </div>
          <button class="clear-button" type="button" :disabled="streaming" @click="clearChat">
            <Trash2 :size="15" :stroke-width="1.7" />清空
          </button>
        </div>

        <div ref="listEl" class="chat-list">
          <EmptyState v-if="messages.length === 0" :icon="Sparkles" title="等待第一条消息" hint="填入 Key 与模型后，用 Ctrl + Enter 发起流式请求" />
          <div v-for="(m, i) in messages" :key="i" class="bubble-row" :class="m.role">
            <div class="bubble-avatar" :class="m.role">
              <UserRound v-if="m.role === 'user'" :size="15" :stroke-width="1.8" />
              <Bot v-else :size="15" :stroke-width="1.8" />
            </div>
            <div class="bubble" :class="{ error: m.error }">
              <div class="bubble-meta mono">{{ m.role === 'user' ? 'OPERATOR' : 'GATEWAY' }}</div>
              <pre>{{ m.content }}<span v-if="m.role === 'assistant' && streaming && i === messages.length - 1" class="cursor" aria-hidden="true"></span></pre>
            </div>
          </div>
        </div>

        <div class="stats" v-if="stats.elapsedMs !== null || stats.ttftMs !== null">
          <el-tag v-if="stats.ttftMs !== null" type="info" effect="plain"><Timer :size="13" /> 首字 {{ stats.ttftMs }} ms</el-tag>
          <el-tag v-if="stats.elapsedMs !== null" type="info" effect="plain"><Gauge :size="13" /> 总耗时 {{ stats.elapsedMs }} ms</el-tag>
          <el-tag v-if="stats.usage" type="info" effect="plain"><Boxes :size="13" /> Token {{ stats.usage.prompt_tokens }} 入 / {{ stats.usage.completion_tokens }} 出</el-tag>
          <el-tag v-if="stats.requestId" type="info" effect="plain" class="mono">ID {{ stats.requestId }}</el-tag>
        </div>

        <div class="composer">
          <el-input
            v-model="input"
            type="textarea"
            :rows="2"
            resize="none"
            placeholder="输入消息，Ctrl + Enter 发送"
            @keydown.ctrl.enter.prevent="send"
          />
          <el-button v-if="!streaming" type="primary" :disabled="!input.trim()" @click="send">
            <el-icon><SendHorizontal :stroke-width="1.8" /></el-icon>&nbsp;发送
          </el-button>
          <el-button v-else type="warning" @click="stop">
            <el-icon><CircleStop :stroke-width="1.8" /></el-icon>&nbsp;停止
          </el-button>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.playground-shell {
  display: grid;
  grid-template-columns: 270px minmax(0, 1fr);
  height: calc(100vh - 224px);
  min-height: 520px;
  overflow: hidden;
}

.playground-config {
  display: flex;
  flex-direction: column;
  gap: 22px;
  padding: 22px 18px;
  background: rgba(255, 255, 255, 0.018);
  border-right: 1px solid rgba(255, 255, 255, 0.08);
}

.config-kicker {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--accent-lime);
  font-size: 9px;
  letter-spacing: 0.15em;
}

.config-block {
  display: grid;
  gap: 8px;
}

.config-label {
  color: var(--app-text-faint);
  font-size: 9px;
  letter-spacing: 0.13em;
}

.config-hint {
  color: var(--app-text-faint);
  font-size: 10px;
}

.input-action {
  display: grid;
  place-items: center;
  padding: 0;
  color: var(--app-text-faint);
  background: transparent;
  border: 0;
  cursor: pointer;
}

.input-action:hover {
  color: var(--accent-lime);
}

.model-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.model-chips button {
  padding: 5px 7px;
  color: var(--app-text-faint);
  font: inherit;
  font-size: 9px;
  background: transparent;
  border: 1px solid rgba(255, 255, 255, 0.09);
  cursor: pointer;
  transition: color 0.2s var(--swift), border-color 0.2s var(--swift), background 0.2s var(--swift);
}

.model-chips button:hover,
.model-chips button.active {
  color: var(--accent-lime);
  background: rgba(200, 255, 61, 0.06);
  border-color: rgba(200, 255, 61, 0.4);
}

.config-divider {
  height: 1px;
  background: linear-gradient(90deg, rgba(255, 255, 255, 0.14), transparent);
}

.config-status {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--app-text-faint);
  font-size: 9px;
  letter-spacing: 0.1em;
}

.config-status strong {
  display: block;
  margin-top: 5px;
  color: var(--app-text-secondary);
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: 400;
  letter-spacing: 0;
  overflow-wrap: anywhere;
}

.config-status-icon {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  color: var(--accent-cyan);
  border: 1px solid rgba(83, 217, 255, 0.25);
}

.config-footer {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-top: auto;
  color: var(--app-text-faint);
  font-size: 8px;
  letter-spacing: 0.1em;
}

.status-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--accent-lime);
  box-shadow: 0 0 10px var(--accent-lime);
}

.conversation {
  display: flex;
  min-width: 0;
  flex-direction: column;
  padding: 20px 22px 18px;
}

.conversation-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding-bottom: 15px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.conversation-title {
  color: var(--app-text);
  font-size: 15px;
  font-weight: 600;
}

.conversation-note {
  margin-top: 4px;
  color: var(--app-text-faint);
  font-size: 9px;
  letter-spacing: 0.14em;
}

.clear-button {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 7px 9px;
  color: var(--app-text-faint);
  font: inherit;
  font-size: 11px;
  background: transparent;
  border: 1px solid transparent;
  cursor: pointer;
}

.clear-button:hover:not(:disabled) {
  color: var(--accent-pink);
  border-color: rgba(255, 111, 174, 0.3);
}

.chat-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 18px 4px;
}

.bubble-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin: 14px 0;
}

.bubble-row.user {
  flex-direction: row-reverse;
}

.bubble-avatar {
  width: 28px;
  height: 28px;
  display: grid;
  flex: none;
  place-items: center;
  color: var(--accent-cyan);
  border: 1px solid rgba(83, 217, 255, 0.28);
  background: rgba(83, 217, 255, 0.06);
}

.bubble-avatar.user {
  color: #0b0d0b;
  background: var(--accent-lime);
  border-color: var(--accent-lime);
}

.bubble {
  max-width: min(78%, 720px);
  padding: 10px 14px 13px;
  color: var(--app-text);
  background: rgba(255, 255, 255, 0.035);
  border: 1px solid rgba(255, 255, 255, 0.09);
  border-radius: 1px;
}

.bubble-row.user .bubble {
  color: #0b0d0b;
  background: var(--accent-lime);
  border-color: var(--accent-lime);
}

.bubble.error {
  color: #ffd1df;
  background: rgba(255, 111, 174, 0.08);
  border-color: rgba(255, 111, 174, 0.35);
}

.bubble-meta {
  margin-bottom: 6px;
  color: var(--app-text-faint);
  font-size: 8px;
  letter-spacing: 0.14em;
}

.bubble-row.user .bubble-meta {
  color: rgba(11, 13, 11, 0.58);
}

.bubble pre {
  margin: 0;
  color: inherit;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.cursor {
  display: inline-block;
  width: 7px;
  height: 1.05em;
  margin-left: 3px;
  vertical-align: -0.18em;
  background: currentColor;
  animation: blink 1s step-start infinite;
}

.stats {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  padding: 8px 0;
}

.stats .el-tag {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.composer {
  display: flex;
  gap: 10px;
  align-items: flex-end;
  padding-top: 12px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
}

.composer .el-button {
  height: 54px;
  min-width: 94px;
}

@keyframes blink {
  50% { opacity: 0; }
}

@media (max-width: 850px) {
  .playground-shell {
    grid-template-columns: 1fr;
    height: auto;
    min-height: 680px;
  }

  .playground-config {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 14px;
    border-right: 0;
    border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  }

  .config-kicker,
  .config-divider,
  .config-footer {
    grid-column: 1 / -1;
  }

  .config-status {
    margin-top: auto;
  }
}

@media (max-width: 560px) {
  .playground-config {
    display: flex;
  }

  .conversation {
    padding: 15px 12px 12px;
  }

  .bubble {
    max-width: 84%;
  }
}
</style>
