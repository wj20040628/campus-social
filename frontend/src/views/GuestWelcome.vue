<template>
  <div class="gw">
    <div class="gw__frame">
      <header class="gw__top">
        <div class="gw__brand"><span class="gw__seal">友</span><b>友声</b></div>
        <div class="gw__top-actions">
          <button type="button" class="gw__register" @click="goRegister">注册</button>
          <button type="button" class="gw__skip" @click="enterHome">跳过</button>
        </div>
      </header>

      <div ref="track" class="gw__track" @scroll.passive="onScroll">
        <GuestWelcomeSlides />
      </div>

      <button type="button" class="gw__arrow gw__arrow--prev" aria-label="上一页" :disabled="index === 0" @click="goTo(index - 1)">‹</button>
      <button type="button" class="gw__arrow gw__arrow--next" aria-label="下一页" :disabled="index === last" @click="goTo(index + 1)">›</button>

      <footer class="gw__foot">
        <div class="gw__dots" aria-label="页码">
          <button
            v-for="i in total"
            :key="i"
            type="button"
            class="gw__dot"
            :class="{ 'is-on': index === i - 1 }"
            :aria-label="`第 ${i} 页`"
            @click="goTo(i - 1)"
          ></button>
        </div>
        <div class="gw__action">
          <button v-if="index === last" type="button" class="gw__cta" @click="enterHome">进入首页</button>
          <div class="gw__links">
            <button type="button" class="gw__login" @click="goLogin">已有账号，去登录</button>
          </div>
        </div>
        <p class="gw__minor">学生自建 · 非官方平台</p>
      </footer>
    </div>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import GuestWelcomeSlides from '@/components/welcome/GuestWelcomeSlides.vue'

const total = 4
const last = total - 1
const index = ref(0)
const track = ref(null)
const router = useRouter()
const userStore = useUserStore()

let prevOverflow = ''

function onScroll() {
  const el = track.value
  if (!el || !el.clientWidth) return
  const next = Math.round(el.scrollLeft / el.clientWidth)
  if (next >= 0 && next <= last) index.value = next
}

function goTo(target) {
  const el = track.value
  if (!el) return
  const i = Math.min(Math.max(target, 0), last)
  el.scrollTo({ left: i * el.clientWidth, behavior: 'smooth' })
}

function enterHome() {
  userStore.enterGuest()
  router.replace('/')
}

function goLogin() {
  router.push('/login')
}

function goRegister() {
  router.push('/login/register')
}

function onKey(e) {
  if (e.key === 'ArrowLeft') goTo(index.value - 1)
  if (e.key === 'ArrowRight') goTo(index.value + 1)
}

onMounted(() => {
  prevOverflow = document.body.style.overflow
  document.body.style.overflow = 'hidden'
  window.addEventListener('keydown', onKey)
})

onBeforeUnmount(() => {
  document.body.style.overflow = prevOverflow
  window.removeEventListener('keydown', onKey)
})
</script>

<style scoped>
.gw {
  position: fixed;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background: var(--ys-bg);
  color: var(--ys-fg);
  font-family: var(--ys-font-body);
}
.gw__frame {
  position: relative;
  display: flex;
  flex-direction: column;
  width: 100%;
  max-width: 430px;
  height: 100vh;
  height: 100dvh;
  overflow: hidden;
  background: var(--ys-bg);
}
@media (min-width: 768px) {
  .gw__frame {
    height: min(100dvh, 860px);
    border: 1px solid var(--ys-border);
    border-radius: 24px;
    box-shadow: 0 18px 48px var(--ys-overlay);
  }
}
.gw__top {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px 6px;
}
.gw__brand {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  letter-spacing: 0.03em;
}
.gw__seal {
  width: 26px;
  height: 26px;
  border-radius: 8px;
  display: grid;
  place-items: center;
  color: #fff;
  font-size: 13px;
  font-weight: 500;
  background: linear-gradient(145deg, var(--ys-accent), var(--ys-accent-hover));
}
.gw__top-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}
.gw__register {
  border: 0;
  border-radius: 999px;
  background: var(--ys-accent);
  color: var(--ys-accent-on);
  font-size: 13px;
  padding: 6px 14px;
  cursor: pointer;
}
.gw__register:hover {
  background: var(--ys-accent-hover);
}
.gw__skip {
  border: 0;
  background: transparent;
  color: var(--ys-muted);
  font-size: 13px;
  padding: 6px;
  cursor: pointer;
}
.gw__track {
  flex: 1;
  min-height: 0;
  display: flex;
  overflow-x: auto;
  overflow-y: hidden;
  scroll-snap-type: x mandatory;
  overscroll-behavior-x: contain;
  scrollbar-width: none;
}
.gw__track::-webkit-scrollbar {
  display: none;
}
.gw__arrow {
  display: none;
}
@media (min-width: 768px) {
  .gw__arrow {
    display: grid;
    place-items: center;
    position: absolute;
    top: 50%;
    transform: translateY(-50%);
    z-index: 2;
    width: 32px;
    height: 32px;
    border: 1px solid var(--ys-border);
    border-radius: 50%;
    background: var(--ys-surface);
    color: var(--ys-fg-2);
    font-size: 18px;
    line-height: 1;
    cursor: pointer;
  }
  .gw__arrow:disabled {
    opacity: 0.35;
    cursor: default;
  }
  .gw__arrow--prev {
    left: 6px;
  }
  .gw__arrow--next {
    right: 6px;
  }
}
.gw__foot {
  flex: none;
  padding: 0 16px max(10px, env(safe-area-inset-bottom));
  text-align: center;
}
.gw__dots {
  display: flex;
  justify-content: center;
  gap: 6px;
  padding: 5px 0 8px;
}
.gw__dot {
  width: 6px;
  height: 6px;
  padding: 0;
  border: 0;
  border-radius: 999px;
  background: var(--ys-border);
  cursor: pointer;
}
.gw__dot.is-on {
  width: 16px;
  background: var(--ys-accent);
}
.gw__action {
  min-height: 66px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
}
.gw__cta {
  width: 100%;
  max-width: 320px;
  height: 40px;
  border: 0;
  border-radius: 12px;
  background: var(--ys-accent);
  color: var(--ys-accent-on);
  font-size: 15px;
  font-weight: 500;
  cursor: pointer;
}
.gw__cta:hover {
  background: var(--ys-accent-hover);
}
.gw__links {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
}
.gw__login {
  border: 0;
  background: transparent;
  color: var(--ys-accent);
  font-size: 13px;
  padding: 3px;
  cursor: pointer;
}
.gw__minor {
  margin: 2px 0 0;
  font-size: 11px;
  color: var(--ys-muted);
}
</style>
