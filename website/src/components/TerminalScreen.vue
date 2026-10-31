<template>
<div class="ac-subpage" id="ac-page-terminal">
  <!-- Terminal Header + Session Tab Row (Surface #FCFBF7) -->
  <div class="ac-term-top">
    <header class="ac-subbar ac-subbar--surface">
      <div class="ac-subbar__left">
        <button type="button" class="ac-icon-btn" aria-label="返回" @click="$emit('back')">
          <svg viewBox="0 0 24 24" width="21" height="21" fill="none" stroke="currentColor" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round"><line x1="19" y1="12" x2="5" y2="12"></line><polyline points="12 19 5 12 12 5"></polyline></svg>
        </button>
        <span class="ac-subbar__title">终端</span>
      </div>
      <div class="ac-subbar__right">
        <span class="ac-subbar__icon">
          <svg viewBox="0 0 24 24" width="19" height="19" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="12" cy="12" r="3"></circle>
            <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path>
          </svg>
        </span>
      </div>
    </header>

    <div class="ac-term-tabs">
      <div class="ac-term-tab">
        <span class="ac-term-tab__dot"></span>
        <span>term-1</span>
        <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>
      </div>
      <span class="ac-term-add">
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"></line><line x1="5" y1="12" x2="19" y2="12"></line></svg>
      </span>
    </div>
  </div>

  <!-- Terminal Prompt Canvas -->
  <div class="ac-term-canvas">
    <span class="ac-term-user">root@localhost</span><span>:</span><span class="ac-term-path">~/workspace</span><span># </span><span class="ac-term-block-cursor"></span>
  </div>

  <!-- Bottom Extra Keys Row -->
  <div class="ac-term-keys">
    <span class="ac-term-key">ESC</span>
    <span class="ac-term-key">TAB</span>
    <span class="ac-term-key">CTRL</span>
    <span class="ac-term-key">ALT</span>
    <span class="ac-term-key">&larr;</span>
    <span class="ac-term-key">&uarr;</span>
    <span class="ac-term-key">&darr;</span>
    <span class="ac-term-key">&rarr;</span>
  </div>
</div>
</template>

<script setup>
defineEmits(["back"]);
</script>
