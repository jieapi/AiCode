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
      collapsible: { type: Boolean, default: true },
      expanded: { type: Boolean, default: true }
    },
    emits: ['update:expanded'],
    setup: function (props, context) {
      return function () {
        var showChevron = props.collapsible;
        var expanded = props.expanded;
        return h('div', { class: 'aicode-group' }, [
          h('button', {
            class: 'aicode-group-header',
            type: 'button',
            'data-expanded': showChevron ? String(expanded) : 'true',
            'aria-expanded': showChevron ? String(expanded) : 'true',
            onClick: function () {
              if (showChevron) context.emit('update:expanded', !expanded);
            }
          }, [
            h('span', null, props.title),
            showChevron ? h('span', { class: 'aicode-chevron', innerHTML: CHEVRON }) : null
          ]),
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
      icon: { type: String, default: 'book' },
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
          h('span', {
            class: 'aicode-row-icon',
            innerHTML: ICONS[props.icon] || ICONS.book
          }),
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
          props.chevron ? h('span', { class: 'aicode-row-chevron' }, '›') : null
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
        return h('div', { class: 'aicode-segmented' }, props.options.map(function (option) {
          return h('button', {
            class: 'aicode-segment',
            type: 'button',
            'aria-pressed': String(props.modelValue === option.value),
            disabled: props.disabled,
            onClick: function () {
              if (!props.disabled) context.emit('update:modelValue', option.value);
            }
          }, option.label);
        }));
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
      return function () {
        return h('div', {
          class: 'aicode-markdown',
          innerHTML: renderMarkdown(props.text)
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

  var AicodeBottomSheet = defineComponent({
    name: 'AicodeBottomSheet',
    props: {
      modelValue: { type: Boolean, default: false },
      title: { type: String, default: '' }
    },
    emits: ['update:modelValue'],
    setup: function (props, context) {
      function close() {
        context.emit('update:modelValue', false);
      }

      return function () {
        if (!props.modelValue) return null;
        return h('div', {
          class: 'aicode-sheet-mask',
          onClick: function (event) {
            if (event.target === event.currentTarget) close();
          }
        }, [
          h('div', { class: 'aicode-sheet' }, [
            h('div', { class: 'aicode-sheet-handle' }),
            h('div', { class: 'aicode-sheet-header' }, [
              h('h2', { class: 'aicode-sheet-title' }, props.title),
              h('button', {
                class: 'aicode-icon-button',
                type: 'button',
                'aria-label': '关闭',
                onClick: close
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
      function close() {
        context.emit('update:modelValue', false);
      }

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
          h('div', { class: 'aicode-dialog' }, [
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
