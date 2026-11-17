/**
 * AiCode 插件页面组件库（`/__aicode/aicode-ui-vue.js`）的模板类型声明。
 *
 * 组件库在运行时挂到全局 `window.AicodeUI`，页面通过 `app.use(AicodeUI)` 注册。
 * 本文件由 `@jieapi/plugin-sdk/page/vue` 导出，把同名组件并入 Vue 的 `GlobalComponents`，
 * 让 Volar / vue-tsc 在模板里补全 `<AicodeButton>`、`<aicode-button>` 等标签与它们的 props。
 *
 * 需要项目安装 `vue`（仅用于类型，构建时仍使用 `/__aicode/vue.global.prod.js`）。
 */

import type {
  ComponentOptionsMixin,
  ComputedOptions,
  DefineComponent,
  EmitsOptions,
  MethodOptions,
} from 'vue';

export type { PermissionRequest, PermissionDecision } from './host';

type AicodeOption = { label: string; value: string };

type AicodeComponent<Props, Emits extends EmitsOptions> = DefineComponent<
  Props,
  {},
  {},
  ComputedOptions,
  MethodOptions,
  ComponentOptionsMixin,
  ComponentOptionsMixin,
  Emits
>;

interface AicodeBadgeProps {
  text?: string;
  kind?: string;
}

interface AicodeGroupProps {
  title?: string;
  collapsible?: boolean;
  /** 支持 `v-model:expanded`。 */
  expanded?: boolean;
}

interface AicodeRowProps {
  title?: string;
  subtitle?: string;
  badge?: string;
  badgeKind?: string;
  /** `AicodeUI.icons` 中的图标名。 */
  icon?: string;
  chevron?: boolean;
  selectable?: boolean;
  selected?: boolean;
}

interface AicodeFieldProps {
  label?: string;
}

interface AicodeSegmentedProps {
  modelValue?: string;
  options?: AicodeOption[];
  disabled?: boolean;
}

interface AicodeButtonProps {
  /** 同 `aicode-button-{variant}` 的样式变体。 */
  variant?: string;
  compact?: boolean;
  disabled?: boolean;
}

interface AicodeEmptyProps {
  text?: string;
}

interface AicodeMarkdownProps {
  text?: string;
}

interface AicodeIconButtonProps {
  /** 无障碍标签，同时用于 title。 */
  label?: string;
}

interface AicodeSwipeRowProps {
  disabled?: boolean;
  actionText?: string;
}

interface AicodeSwitchProps {
  modelValue?: boolean;
  disabled?: boolean;
}

interface AicodeInputProps {
  modelValue?: string;
  placeholder?: string;
  disabled?: boolean;
  type?: string;
}

interface AicodeTextareaProps {
  modelValue?: string;
  placeholder?: string;
  disabled?: boolean;
}

interface AicodeSelectProps {
  modelValue?: string;
  options?: AicodeOption[];
  disabled?: boolean;
}

interface AicodeDividerProps {
  flat?: boolean;
}

interface AicodeInfoRowProps {
  label?: string;
  value?: string;
}

interface AicodeBottomSheetProps {
  modelValue?: boolean;
  title?: string;
}

interface AicodeDialogProps {
  modelValue?: boolean;
  title?: string;
  message?: string;
  confirmText?: string;
  cancelText?: string;
  confirmDisabled?: boolean;
}

declare module 'vue' {
  export interface GlobalComponents {
    AicodeBadge: DefineComponent<AicodeBadgeProps>;
    AicodeCard: DefineComponent;
    AicodeGroup: AicodeComponent<AicodeGroupProps, {
      'update:expanded': (expanded: boolean) => void;
    }>;
    AicodeRow: AicodeComponent<AicodeRowProps, {
      click: (event: MouseEvent) => void;
    }>;
    AicodeField: DefineComponent<AicodeFieldProps>;
    AicodeSegmented: AicodeComponent<AicodeSegmentedProps, {
      'update:modelValue': (value: string) => void;
    }>;
    AicodeButton: AicodeComponent<AicodeButtonProps, {
      click: (event: MouseEvent) => void;
    }>;
    AicodeEmpty: DefineComponent<AicodeEmptyProps>;
    AicodeMarkdown: DefineComponent<AicodeMarkdownProps>;
    AicodeIconButton: AicodeComponent<AicodeIconButtonProps, {
      click: (event: MouseEvent) => void;
    }>;
    AicodeSwipeRow: AicodeComponent<AicodeSwipeRowProps, {
      delete: () => void;
    }>;
    AicodeSwitch: AicodeComponent<AicodeSwitchProps, {
      'update:modelValue': (value: boolean) => void;
    }>;
    AicodeInput: AicodeComponent<AicodeInputProps, {
      'update:modelValue': (value: string) => void;
    }>;
    AicodeTextarea: AicodeComponent<AicodeTextareaProps, {
      'update:modelValue': (value: string) => void;
    }>;
    AicodeSelect: AicodeComponent<AicodeSelectProps, {
      'update:modelValue': (value: string) => void;
    }>;
    AicodeDivider: DefineComponent<AicodeDividerProps>;
    AicodeInfoRow: DefineComponent<AicodeInfoRowProps>;
    AicodeBottomSheet: AicodeComponent<AicodeBottomSheetProps, {
      'update:modelValue': (value: boolean) => void;
    }>;
    AicodeDialog: AicodeComponent<AicodeDialogProps, {
      'update:modelValue': (value: boolean) => void;
      confirm: () => void;
      cancel: () => void;
    }>;
  }
}

export {};
