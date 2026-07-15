<script setup>
import { nextTick, onBeforeUnmount, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Promotion, VideoPause, Delete } from '@element-plus/icons-vue'

/**
 * 试运行:管理员直连 /v1/chat/completions 验证网关(含 SSE 流式)。
 * - API Key 只存组件内存,刷新即失,绝不写 localStorage
 * - axios 不支持流式读取,这里用原生 fetch + ReadableStream 解析 SSE
 */
const config = reactive({ apiKey: '', model: 'default' })
const input = ref('')
const messages = ref([]) // { role: 'user'|'assistant', content, error? }
const streaming = ref(false)
const stats = reactive({ ttftMs: null, elapsedMs: null, usage: null })
const listEl = ref(null)
let controller = null

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
  controller = new AbortController()
  const startedAt = performance.now()
  scrollToBottom()

  try {
    const resp = await fetch('/v1/chat/completions', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${config.apiKey}` },
      body: JSON.stringify({
        model: config.model,
        messages: history,
        stream: true,
        stream_options: { include_usage: true },
      }),
      signal: controller.signal,
    })

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

    const reader = resp.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const frames = buffer.split('\n\n')
      buffer = frames.pop() // 半帧留到下一轮
      for (const frame of frames) {
        for (const line of frame.split('\n')) {
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
      assistant.content += '\n[已停止]'
    } else {
      assistant.error = true
      assistant.content = '网络错误：' + (e.message || e)
    }
  } finally {
    stats.elapsedMs = Math.round(performance.now() - startedAt)
    streaming.value = false
    controller = null
    scrollToBottom()
  }
}

onBeforeUnmount(stop)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">试运行</h2>
        <div class="page-subtitle">用租户 API Key 直连 /v1,验证路由、护栏与流式打字机效果</div>
      </div>
    </div>

    <div class="surface playground">
      <div class="toolbar">
        <el-input
          v-model="config.apiKey"
          type="password"
          show-password
          placeholder="API Key(sk-gw-…,仅存内存)"
          style="width: 280px"
        />
        <el-input
          v-model="config.model"
          placeholder="模型或别名:default / auto / cheap / mock-small"
          style="width: 260px"
        />
        <div class="spacer"></div>
        <el-button :disabled="streaming" @click="clearChat"
          ><el-icon><Delete /></el-icon>&nbsp;清空对话</el-button
        >
      </div>

      <div ref="listEl" class="chat-list">
        <el-empty v-if="messages.length === 0" description="填好 Key 与模型,发一条消息试试" />
        <div v-for="(m, i) in messages" :key="i" class="bubble-row" :class="m.role">
          <div class="bubble" :class="{ error: m.error }">
            <pre>{{ m.content }}<span v-if="m.role === 'assistant' && streaming && i === messages.length - 1" class="cursor">▌</span></pre>
          </div>
        </div>
      </div>

      <div class="stats" v-if="stats.elapsedMs !== null || stats.ttftMs !== null">
        <el-tag v-if="stats.ttftMs !== null" type="info" effect="plain"
          >首字 {{ stats.ttftMs }} ms</el-tag
        >
        <el-tag v-if="stats.elapsedMs !== null" type="info" effect="plain"
          >总耗时 {{ stats.elapsedMs }} ms</el-tag
        >
        <el-tag v-if="stats.usage" type="info" effect="plain">
          Token {{ stats.usage.prompt_tokens }} 入 / {{ stats.usage.completion_tokens }} 出
        </el-tag>
      </div>

      <div class="composer">
        <el-input
          v-model="input"
          type="textarea"
          :rows="2"
          resize="none"
          placeholder="输入消息,Ctrl+Enter 发送"
          @keydown.ctrl.enter.prevent="send"
        />
        <el-button v-if="!streaming" type="primary" :disabled="!input.trim()" @click="send">
          <el-icon><Promotion /></el-icon>&nbsp;发送
        </el-button>
        <el-button v-else type="warning" @click="stop">
          <el-icon><VideoPause /></el-icon>&nbsp;停止
        </el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.playground {
  padding: 16px;
  display: flex;
  flex-direction: column;
  height: calc(100vh - 170px);
}
.chat-list {
  flex: 1;
  overflow-y: auto;
  padding: 12px 4px;
}
.bubble-row {
  display: flex;
  margin: 10px 0;
}
.bubble-row.user {
  justify-content: flex-end;
}
.bubble {
  max-width: 76%;
  padding: 10px 14px;
  border-radius: 12px;
  background: var(--el-fill-color-light);
  font-size: 14px;
  line-height: 1.6;
}
.bubble-row.user .bubble {
  background: var(--el-color-primary);
  color: #fff;
}
.bubble.error {
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
}
.bubble pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
}
.cursor {
  animation: blink 1s step-start infinite;
}
@keyframes blink {
  50% {
    opacity: 0;
  }
}
.stats {
  display: flex;
  gap: 8px;
  padding: 8px 0;
}
.composer {
  display: flex;
  gap: 12px;
  align-items: flex-end;
  padding-top: 8px;
  border-top: 1px solid var(--app-border);
}
.composer .el-button {
  height: 54px;
}
</style>
