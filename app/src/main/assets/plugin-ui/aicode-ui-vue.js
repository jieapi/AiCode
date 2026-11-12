/*
 * AiCode Vue WebView 组件库。
 *
 * 依赖 vue.global.prod.js 暴露的全局 Vue；样式来自 /__aicode/ui.css 与 theme.css，
 * 与设置页 Compose 组件同一套尺寸和排版。用法：
 *
 *   <script src="/__aicode/vue.global.prod.js"></script>
 *   <script src="/__aicode/aicode-ui-vue.js"></script>
 *   const app = Vue.createApp({...});
 *   app.use(AicodeUI);
 *   app.mount('#app');
 */
(function () {
  'use strict';

  var Vue = window.Vue;
  if (!Vue) {
    console.error('AicodeUI: vue.global.prod.js must be loaded first');
    return;
  }

  var defineComponent = Vue.defineComponent;
  var h = Vue.h;

  var ICONS = {
    book: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" ' +
      'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
      '<path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"></path>' +
      '<path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"></path></svg>',
    file: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" ' +
      'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
      '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path>' +
      '<polyline points="14 2 14 8 20 8"></polyline>' +
      '<line x1="16" y1="13" x2="8" y2="13"></line>' +
      '<line x1="16" y1="17" x2="8" y2="17"></line></svg>'
  };

  var CHEVRON = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" ' +
    'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
    '<polyline points="9 18 15 12 9 6"></polyline></svg>';

  function escapeHtml(value) {
    return String(value == null ? '' : value).replace(/[&<>"']/g, function (ch) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch];
    });
  }

  function inlineMarkdown(text) {
    var escaped = escapeHtml(text);
    escaped = escaped.replace(/`([^`]+)`/g, '<code>$1</code>');
    escaped = escaped.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
    return escaped;
  }

  function renderMarkdown(text) {
    var lines = String(text || '').replace(/\r\n/g, '\n').split('\n');
    var html = [];
    var inCode = false;
    var codeLines = [];
    var listType = null;

    function closeList() {
      if (listType) {
        html.push('</' + listType + '>');
        listType = null;
      }
    }

    for (var i = 0; i < lines.length; i++) {
      var line = lines[i];
      if (/^```/.test(line)) {
        if (inCode) {
          html.push('<pre><code>' + escapeHtml(codeLines.join('\n')) + '</code></pre>');
          codeLines = [];
          inCode = false;
        } else {
          closeList();
          inCode = true;
        }
        continue;
      }
      if (inCode) {
        codeLines.push(line);
        continue;
      }
      if (!line.trim()) {
        closeList();
        continue;
      }
      var heading = /^(#{1,6})\s+(.*)$/.exec(line);
      if (heading) {
        closeList();
        var level = heading[1].length;
        html.push('<h' + level + '>' + inlineMarkdown(heading[2]) + '</h' + level + '>');
        continue;
      }
      var unordered = /^[-*]\s+(.*)$/.exec(line);
      if (unordered) {
        if (listType !== 'ul') {
          closeList();
          html.push('<ul>');
          listType = 'ul';
        }
        html.push('<li>' + inlineMarkdown(unordered[1]) + '</li>');
        continue;
      }
      var ordered = /^\d+\.\s+(.*)$/.exec(line);
      if (ordered) {
        if (listType !== 'ol') {
          closeList();
          html.push('<ol>');
          listType = 'ol';
        }
        html.push('<li>' + inlineMarkdown(ordered[1]) + '</li>');
        continue;
      }
      closeList();
      html.push('<p>' + inlineMarkdown(line) + '</p>');
    }

    closeList();
    if (inCode) {
      html.push('<pre><code>' + escapeHtml(codeLines.join('\n')) + '</code></pre>');
    }
    return html.join('');
  }

  /* ── Markdown ── */
  var markdownAssetState = {
    ready: false,
    loading: null,
    themeLink: null,
    highlightLink: null
  };

  function loadScript(src) {
    return new Promise(function (resolve, reject) {
      var script = document.createElement('script');
      script.src = src;
      script.onload = function () { resolve(); };
      script.onerror = function () { reject(new Error('failed to load ' + src)); };
      document.head.appendChild(script);
    });
  }

  function ensureMarkdownThemeLinks() {
    if (markdownAssetState.themeLink) return;
    markdownAssetState.themeLink = document.createElement('link');
    markdownAssetState.themeLink.rel = 'stylesheet';
    markdownAssetState.themeLink.id = 'aicode-markdown-theme';
    document.head.appendChild(markdownAssetState.themeLink);
    markdownAssetState.highlightLink = document.createElement('link');
    markdownAssetState.highlightLink.rel = 'stylesheet';
    markdownAssetState.highlightLink.id = 'aicode-highlight-theme';
    document.head.appendChild(markdownAssetState.highlightLink);
  }

  function markdownThemeIsDark() {
    var value = getComputedStyle(document.documentElement)
      .getPropertyValue('--aicode-page-bg')
      .trim();
    var match = /^#([0-9a-fA-F]{6})$/.exec(value);
    if (!match) return false;
    var rgb = parseInt(match[1], 16);
    var r = (rgb >> 16) & 255;
    var g = (rgb >> 8) & 255;
    var b = rgb & 255;
    return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255 < 0.5;
  }

  function applyMarkdownTheme() {
    if (!markdownAssetState.themeLink) return;
    var dark = markdownThemeIsDark();
    var markdown = '/__aicode/markdown/' +
      (dark ? 'github-markdown-dark.css' : 'github-markdown-light.css');
    var highlight = '/__aicode/markdown/' +
      (dark ? 'highlight-github-dark.min.css' : 'highlight-github.min.css');
    if (markdownAssetState.themeLink.getAttribute('href') !== markdown) {
      markdownAssetState.themeLink.href = markdown;
    }
    if (markdownAssetState.highlightLink.getAttribute('href') !== highlight) {
      markdownAssetState.highlightLink.href = highlight;
    }
  }

  function ensureMarkdownAssets() {
    ensureMarkdownThemeLinks();
    applyMarkdownTheme();
    if (markdownAssetState.ready) return Promise.resolve();
    if (markdownAssetState.loading) return markdownAssetState.loading;
    markdownAssetState.loading = Promise.all([
      loadScript('/__aicode/markdown/marked.min.js'),
      loadScript('/__aicode/markdown/purify.min.js'),
      loadScript('/__aicode/markdown/highlight.min.js')
    ]).then(function () {
      markdownAssetState.ready = true;
    }).catch(function (error) {
      markdownAssetState.loading = null;
      throw error;
    });
    return markdownAssetState.loading;
  }

  window.addEventListener('aicode:theme', applyMarkdownTheme);

  var AicodeBadge = defineComponent({
    name: 'AicodeBadge',
    props: {
      text: { type: String, default: '' },
      kind: { type: String, default: '' }
    },
    setup: function (props) {
      return function () {
        return h('span', {
          class: 'aicode-badge' + (props.kind ? ' aicode-badge-' + props.kind : '')
        }, props.text);
      };
    }
  });

  var AicodeCard = defineComponent({
    name: 'AicodeCard',
    setup: function (props, context) {
      return function () {
        return h('div', { class: 'aicode-card' }, context.slots.default ? context.slots.default() : []);
      };
    }
  });

  var AicodeGroup = defineComponent({
    name: 'AicodeGroup',
    props: {
      title: { type: String, default: '' },
      collapsible: { type: Boolean, default: false },
      expanded: { type: Boolean, default: true }
    },
    emits: ['update:expanded'],
    setup: function (props, context) {
      return function () {
        var showChevron = props.collapsible;
        var expanded = props.expanded;
        return h('div', { class: 'aicode-group' }, [
          props.title ? h('button', {
            class: 'aicode-group-header',
            type: 'button',
            'data-collapsible': String(showChevron),
            'data-expanded': showChevron ? String(expanded) : 'true',
            'aria-expanded': showChevron ? String(expanded) : 'true',
            onClick: function () {
              if (showChevron) context.emit('update:expanded', !expanded);
            }
          }, [
            h('span', null, props.title),
            showChevron ? h('span', { class: 'aicode-chevron', innerHTML: CHEVRON }) : null
          ]) : null,
          h('div', {
            class: 'aicode-card',
            hidden: showChevron && !expanded
          }, context.slots.default ? context.slots.default() : [])
        ]);
      };
    }
  });

  var AicodeRow = defineComponent({
    name: 'AicodeRow',
    props: {
      title: { type: String, default: '' },
      subtitle: { type: String, default: '' },
      badge: { type: String, default: '' },
      badgeKind: { type: String, default: '' },
      icon: { type: String, default: '' },
      chevron: { type: Boolean, default: true },
      selectable: { type: Boolean, default: false },
      selected: { type: Boolean, default: false }
    },
    emits: ['click'],
    setup: function (props, context) {
      return function () {
        return h('button', {
          class: 'aicode-row',
          type: 'button',
          onClick: function (event) {
            context.emit('click', event);
          }
        }, [
          props.selectable
            ? h('span', {
              class: 'aicode-checkbox',
              'data-checked': String(!!props.selected)
            })
            : null,
          props.icon
            ? h('span', {
              class: 'aicode-row-icon',
              innerHTML: ICONS[props.icon] || ICONS.book
            })
            : null,
          h('span', { class: 'aicode-row-main' }, [
            h('span', { class: 'aicode-row-title' }, [
              h('span', { class: 'aicode-row-title-text' }, props.title),
              props.badge
                ? h('span', {
                  class: 'aicode-badge' + (props.badgeKind ? ' aicode-badge-' + props.badgeKind : '')
                }, props.badge)
                : null
            ]),
            h('span', { class: 'aicode-row-subtitle' }, props.subtitle)
          ]),
          props.chevron ? h('span', { class: 'aicode-row-chevron', innerHTML: CHEVRON }) : null
        ]);
      };
    }
  });

  var AicodeField = defineComponent({
    name: 'AicodeField',
    props: {
      label: { type: String, default: '' }
    },
    setup: function (props, context) {
      return function () {
        return h('div', { class: 'aicode-outlined-field' }, [
          props.label ? h('label', null, props.label) : null,
          context.slots.default ? context.slots.default() : []
        ]);
      };
    }
  });

  var AicodeSegmented = defineComponent({
    name: 'AicodeSegmented',
    props: {
      modelValue: { type: String, default: '' },
      options: { type: Array, default: function () { return []; } },
      disabled: { type: Boolean, default: false }
    },
    emits: ['update:modelValue'],
    setup: function (props, context) {
      return function () {
        var options = props.options || [];
        var selectedIndex = 0;
        options.forEach(function (option, index) {
          if (option.value === props.modelValue) selectedIndex = index;
        });
        var children = [];
        if (options.length) {
          children.push(h('span', {
            class: 'aicode-segmented-thumb',
            style: {
              width: 'calc((100% - 6px) / ' + options.length + ')',
              transform: 'translateX(' + (selectedIndex * 100) + '%)'
            }
          }));
        }
        options.forEach(function (option, index) {
          children.push(h('button', {
            class: 'aicode-segment',
            type: 'button',
            role: 'tab',
            'aria-selected': String(props.modelValue === option.value),
            'aria-pressed': String(props.modelValue === option.value),
            disabled: props.disabled,
            onClick: function () {
              if (!props.disabled) context.emit('update:modelValue', option.value);
            }
          }, option.label));
        });
        return h('div', {
          class: 'aicode-segmented' + (props.disabled ? ' aicode-segmented-disabled' : '')
        }, children);
      };
    }
  });

  var AicodeButton = defineComponent({
    name: 'AicodeButton',
    props: {
      variant: { type: String, default: '' },
      compact: { type: Boolean, default: false },
      disabled: { type: Boolean, default: false }
    },
    emits: ['click'],
    setup: function (props, context) {
      return function () {
        var className = 'aicode-button' +
          (props.variant ? ' aicode-button-' + props.variant : '') +
          (props.compact ? ' aicode-button-compact' : '');
        return h('button', {
          class: className,
          type: 'button',
          disabled: props.disabled,
          onClick: function (event) {
            if (!props.disabled) context.emit('click', event);
          }
        }, context.slots.default ? context.slots.default() : []);
      };
    }
  });

  var AicodeEmpty = defineComponent({
    name: 'AicodeEmpty',
    props: {
      text: { type: String, default: '' }
    },
    setup: function (props) {
      return function () {
        return h('div', { class: 'aicode-empty' }, props.text);
      };
    }
  });

  var AicodeMarkdown = defineComponent({
    name: 'AicodeMarkdown',
    props: {
      text: { type: String, default: '' }
    },
    setup: function (props) {
      var root = Vue.ref(null);
      var enhanced = Vue.ref(false);

      function highlight() {
        if (!enhanced.value || !window.hljs || !root.value) return;
        Array.prototype.forEach.call(root.value.querySelectorAll('pre code'), function (element) {
          try {
            window.hljs.highlightElement(element);
          } catch (e) {
            // 忽略单个代码块的高亮失败。
          }
        });
      }

      function html() {
        var text = String(props.text || '');
        if (enhanced.value && window.marked && window.DOMPurify) {
          try {
            var raw = window.marked.parse(text, { gfm: true, breaks: false });
            return window.DOMPurify.sanitize(raw, { ADD_ATTR: ['align', 'target'] });
          } catch (e) {
            // 解析失败时退回内置解析器。
          }
        }
        return renderMarkdown(text);
      }

      Vue.onMounted(function () {
        ensureMarkdownAssets().then(function () {
          enhanced.value = true;
          Vue.nextTick(highlight);
        }).catch(function () {});
      });

      Vue.onUpdated(highlight);

      return function () {
        return h('div', {
          ref: root,
          class: 'aicode-markdown markdown-body',
          innerHTML: html()
        });
      };
    }
  });

  var AicodeIconButton = defineComponent({
    name: 'AicodeIconButton',
    props: {
      label: { type: String, default: '' }
    },
    emits: ['click'],
    setup: function (props, context) {
      return function () {
        return h('button', {
          class: 'aicode-icon-button',
          type: 'button',
          'aria-label': props.label,
          onClick: function (event) {
            context.emit('click', event);
          }
        }, context.slots.default ? context.slots.default() : []);
      };
    }
  });

  var TRASH_ICON = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" ' +
    'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
    '<polyline points="3 6 5 6 21 6"></polyline>' +
    '<path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path></svg>';

  // 与 App 的 SwipeToDeleteRow 一致：露出 112、按钮 104。
  var SWIPE_REVEAL = 112;

  var AicodeSwipeRow = defineComponent({
    name: 'AicodeSwipeRow',
    props: {
      disabled: { type: Boolean, default: false },
      actionText: { type: String, default: '删除' }
    },
    emits: ['delete'],
    setup: function (props, context) {
      var offset = Vue.ref(0);
      var transition = Vue.ref('none');
      var startX = 0;
      var startY = 0;
      var startOffset = 0;
      var horizontal = false;
      var settleTimer = null;
      var deleteTimer = null;

      function clamp(value) {
        return Math.max(-SWIPE_REVEAL * 1.15, Math.min(0, value));
      }

      function animateTo(target, spec) {
        transition.value = spec;
        offset.value = target;
        if (settleTimer) clearTimeout(settleTimer);
        settleTimer = setTimeout(function () {
          transition.value = 'none';
          settleTimer = null;
        }, 320);
      }

      function close() {
        animateTo(0, 'transform 0.2s cubic-bezier(0.2, 0, 0, 1)');
      }

      function onTouchStart(event) {
        if (props.disabled || !event.touches.length) return;
        startX = event.touches[0].clientX;
        startY = event.touches[0].clientY;
        startOffset = offset.value;
        horizontal = false;
        transition.value = 'none';
      }

      function onTouchMove(event) {
        if (props.disabled || !event.touches.length) return;
        var dx = event.touches[0].clientX - startX;
        var dy = event.touches[0].clientY - startY;
        if (!horizontal) {
          if (Math.abs(dx) < 6 || Math.abs(dx) <= Math.abs(dy)) return;
          horizontal = dx < 0 || startOffset < 0;
          if (!horizontal) return;
        }
        event.preventDefault();
        offset.value = clamp(startOffset + dx);
      }

      function onTouchEnd() {
        if (!horizontal) return;
        horizontal = false;
        if (offset.value < -SWIPE_REVEAL / 2) {
          // 展开带一点点回弹，对齐 App 的 spring(DampingRatioLowBouncy)。
          animateTo(-SWIPE_REVEAL, 'transform 0.28s cubic-bezier(0.34, 1.4, 0.64, 1)');
        } else {
          animateTo(0, 'transform 0.2s cubic-bezier(0.2, 0, 0, 1)');
        }
      }

      function onClickCapture(event) {
        if (offset.value < -10) {
          event.stopPropagation();
          event.preventDefault();
          close();
        }
      }

      function onDeleteClick() {
        if (props.disabled) return;
        transition.value = 'transform 0.18s ease-out';
        offset.value = 0;
        if (deleteTimer) clearTimeout(deleteTimer);
        deleteTimer = setTimeout(function () {
          deleteTimer = null;
          context.emit('delete');
        }, 180);
      }

      Vue.watch(function () {
        return props.disabled;
      }, function (disabled) {
        if (disabled && offset.value !== 0) {
          transition.value = 'none';
          offset.value = 0;
        }
      });

      Vue.onUnmounted(function () {
        if (settleTimer) clearTimeout(settleTimer);
        if (deleteTimer) clearTimeout(deleteTimer);
      });

      return function () {
        var progress = Math.min(1, Math.max(0, -offset.value / SWIPE_REVEAL));
        return h('div', { class: 'aicode-swipe-row' }, [
          props.disabled ? null : h('button', {
            class: 'aicode-swipe-action',
            type: 'button',
            'aria-label': props.actionText,
            style: {
              opacity: String(Math.min(1, progress * 1.2)),
              transform: 'scale(' + (0.4 + 0.6 * progress) + ', ' + (0.7 + 0.3 * progress) + ')'
            },
            onClick: onDeleteClick
          }, [
            h('span', { class: 'aicode-swipe-icon', innerHTML: TRASH_ICON }),
            h('span', null, props.actionText)
          ]),
          h('div', {
            class: 'aicode-swipe-content',
            style: {
              transform: offset.value ? 'translateX(' + offset.value + 'px)' : '',
              transition: transition.value
            },
            onClickCapture: onClickCapture,
            onTouchstart: onTouchStart,
            onTouchmove: onTouchMove,
            onTouchend: onTouchEnd,
            onTouchcancel: onTouchEnd
          }, context.slots.default ? context.slots.default() : [])
        ]);
      };
    }
  });

  var AicodeSwitch = defineComponent({
    name: 'AicodeSwitch',
    props: {
      modelValue: { type: Boolean, default: false },
      disabled: { type: Boolean, default: false }
    },
    emits: ['update:modelValue'],
    setup: function (props, context) {
      return function () {
        return h('button', {
          class: 'aicode-switch',
          type: 'button',
          role: 'switch',
          'aria-checked': String(!!props.modelValue),
          disabled: props.disabled,
          onClick: function () {
            if (!props.disabled) context.emit('update:modelValue', !props.modelValue);
          }
        }, [h('span', { class: 'aicode-switch-thumb' })]);
      };
    }
  });

  var AicodeInput = defineComponent({
    name: 'AicodeInput',
    props: {
      modelValue: { type: String, default: '' },
      placeholder: { type: String, default: '' },
      disabled: { type: Boolean, default: false },
      type: { type: String, default: 'text' }
    },
    emits: ['update:modelValue'],
    setup: function (props, context) {
      return function () {
        return h('input', {
          class: 'aicode-input',
          type: props.type,
          value: props.modelValue,
          placeholder: props.placeholder,
          disabled: props.disabled,
          onInput: function (event) {
            context.emit('update:modelValue', event.target.value);
          }
        });
      };
    }
  });

  var AicodeTextarea = defineComponent({
    name: 'AicodeTextarea',
    props: {
      modelValue: { type: String, default: '' },
      placeholder: { type: String, default: '' },
      disabled: { type: Boolean, default: false }
    },
    emits: ['update:modelValue'],
    setup: function (props, context) {
      return function () {
        return h('textarea', {
          class: 'aicode-textarea',
          value: props.modelValue,
          placeholder: props.placeholder,
          disabled: props.disabled,
          onInput: function (event) {
            context.emit('update:modelValue', event.target.value);
          }
        });
      };
    }
  });

  var AicodeSelect = defineComponent({
    name: 'AicodeSelect',
    props: {
      modelValue: { type: String, default: '' },
      options: { type: Array, default: function () { return []; } },
      disabled: { type: Boolean, default: false }
    },
    emits: ['update:modelValue'],
    setup: function (props, context) {
      return function () {
        return h('select', {
          class: 'aicode-select',
          value: props.modelValue,
          disabled: props.disabled,
          onChange: function (event) {
            context.emit('update:modelValue', event.target.value);
          }
        }, props.options.map(function (option) {
          return h('option', { value: option.value }, option.label);
        }));
      };
    }
  });

  var AicodeDivider = defineComponent({
    name: 'AicodeDivider',
    props: { flat: { type: Boolean, default: false } },
    setup: function (props) {
      return function () {
        return h('div', { class: 'aicode-divider' + (props.flat ? ' aicode-divider-flat' : '') });
      };
    }
  });

  var AicodeInfoRow = defineComponent({
    name: 'AicodeInfoRow',
    props: {
      label: { type: String, default: '' },
      value: { type: String, default: '' }
    },
    setup: function (props) {
      return function () {
        return h('div', { class: 'aicode-info-row' }, [
          h('span', null, props.label),
          h('span', null, props.value)
        ]);
      };
    }
  });

  // 弹窗类组件的返回键栈：后打开的先消费，页面自己的 onBack 仍可共存。
  var overlayStack = [];
  var overlayBackWired = false;
  var overlayRequest = Promise.resolve();
  var lastOverlaySignature = '';
  var overlaySyncScheduled = false;

  function topOverlayRect() {
    for (var i = overlayStack.length - 1; i >= 0; i--) {
      var provider = overlayStack[i].rect;
      if (!provider) continue;
      var rect = provider();
      if (rect && rect.width > 0 && rect.height > 0) return rect;
    }
    return null;
  }

  // WebView 内部弹层挡不住宿主绘制在最上层的背景图；上报弹层矩形，让宿主在背景图上挖洞。
  function syncOverlayBackground() {
    var active = overlayStack.length > 0;
    var rect = active ? topOverlayRect() : null;
    var signature = active
      ? (rect
        ? [Math.round(rect.left), Math.round(rect.top), Math.round(rect.width),
          Math.round(rect.height), Math.round(rect.topRadius), Math.round(rect.bottomRadius)].join(',')
        : 'no-rect')
      : 'off';
    if (signature === lastOverlaySignature) return;
    if (!window.aicode || !window.aicode.ui || !window.aicode.ui.setOverlay) return;
    lastOverlaySignature = signature;
    overlayRequest = overlayRequest.then(function () {
      return window.aicode.ui.setOverlay(active, rect);
    }).catch(function () {
      lastOverlaySignature = '';
    });
  }

  function scheduleOverlaySync() {
    if (overlaySyncScheduled) return;
    overlaySyncScheduled = true;
    requestAnimationFrame(function () {
      overlaySyncScheduled = false;
      syncOverlayBackground();
    });
  }

  // 弹层滑入/滑出/回弹时矩形在动，短时间内持续跟随，避免背景图短暂叠到弹层上。
  function trackOverlayMotion(duration) {
    var until = Date.now() + duration;
    setTimeout(function tick() {
      syncOverlayBackground();
      if (overlayStack.length && Date.now() < until) setTimeout(tick, 30);
    }, 16);
  }

  window.addEventListener('resize', function () {
    if (overlayStack.length) scheduleOverlaySync();
  });

  function ensureOverlayBack() {
    if (overlayBackWired || !window.aicode || !window.aicode.ui || !window.aicode.ui.onBack) return;
    overlayBackWired = true;
    window.aicode.ui.onBack(function () {
      var top = overlayStack[overlayStack.length - 1];
      if (!top) return false;
      top.close();
      return true;
    });
  }

  function pushOverlay(close, rectProvider) {
    ensureOverlayBack();
    for (var i = 0; i < overlayStack.length; i++) {
      if (overlayStack[i].close === close) return;
    }
    overlayStack.push({ close: close, rect: rectProvider || null });
    scheduleOverlaySync();
  }

  function removeOverlay(close) {
    for (var i = overlayStack.length - 1; i >= 0; i--) {
      if (overlayStack[i].close === close) {
        overlayStack.splice(i, 1);
        break;
      }
    }
    scheduleOverlaySync();
  }

  function isWideViewport() {
    return window.innerWidth >= 600;
  }

  var AicodeBottomSheet = defineComponent({
    name: 'AicodeBottomSheet',
    props: {
      modelValue: { type: Boolean, default: false },
      title: { type: String, default: '' }
    },
    emits: ['update:modelValue'],
    setup: function (props, context) {
      var shown = Vue.ref(false);
      var closing = Vue.ref(false);
      var dragging = Vue.ref(false);
      var offset = Vue.ref(0);
      var maskOpacity = Vue.ref(1);
      var sheetRef = Vue.ref(null);
      var closeTimer = null;
      var drag = null;
      var bodyOverflow = null;

      function lockBody() {
        if (bodyOverflow !== null) return;
        bodyOverflow = document.body.style.overflow;
        document.body.style.overflow = 'hidden';
      }

      function sheetRect() {
        var el = sheetRef.value;
        if (!el) return null;
        var rect = el.getBoundingClientRect();
        var wide = isWideViewport();
        return {
          left: rect.left,
          top: rect.top,
          width: rect.width,
          height: rect.height,
          topRadius: wide ? 16 : 28,
          bottomRadius: wide ? 16 : 0,
          coversBottom: true
        };
      }

      function unlockBody() {
        if (bodyOverflow === null) return;
        document.body.style.overflow = bodyOverflow;
        bodyOverflow = null;
      }

      function requestClose() {
        if (!shown.value || closing.value) return;
        closing.value = true;
        dragging.value = false;
        drag = null;
        var height = sheetRef.value ? sheetRef.value.offsetHeight : window.innerHeight * 0.5;
        offset.value = isWideViewport() ? 0 : height;
        maskOpacity.value = 0;
        scheduleOverlaySync();
        trackOverlayMotion(260);
        closeTimer = setTimeout(function () {
          closeTimer = null;
          shown.value = false;
          closing.value = false;
          offset.value = 0;
          maskOpacity.value = 1;
          unlockBody();
          removeOverlay(requestClose);
          context.emit('update:modelValue', false);
        }, 220);
      }

      Vue.watch(function () {
        return props.modelValue;
      }, function (open) {
        if (open) {
          if (closeTimer) {
            clearTimeout(closeTimer);
            closeTimer = null;
          }
          closing.value = false;
          offset.value = 0;
          maskOpacity.value = 1;
          shown.value = true;
          lockBody();
          pushOverlay(requestClose, sheetRect);
          trackOverlayMotion(300);
        } else if (shown.value && !closing.value) {
          requestClose();
        }
      }, { immediate: true });

      function startDrag(event) {
        if (isWideViewport() || !shown.value || closing.value) return;
        if (event.button != null && event.button !== 0) return;
        if (event.target && event.target.closest && event.target.closest('button')) return;
        drag = {
          startY: event.clientY,
          lastY: event.clientY,
          lastTime: Date.now(),
          velocity: 0,
          moved: false
        };
        dragging.value = true;
        offset.value = 0;
        if (event.currentTarget.setPointerCapture && event.pointerId != null) {
          try {
            event.currentTarget.setPointerCapture(event.pointerId);
          } catch (e) {
            // 忽略不支持指针捕获的 WebView。
          }
        }
        event.preventDefault();
      }

      function moveDrag(event) {
        if (!drag) return;
        var now = Date.now();
        var delta = event.clientY - drag.startY;
        if (Math.abs(delta) > 4) drag.moved = true;
        if (delta < 0) delta = 0;
        offset.value = delta;
        maskOpacity.value = Math.max(0, 1 - Math.max(0, delta) / 280);
        scheduleOverlaySync();
        var elapsed = now - drag.lastTime;
        if (elapsed > 0) {
          var instantaneous = (event.clientY - drag.lastY) / elapsed;
          drag.velocity = drag.velocity * 0.6 + instantaneous * 0.4;
          drag.lastY = event.clientY;
          drag.lastTime = now;
        }
        event.preventDefault();
      }

      function endDrag() {
        if (!drag) return;
        var wasMoved = drag.moved;
        var velocity = drag.velocity;
        var delta = Math.max(0, offset.value);
        drag = null;
        dragging.value = false;
        if (!wasMoved) {
          requestClose();
          return;
        }
        var height = sheetRef.value ? sheetRef.value.offsetHeight : window.innerHeight * 0.5;
        if (delta > Math.min(140, height * 0.28) || velocity > 0.55) {
          requestClose();
        } else {
          offset.value = 0;
          maskOpacity.value = 1;
          trackOverlayMotion(260);
        }
      }

      Vue.onUnmounted(function () {
        if (closeTimer) clearTimeout(closeTimer);
        unlockBody();
        removeOverlay(requestClose);
      });

      return function () {
        if (!shown.value) return null;
        return h('div', {
          class: 'aicode-sheet-mask',
          role: 'dialog',
          'aria-modal': 'true',
          onClick: function (event) {
            if (event.target === event.currentTarget) requestClose();
          }
        }, [
          h('div', {
            class: 'aicode-sheet-scrim',
            style: { opacity: maskOpacity.value }
          }),
          h('div', {
            ref: sheetRef,
            class: 'aicode-sheet' + (dragging.value ? ' aicode-sheet-dragging' : ''),
            style: { transform: 'translate3d(0, ' + offset.value + 'px, 0)' }
          }, [
            h('div', {
              class: 'aicode-sheet-grabber',
              role: 'button',
              tabIndex: 0,
              'aria-label': '下拉关闭',
              onPointerdown: startDrag,
              onPointermove: moveDrag,
              onPointerup: endDrag,
              onPointercancel: endDrag,
              onKeydown: function (event) {
                if (event.key === 'Enter' || event.key === ' ') requestClose();
              }
            }, [h('div', { class: 'aicode-sheet-handle' })]),
            h('div', {
              class: 'aicode-sheet-header',
              onPointerdown: startDrag,
              onPointermove: moveDrag,
              onPointerup: endDrag,
              onPointercancel: endDrag
            }, [
              h('h2', { class: 'aicode-sheet-title' }, props.title),
              h('button', {
                class: 'aicode-icon-button',
                type: 'button',
                'aria-label': '关闭',
                onClick: requestClose
              }, '×')
            ]),
            h('div', { class: 'aicode-sheet-body' }, context.slots.default ? context.slots.default() : [])
          ])
        ]);
      };
    }
  });

  var AicodeDialog = defineComponent({
    name: 'AicodeDialog',
    props: {
      modelValue: { type: Boolean, default: false },
      title: { type: String, default: '' },
      message: { type: String, default: '' },
      confirmText: { type: String, default: '确定' },
      cancelText: { type: String, default: '取消' }
    },
    emits: ['update:modelValue', 'confirm', 'cancel'],
    setup: function (props, context) {
      var dialogRef = Vue.ref(null);

      function close() {
        context.emit('update:modelValue', false);
      }

      function dialogRect() {
        var el = dialogRef.value;
        if (!el) return null;
        var rect = el.getBoundingClientRect();
        return {
          left: rect.left,
          top: rect.top,
          width: rect.width,
          height: rect.height,
          topRadius: 28,
          bottomRadius: 28,
          coversBottom: false
        };
      }

      Vue.watch(function () {
        return props.modelValue;
      }, function (open) {
        if (open) pushOverlay(close, dialogRect);
        else removeOverlay(close);
      }, { immediate: true });

      Vue.onUnmounted(function () {
        removeOverlay(close);
      });

      return function () {
        if (!props.modelValue) return null;
        return h('div', {
          class: 'aicode-dialog-mask',
          onClick: function (event) {
            if (event.target === event.currentTarget) {
              context.emit('cancel');
              close();
            }
          }
        }, [
          h('div', { ref: dialogRef, class: 'aicode-dialog' }, [
            props.title ? h('h2', { class: 'aicode-dialog-title' }, props.title) : null,
            props.message ? h('p', { class: 'aicode-dialog-message' }, props.message) : null,
            h('div', { class: 'aicode-dialog-actions' }, [
              h('button', {
                class: 'aicode-button aicode-button-compact',
                type: 'button',
                onClick: function () {
                  context.emit('cancel');
                  close();
                }
              }, props.cancelText),
              h('button', {
                class: 'aicode-button aicode-button-primary aicode-button-compact',
                type: 'button',
                onClick: function () {
                  context.emit('confirm');
                  close();
                }
              }, props.confirmText)
            ])
          ])
        ]);
      };
    }
  });

  var components = {
    AicodeBadge: AicodeBadge,
    AicodeCard: AicodeCard,
    AicodeGroup: AicodeGroup,
    AicodeRow: AicodeRow,
    AicodeField: AicodeField,
    AicodeSegmented: AicodeSegmented,
    AicodeButton: AicodeButton,
    AicodeEmpty: AicodeEmpty,
    AicodeMarkdown: AicodeMarkdown,
    AicodeIconButton: AicodeIconButton,
    AicodeSwipeRow: AicodeSwipeRow,
    AicodeSwitch: AicodeSwitch,
    AicodeInput: AicodeInput,
    AicodeTextarea: AicodeTextarea,
    AicodeSelect: AicodeSelect,
    AicodeDivider: AicodeDivider,
    AicodeInfoRow: AicodeInfoRow,
    AicodeBottomSheet: AicodeBottomSheet,
    AicodeDialog: AicodeDialog
  };

  window.AicodeUI = {
    install: function (app) {
      Object.keys(components).forEach(function (name) {
        app.component(name, components[name]);
      });
    },
    renderMarkdown: renderMarkdown,
    icons: ICONS,
    components: components
  };
})();
