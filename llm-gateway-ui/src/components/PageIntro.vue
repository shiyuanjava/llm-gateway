<script setup>
defineProps({
  index: { type: String, required: true },
  eyebrow: { type: String, required: true },
  title: { type: String, required: true },
  subtitle: { type: String, default: '' },
})
</script>

<template>
  <header class="page-intro rise">
    <div class="intro-index" aria-hidden="true">{{ index }}</div>
    <div class="intro-copy">
      <div class="intro-eyebrow mono">
        <span class="intro-signal" aria-hidden="true"></span>
        {{ eyebrow }}
      </div>
      <h1 class="intro-title">{{ title }}</h1>
      <p v-if="subtitle" class="intro-subtitle">{{ subtitle }}</p>
    </div>
    <div v-if="$slots.actions" class="intro-actions">
      <slot name="actions" />
    </div>
    <div class="intro-axis" aria-hidden="true">
      <span></span>
      <i></i>
    </div>
  </header>
</template>

<style scoped>
.page-intro {
  position: relative;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: end;
  gap: clamp(18px, 2.4vw, 38px);
  min-height: 150px;
  padding: 22px 0 30px;
  margin-bottom: 22px;
}

.intro-index {
  align-self: start;
  margin-top: 18px;
  font-family: var(--font-display);
  font-size: clamp(56px, 6vw, 92px);
  font-weight: 700;
  line-height: 0.72;
  letter-spacing: -0.08em;
  color: transparent;
  -webkit-text-stroke: 1px rgba(236, 240, 222, 0.19);
  user-select: none;
}

.intro-copy {
  min-width: 0;
}

.intro-eyebrow {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
  color: var(--accent-lime);
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.22em;
  text-transform: uppercase;
}

.intro-signal {
  width: 18px;
  height: 1px;
  background: currentColor;
  box-shadow: 8px 0 12px currentColor;
}

.intro-title {
  max-width: 900px;
  margin: 0;
  font-family: var(--font-display);
  font-size: clamp(38px, 4.8vw, 68px);
  font-weight: 600;
  line-height: 0.96;
  letter-spacing: -0.065em;
  color: var(--app-text);
}

.intro-subtitle {
  max-width: 680px;
  margin: 14px 0 0;
  color: var(--app-text-secondary);
  font-size: 13px;
  line-height: 1.75;
}

.intro-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  padding-bottom: 5px;
}

.intro-axis {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  display: flex;
  align-items: center;
}

.intro-axis span {
  flex: 1;
  height: 1px;
  background: linear-gradient(90deg, rgba(255, 255, 255, 0.16), rgba(255, 255, 255, 0.03));
}

.intro-axis i {
  width: 7px;
  height: 7px;
  border: 1px solid var(--accent-lime);
  transform: rotate(45deg);
  box-shadow: 0 0 14px rgba(200, 255, 61, 0.45);
}

@media (max-width: 760px) {
  .page-intro {
    grid-template-columns: 1fr auto;
    min-height: 0;
    padding-top: 12px;
  }

  .intro-index {
    display: none;
  }

  .intro-title {
    font-size: clamp(34px, 10vw, 48px);
  }
}

@media (max-width: 560px) {
  .page-intro {
    grid-template-columns: 1fr;
    gap: 18px;
  }

  .intro-actions {
    justify-content: flex-start;
    padding-bottom: 0;
  }
}
</style>
