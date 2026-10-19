<template>
  <div class="sub-inner">
    <!-- 深色模式:与设置首页/桌面顶栏共享 useTheme 单例 -->
    <el-card class="sgroup">
      <div class="srow">
        <span class="sicon"><el-icon :size="18"><Moon /></el-icon></span>
        <span class="stext">
          <span class="slabel">深色模式</span>
          <span class="sdesc">夜间刷校园动态更护眼,切换即时生效,刷新不丢</span>
        </span>
        <el-switch
          :model-value="isDark"
          aria-label="深色模式开关"
          style="--el-switch-on-color: var(--ys-accent)"
          @change="toggleTheme"
        />
      </div>
    </el-card>

    <!-- 字体大小:三档,useFontScale 落 <html data-font-scale>,design-tokens 覆写 --ys-text-* -->
    <el-card class="sgroup">
      <div class="srow srow--static">
        <span class="sicon"><el-icon :size="18"><Sort /></el-icon></span>
        <span class="stext">
          <span class="slabel">字体大小</span>
          <span class="sdesc">调整全站文字大小,即时生效</span>
        </span>
      </div>
      <div class="fseg" role="radiogroup" aria-label="字体大小">
        <button
          v-for="o in options"
          :key="o.value"
          type="button"
          class="fopt"
          :class="{ on: scale === o.value }"
          role="radio"
          :aria-checked="scale === o.value"
          @click="setScale(o.value)"
        >
          {{ o.label }}
        </button>
      </div>
    </el-card>
  </div>
</template>

<script setup>
/**
 * 通用设置(/settings/general):深色模式 + 字体大小三档。
 * 字号缩放走 useFontScale(单例 + localStorage + <html data-font-scale>),
 * 真正改字号的是 design-tokens.css 尾部的两个覆盖块 —— 本页零样式 hack。
 */
import { Moon, Sort } from '@element-plus/icons-vue'
import { useTheme } from '@/composables/useTheme'
import { useFontScale } from '@/composables/useFontScale'

const { isDark, toggle: toggleTheme } = useTheme()
const { scale, setScale, options } = useFontScale()
</script>

<style scoped src="./SettingsSub.css"></style>
