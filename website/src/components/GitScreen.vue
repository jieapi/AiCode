<template>
<div class="ac-subpage" id="ac-page-git">
<!-- Git Top Bar -->
<header class="ac-subbar">
<div class="ac-subbar__left">
<button type="button" class="ac-icon-btn" aria-label="返回" @click="$emit('back')">
  <svg viewBox="0 0 24 24" width="21" height="21" fill="none" stroke="currentColor" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round"><line x1="19" y1="12" x2="5" y2="12"></line><polyline points="12 19 5 12 12 5"></polyline></svg>
</button>
<span class="ac-subbar__title">Git</span>
</div>
<div class="ac-subbar__right">
<span class="ac-subbar__icon">
  <svg viewBox="0 0 24 24" width="19" height="19" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 2l-2 2m-7.61 7.61a5.5 5.5 0 1 1-7.778 7.778 5.5 5.5 0 0 1 7.777-7.777zm0 0L15.5 7.5m0 0l3 3L22 7l-3-3m-3.5 3.5L19 4"></path></svg>
</span>
<span class="ac-subbar__icon">
  <svg viewBox="0 0 24 24" width="19" height="19" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"></polyline><polyline points="1 20 1 14 7 14"></polyline><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"></path></svg>
</span>
</div>
</header>

<!-- Git Body -->
<div class="ac-git-body">
<!-- Status Summary Card -->
<div class="ac-git-card">
<div class="ac-git-card__head">
  <svg class="ac-git-card__branch-icon" viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
    <line x1="6" y1="3" x2="6" y2="15"></line>
    <circle cx="18" cy="6" r="3"></circle>
    <circle cx="6" cy="18" r="3"></circle>
    <path d="M18 9a9 9 0 0 1-9 9"></path>
  </svg>
  <div class="ac-git-card__meta">
    <div class="ac-git-card__small">工作区干净</div>
    <div class="ac-git-card__main">No commits yet on main</div>
    <div class="ac-git-card__small">未设置上游</div>
  </div>
</div>

<div class="ac-git-counts">
  <div class="ac-git-count-box">
    <span class="ac-git-count-box__num is-green">0</span>
    <span class="ac-git-count-box__label">已暂存</span>
  </div>
  <div class="ac-git-count-box">
    <span class="ac-git-count-box__num is-amber">0</span>
    <span class="ac-git-count-box__label">已修改</span>
  </div>
  <div class="ac-git-count-box">
    <span class="ac-git-count-box__num is-gray">0</span>
    <span class="ac-git-count-box__label">未跟踪</span>
  </div>
</div>
</div>

<!-- Commit Button -->
<div class="ac-git-commit-btn">
<svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"></polyline></svg>
<span>提交更改</span>
</div>

<div class="ac-git-sign-hint">尚未配置提交署名，请在「凭据与署名」页填写用户名和邮箱</div>

<!-- 4 Action Pills -->
<div class="ac-git-actions">
<div class="ac-git-action is-disabled">
  <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"></line><line x1="5" y1="12" x2="19" y2="12"></line></svg>
  <span>暂存全部</span>
</div>
<div class="ac-git-action is-enabled">
  <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2"><polyline points="21 8 21 21 3 21 3 8"></polyline><rect x="1" y="3" width="22" height="5"></rect><line x1="10" y1="12" x2="14" y2="12"></line></svg>
  <span>储藏</span>
</div>
<div class="ac-git-action is-disabled">
  <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2"><polyline points="8 17 12 21 16 17"></polyline><line x1="12" y1="12" x2="12" y2="21"></line><path d="M20.88 18.09A5 5 0 0 0 18 9h-1.26A8 8 0 1 0 3 16.29"></path></svg>
  <span>拉取</span>
</div>
<div class="ac-git-action is-disabled">
  <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2"><polyline points="16 16 12 12 8 16"></polyline><line x1="12" y1="12" x2="12" y2="21"></line><path d="M20.39 18.39A5 5 0 0 0 18 9h-1.26A8 8 0 1 0 3 16.3"></path></svg>
  <span>推送</span>
</div>
</div>

<!-- Center Empty State -->
<div class="ac-git-empty">工作区干净，无改动</div>

<!-- Bottom Floating Tab Pill -->
<div class="ac-git-bottom-tabs">
<div class="ac-git-tab is-active">
  <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round"><polyline points="22 12 18 12 15 21 9 3 6 12 2 12"></polyline></svg>
  <span>状态</span>
</div>
<div class="ac-git-tab">
  <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="6" y1="3" x2="6" y2="15"></line><circle cx="18" cy="6" r="3"></circle><circle cx="6" cy="18" r="3"></circle><path d="M18 9a9 9 0 0 1-9 9"></path></svg>
  <span>分支</span>
</div>
<div class="ac-git-tab">
  <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="4"></circle><line x1="1.05" y1="12" x2="7" y2="12"></line><line x1="17.01" y1="12" x2="22.96" y2="12"></line></svg>
  <span>提交</span>
</div>
</div>
</div>
</div>
</template>

<script setup>
defineEmits(["back"]);
</script>
