# editor — 代码编辑器模块

sora-editor 的 Compose 封装：全屏编辑器页与 WorkbenchPane 嵌入两用，提供 TextMate 语法高亮、VS Code 配色、编码检测与编辑器设置。

## 结构

```
feature/editor/
├── domain/
│   ├── TextMateSetup.kt        # TextMate 语法/主题一次性初始化
│   └── FileEncoding.kt         # 编码检测与回写
├── data/
│   └── EditorSettingsRepository.kt  # DataStore（editor_prefs）
└── presentation/
    ├── CodeEditorScreen.kt     # AndroidView 封装 sora-editor
    ├── CodeEditorViewModel.kt
    └── EditorSettingsScreen.kt / EditorSettingsViewModel.kt
```

## 关键文件

| 文件 | 目的 |
|------|------|
| `domain/TextMateSetup.kt` | IO 线程加载 assets 语法包（GrammarRegistry）+ `dark_plus`/`light_plus` 主题；开启逐 1000 行推送着色；`scopeNameFor(path)` 按扩展名映射 scopeName，未打包语法走纯文本 |
| `domain/FileEncoding.kt` | BOM → 严格 UTF-8 → GBK 兜底检测；保存时用同一编码回写，防转码破坏 |
| `presentation/CodeEditorScreen.kt` | Compose `AndroidView` 封装；全屏路由与 `WorkbenchPane` 嵌入共用 |
| `data/EditorSettingsRepository.kt` | 字号/换行/缩进参考线/空白显示持久化 |

## 依赖

**本模块依赖**:
- sora-editor（`io.github.rosemoe:editor` + `language-textmate`，editor-bom 0.24.6）——只取纯 JVM 模块，避开 treesitter/oniguruma 的 .so（与 ABI flavor 拆分和 F-Droid 可复现构建冲突）
- `feature/agent` — Markdown 预览复用其 `MarkdownContent` 渲染链

**依赖本模块的**:
- `MainActivity` / `WorkbenchPane` — 编辑器面板挂载

## 规范

### 代码模式

**语法高亮**：新增语言支持时在 TextMateSetup 的 assets 语法包与 `scopeNameFor` 扩展名映射处扩展；编辑器主题 JSON 由 `scripts/build-vscode-themes.py` 离线生成（`assets/textmate/`），手改会被覆盖。

### 依赖约束

引入含 native 库的编辑器增强（treesitter 等）前先确认与 ABI flavor 拆分、可复现构建的冲突——这是当初只选纯 JVM 模块的原因。
