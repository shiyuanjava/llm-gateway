<script setup>
// 网关 Logo:一个入口经中央核心节点路由到多个上游节点。
// 连线上有沿路径流动的数据脉冲,核心有呼吸光晕 —— 动态科技风。
// 尊重 prefers-reduced-motion:关闭动效时静态呈现,信息完整。
defineProps({
  size: { type: Number, default: 40 },
  // 是否播放数据流/呼吸动画;可在低性能或纯展示场景关闭
  animated: { type: Boolean, default: true },
})

// 每个实例递增一个前缀,避免同页多个 logo 的 <defs> id 冲突
const uid = `gw-${(window.__gwLogoSeq = (window.__gwLogoSeq || 0) + 1)}`
</script>

<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 48 48"
    fill="none"
    role="img"
    aria-label="LLM Gateway 标志"
    class="gw-logo"
    :class="{ 'gw-static': !animated }"
  >
    <defs>
      <linearGradient :id="`${uid}-core`" x1="0" y1="0" x2="1" y2="1">
        <stop offset="0%" stop-color="#818cf8" />
        <stop offset="55%" stop-color="#4f46e5" />
        <stop offset="100%" stop-color="#8b5cf6" />
      </linearGradient>
      <linearGradient :id="`${uid}-edge`" x1="0" y1="0" x2="1" y2="0">
        <stop offset="0%" stop-color="#4f46e5" stop-opacity="0.25" />
        <stop offset="100%" stop-color="#22d3ee" stop-opacity="0.9" />
      </linearGradient>
      <radialGradient :id="`${uid}-glow`" cx="50%" cy="50%" r="50%">
        <stop offset="0%" stop-color="#818cf8" stop-opacity="0.55" />
        <stop offset="100%" stop-color="#818cf8" stop-opacity="0" />
      </radialGradient>
    </defs>

    <!-- 核心呼吸光晕 -->
    <circle class="gw-glow" cx="19" cy="24" r="15" :fill="`url(#${uid}-glow)`" />

    <!-- 路由连线:入口 → 核心 → 三个上游节点 -->
    <g :stroke="`url(#${uid}-edge)`" stroke-width="1.6" stroke-linecap="round" fill="none">
      <path d="M4 24 H19" />
      <path d="M19 24 C30 24 32 12 40 12" />
      <path d="M19 24 H40" />
      <path d="M19 24 C30 24 32 36 40 36" />
    </g>

    <!-- 沿连线流动的数据脉冲 -->
    <g class="gw-pulses" fill="#a5f3fc">
      <circle class="gw-pulse gw-p1" r="1.7" cx="0" cy="0" />
      <circle class="gw-pulse gw-p2" r="1.7" cx="0" cy="0" />
      <circle class="gw-pulse gw-p3" r="1.7" cx="0" cy="0" />
    </g>

    <!-- 上游节点 -->
    <g class="gw-nodes">
      <circle cx="40" cy="12" r="2.8" fill="#22d3ee" />
      <circle cx="40" cy="24" r="2.8" fill="#22d3ee" />
      <circle cx="40" cy="36" r="2.8" fill="#22d3ee" />
    </g>

    <!-- 入口节点 -->
    <circle cx="4" cy="24" r="2.4" fill="#818cf8" />

    <!-- 中央网关核心(六边形) -->
    <path
      class="gw-core"
      d="M19 15.5 L26.4 19.75 V28.25 L19 32.5 L11.6 28.25 V19.75 Z"
      :fill="`url(#${uid}-core)`"
      stroke="#c7d2fe"
      stroke-width="0.8"
      stroke-linejoin="round"
    />
    <!-- 核心内部节点 -->
    <circle cx="19" cy="24" r="2.6" fill="#0b1020" fill-opacity="0.35" />
    <circle cx="19" cy="24" r="1.3" fill="#e0e7ff" />
  </svg>
</template>

<style scoped>
.gw-logo {
  display: block;
  overflow: visible;
}

/* 核心呼吸 */
.gw-core {
  transform-box: fill-box;
  transform-origin: center;
  animation: gw-breathe 3.2s ease-in-out infinite;
}
.gw-glow {
  transform-box: fill-box;
  transform-origin: center;
  animation: gw-glow-pulse 3.2s ease-in-out infinite;
}

/* 数据脉冲沿三条路径流动(offset-path 复用连线几何) */
.gw-pulse {
  offset-rotate: 0deg;
  animation: gw-flow 2.4s linear infinite;
}
.gw-p1 {
  offset-path: path('M19 24 C30 24 32 12 40 12');
  animation-delay: 0s;
}
.gw-p2 {
  offset-path: path('M19 24 H40');
  animation-delay: 0.8s;
}
.gw-p3 {
  offset-path: path('M19 24 C30 24 32 36 40 36');
  animation-delay: 1.6s;
}

@keyframes gw-flow {
  0% {
    offset-distance: 0%;
    opacity: 0;
  }
  15% {
    opacity: 1;
  }
  85% {
    opacity: 1;
  }
  100% {
    offset-distance: 100%;
    opacity: 0;
  }
}
@keyframes gw-breathe {
  0%,
  100% {
    transform: scale(1);
  }
  50% {
    transform: scale(1.06);
  }
}
@keyframes gw-glow-pulse {
  0%,
  100% {
    opacity: 0.5;
    transform: scale(0.92);
  }
  50% {
    opacity: 0.9;
    transform: scale(1.08);
  }
}

/* 关闭动效:静态但完整 */
.gw-static .gw-core,
.gw-static .gw-glow {
  animation: none;
}
.gw-static .gw-pulse {
  display: none;
}
@media (prefers-reduced-motion: reduce) {
  .gw-core,
  .gw-glow {
    animation: none;
  }
  .gw-pulse {
    display: none;
  }
}
</style>
