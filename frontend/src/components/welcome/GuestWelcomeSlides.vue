<template>
  <section class="gs gs--hero" aria-label="第 1 页，品牌欢迎">
    <div class="gs__hero">
      <p class="gs__eyebrow">黄河交通学院 · 校园社区</p>
      <h1>同学，进来逛逛</h1>
      <p class="gs__sub">动态、集市、资料，游客也能先看</p>

      <div class="gs__stage" aria-hidden="true">
        <figure class="gs__piece gs__piece--feed">
          <img src="/welcome/feed.jpg" alt="">
        </figure>
        <figure class="gs__piece gs__piece--market">
          <img src="/welcome/market.jpg" alt="">
        </figure>
        <div class="gs__piece gs__piece--file">
          <span class="gs__pdf">PDF</span>
          <span class="gs__fmeta">
            <b>数据结构复习提纲.pdf</b>
            <span>3.2 MB · 已过审</span>
          </span>
          <span class="gs__fdl">下载</span>
        </div>
      </div>
    </div>
  </section>

  <section class="gs" aria-label="第 2 页，校园动态">
    <div class="gs__copy">
      <h2>看看同学在聊什么</h2>
      <p>同校动态、话题和评论，游客也能先翻。</p>
    </div>
    <div class="gs__shot">
      <img src="/welcome/feed.jpg" alt="友声校园动态页演示截图：晚霞照片、校运会摄影招募和校园卡招领">
    </div>
    <div class="gs__chips"><span>动态</span><span>话题</span><span>评论</span></div>
  </section>

  <section class="gs" aria-label="第 3 页，集市说明">
    <div class="gs__copy">
      <h2>集市，不只是买东西</h2>
      <p>把需求发出来，同校同学更快接上。</p>
    </div>
    <div class="gs__shot">
      <img src="/welcome/market.jpg" alt="友声集市页演示截图：拼车出行、跑腿代办、学习搭子、游戏组队和运动约伴">
    </div>
    <div class="gs__flow"><span>发需求</span><i>→</i><span>同校同学看到</span><i>→</i><span>留言或私信</span></div>
    <p class="gs__note">不是只有买卖：有需求就发一条，让同校同学找到你。</p>
  </section>

  <section class="gs" aria-label="第 4 页，学习文件">
    <div class="gs__copy">
      <h2>课件资料，随用随取</h2>
      <p>游客也能先看、先下载；上传要登录。</p>
    </div>
    <div class="gs__flist">
      <div class="gs__frow">
        <span class="gs__ext">PDF</span>
        <span class="gs__fmeta"><b>数据结构复习提纲.pdf</b><span>3.2 MB · 已过审</span></span>
        <span class="gs__dl">下载</span>
      </div>
      <div class="gs__frow">
        <span class="gs__ext gs__ext--doc">DOC</span>
        <span class="gs__fmeta"><b>英语四级高频词.docx</b><span>420 KB · 已过审</span></span>
        <span class="gs__dl">下载</span>
      </div>
      <div class="gs__frow">
        <span class="gs__ext gs__ext--zip">ZIP</span>
        <span class="gs__fmeta"><b>实验报告模板.zip</b><span>1.1 MB · 已过审</span></span>
        <span class="gs__dl">下载</span>
      </div>
    </div>
    <p class="gs__note">登录后可以上传资料、参与讨论。</p>
  </section>
</template>

<style scoped>
.gs {
  box-sizing: border-box;
  flex: 0 0 100%;
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 8px 16px 4px;
  overflow: hidden;
  scroll-snap-align: start;
  scroll-snap-stop: always;
}
.gs h1,
.gs h2 {
  margin: 0;
  font-size: 20px;
  line-height: 1.35;
  font-weight: 500;
  letter-spacing: 0.01em;
  color: var(--ys-fg);
}
.gs p {
  margin: 2px 0 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--ys-muted);
}
.gs--hero {
  align-items: center;
  justify-content: center;
  text-align: center;
  padding-bottom: 8px;
}
.gs__hero {
  width: 100%;
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 20px 18px;
  border: 1px solid var(--ys-border-soft);
  border-radius: 18px;
  background:
    radial-gradient(120% 80% at 100% 0%, var(--ys-accent-soft) 0%, transparent 60%),
    radial-gradient(100% 80% at 0% 100%, var(--ys-accent-2-soft) 0%, transparent 68%),
    var(--ys-surface);
}
.gs--hero h1 {
  margin-top: 10px;
  font-size: 25px;
}
.gs__eyebrow {
  margin: 0;
  padding: 3px 10px;
  font-size: 11px;
  letter-spacing: 0.06em;
  color: var(--ys-fg-2);
  background: var(--ys-surface);
  border: 1px solid var(--ys-border);
  border-radius: 999px;
}
.gs__sub {
  margin-top: 7px;
  font-size: 13px;
  color: var(--ys-fg-2);
}
.gs__stage {
  position: relative;
  width: 100%;
  aspect-ratio: 1.04;
  margin-top: 18px;
  pointer-events: none;
}
.gs__stage::before {
  content: '';
  position: absolute;
  inset: 8% 0 4%;
  border-radius: 50%;
  background: radial-gradient(closest-side, var(--ys-accent-soft), transparent 76%);
}
.gs__piece {
  position: absolute;
  margin: 0;
  border: 1px solid var(--ys-border);
  border-radius: 14px;
  background: var(--ys-surface);
  box-shadow: 0 10px 22px var(--ys-overlay);
  overflow: hidden;
}
.gs__piece img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: top center;
}
.gs__piece--feed {
  left: 0;
  top: 0;
  z-index: 1;
  width: 52%;
  aspect-ratio: 0.58;
  transform: rotate(-5deg);
}
.gs__piece--market {
  right: 0;
  top: 4%;
  z-index: 2;
  width: 52%;
  aspect-ratio: 0.57;
  transform: rotate(5deg);
}
.gs__piece--file {
  left: 50%;
  bottom: 0;
  z-index: 3;
  width: 86%;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 12px;
  text-align: left;
  transform: translateX(-50%) rotate(-1.5deg);
}
.gs__pdf {
  flex: none;
  width: 30px;
  height: 30px;
  border-radius: 8px;
  display: grid;
  place-items: center;
  font-size: 9.5px;
  font-weight: 600;
  color: var(--ys-accent-2);
  background: var(--ys-accent-2-soft);
}
.gs__fmeta {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  line-height: 1.35;
}
.gs__fmeta b {
  font-size: 12px;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.gs__fmeta span {
  font-size: 10px;
  color: var(--ys-muted);
}
.gs__fdl {
  flex: none;
  font-size: 10.5px;
  color: var(--ys-accent);
  border: 1px solid var(--ys-accent);
  border-radius: 999px;
  padding: 2px 9px;
}
.gs__chips span {
  font-size: 12px;
  color: var(--ys-fg-2);
  background: var(--ys-surface);
  border: 1px solid var(--ys-border);
  border-radius: 999px;
  padding: 2px 10px;
}
.gs__shot {
  position: relative;
  flex: 1;
  min-height: 0;
  border: 1px solid var(--ys-border);
  border-radius: 12px;
  overflow: hidden;
  background: var(--ys-surface);
}
.gs__shot img {
  position: absolute;
  inset: 0;
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: top center;
}
.gs__chips {
  display: flex;
  gap: 6px;
}
.gs__flow {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  font-size: 12px;
  color: var(--ys-fg-2);
}
.gs__flow i {
  font-style: normal;
  color: var(--ys-accent);
}
.gs__note {
  font-size: 12px;
  color: var(--ys-fg-2);
}
.gs__flist {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.gs__frow {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px;
  background: var(--ys-surface);
  border: 1px solid var(--ys-border);
  border-radius: 12px;
}
.gs__ext {
  flex: none;
  width: 38px;
  height: 38px;
  border-radius: 9px;
  display: grid;
  place-items: center;
  font-size: 10px;
  font-weight: 600;
  color: var(--ys-accent-2);
  background: var(--ys-accent-2-soft);
}
.gs__ext--doc {
  color: var(--ys-accent);
  background: var(--ys-accent-soft);
}
.gs__ext--zip {
  color: var(--ys-muted);
  background: var(--ys-surface-2);
}
.gs__fmeta {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  line-height: 1.4;
}
.gs__fmeta b {
  font-size: 13px;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.gs__fmeta span {
  font-size: 11px;
  color: var(--ys-muted);
}
.gs__dl {
  flex: none;
  font-size: 11px;
  color: var(--ys-accent);
  border: 1px solid var(--ys-accent);
  border-radius: 999px;
  padding: 2px 9px;
}
</style>
