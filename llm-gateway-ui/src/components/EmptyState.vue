<script setup>
import { Orbit } from 'lucide-vue-next'

defineProps({
  icon: { type: [Object, Function], default: () => Orbit },
  title: { type: String, required: true },
  hint: { type: String, default: '' },
})
</script>

<template>
  <div class="empty-state">
    <div class="empty-orbit" aria-hidden="true">
      <span></span>
      <div class="empty-icon">
        <component :is="icon" :size="23" :stroke-width="1.6" />
      </div>
    </div>
    <div class="empty-copy">
      <div class="empty-title">{{ title }}</div>
      <div v-if="hint" class="empty-hint">{{ hint }}</div>
    </div>
    <slot />
  </div>
</template>

<style scoped>
.empty-state {
  display: grid;
  place-items: center;
  gap: 14px;
  padding: 58px 18px;
  text-align: center;
}

.empty-orbit {
  position: relative;
  width: 72px;
  height: 72px;
  display: grid;
  place-items: center;
}

.empty-orbit::before,
.empty-orbit span {
  content: '';
  position: absolute;
  inset: 2px;
  border: 1px solid rgba(200, 255, 61, 0.18);
  border-radius: 50%;
}

.empty-orbit span {
  inset: 10px -3px;
  border-color: rgba(124, 92, 255, 0.34);
  transform: rotate(58deg);
}

.empty-icon {
  position: relative;
  z-index: 1;
  width: 46px;
  height: 46px;
  display: grid;
  place-items: center;
  color: var(--accent-lime);
  background: #0d0f0f;
  border: 1px solid var(--app-border-strong);
  box-shadow: 0 0 28px rgba(124, 92, 255, 0.2);
}

.empty-title {
  color: var(--app-text);
  font-size: 14px;
  font-weight: 600;
}

.empty-hint {
  max-width: 360px;
  margin-top: 5px;
  color: var(--app-text-faint);
  font-size: 12px;
  line-height: 1.7;
}
</style>
