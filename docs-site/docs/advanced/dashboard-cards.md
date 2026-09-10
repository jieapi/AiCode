# 自定义面板 (DIY Dashboard) 与 Adaptive Cards 设计规范指南

本文档定义了 AiCode 在聊天输入框上方「自定义面板 (DIY Dashboard)」中支持的 **Adaptive Cards 声明式微模板规范**。

AI 助手与开发者可以依据此规范，编写面板脚本（Python / Node / Shell）输出结构化卡片 JSON，从而完全自定义面板在收起态（Compact）与展开态（Expanded）下的排版、配色、进度条、指标卡片及明细信息。

---

## 1. 脚本存放路径与管理方式

### 1.1 默认存放路径
- **统一脚本目录**：`~/.aicode/scripts/`（容器绝对路径为 `/root/.aicode/scripts/`）。
- **持久化说明**：该目录映射至宿主 App 数据目录，在容器升级重装时保留，不会丢失。

### 1.2 脚本编写与语言支持
脚本只需将标准 JSON 结果输出到**标准输出 (stdout)** 即可：
- **Python 脚本**（`.py` 后缀）：底层自动调用 `python3 <script_path>` 执行；
- **NodeJS 脚本**（`.js` 后缀）：底层自动调用 `node <script_path>` 执行；
- **Shell 脚本**（`.sh` / `.bash` 后缀）：底层自动调用 `bash <script_path>` 执行；
- **二进制 / 可执行脚本**：如果具备可执行权限（`chmod +x`），底层直接执行。

### 1.3 路径解析规则
在提供商设置项「面板脚本」中，支持以下几种路径填写方式：
1. **纯文件名**（推荐，如 `demo_balance.py`）：自动在 `~/.aicode/scripts/` 目录下查找；
2. **相对路径**（如 `scripts/my_panel.py` 或 `.aicode/scripts/my_panel.py`）：自动从 `~/.aicode/` 展开；
3. **波浪号路径**（如 `~/.aicode/scripts/my_panel.py` 或 `~/my_script.py`）：自动展开为 `/root/` 对应路径；
4. **容器内绝对路径**（如 `/root/workspace/scripts/quota.py`）。

---

## 2. 脚本运行环境与注入的环境变量

执行面板脚本时，AiCode 会自动注入以下环境变量，供脚本自由消费并构建动态面板：

### Token 类变量

> **关于「输入 Token」的说明**：`LAST_INPUT_TOKENS` 和 `TOTAL_INPUT_TOKENS` 均为 API 返回的**总输入 Token**（即 `input_tokens` / `prompt_tokens` / `promptTokenCount`），**已包含缓存命中部分**。`LAST_CACHED_TOKENS` 是其中命中服务端缓存的部分（子集）。因此，纯新增输入 = `LAST_INPUT_TOKENS` - `LAST_CACHED_TOKENS`。

| 环境变量名 | 示例值 | 说明 |
| :--- | :--- | :--- |
| `AICODE_MODEL` | `claude-3-7-sonnet` | 当前选中的生效模型名称 |
| `AICODE_SESSION_ID` | `sess_98a7bc21` | 当前会话 ID |
| `AICODE_LAST_INPUT_TOKENS` | `1420` | 最近一次单次 LLM 请求的输入 Token（API 返回的总输入，**含缓存命中**） |
| `AICODE_LAST_OUTPUT_TOKENS` | `365` | 最近一次单次 LLM 请求生成的输出 Token |
| `AICODE_LAST_CACHED_TOKENS` | `1024` | 最近一次请求中命中服务端缓存的部分（如 OpenAI `cached_tokens` / Anthropic `cache_read_input_tokens`）。是 `LAST_INPUT_TOKENS` 的子集，取不到时为 0 |
| `AICODE_TOTAL_INPUT_TOKENS` | `28540` | 当前会话累计的输入 Token（API 返回的总输入，**含缓存命中**） |
| `AICODE_TOTAL_OUTPUT_TOKENS` | `5420` | 当前会话累计生成的输出 Token |
| `AICODE_MODEL_CONTEXT_TOKENS` | `200000` | 当前模型的上下文窗口大小（Token）。若模型元数据未获取到则为 0 |
| `AICODE_MODEL_MAX_INPUT_TOKENS` | `200000` | 模型最大输入 Token 限制。未获取到则为 0 |
| `AICODE_MODEL_MAX_OUTPUT_TOKENS` | `8192` | 模型最大输出 Token 限制。未获取到则为 0 |
| `AICODE_MODEL_INPUT_COST_USD_PER_M` | `3.0` | 模型输入单价（美元 / 百万 Token）。未获取到则为 0 |
| `AICODE_MODEL_OUTPUT_COST_USD_PER_M` | `15.0` | 模型输出单价（美元 / 百万 Token）。未获取到则为 0 |
| `AICODE_MODEL_CACHE_READ_COST_USD_PER_M` | `0.3` | 模型缓存读取单价（美元 / 百万 Token）。未获取到则为 0 |
| `AICODE_MODEL_SUPPORTS_TOOLS` | `true` | 模型是否支持工具调用（`true` / `false`） |
| `AICODE_MODEL_SUPPORTS_VISION` | `false` | 模型是否支持图片输入（`true` / `false`） |
| `AICODE_MODEL_SUPPORTS_REASONING` | `true` | 模型是否支持思考/推理模式（`true` / `false`） |
| `AICODE_MESSAGE_COUNT` | `12` | 当前会话的消息总数（含用户和助手消息） |

### 会话状态变量

| 环境变量名 | 示例值 | 说明 |
| :--- | :--- | :--- |
| `AICODE_AGENT_STATE` | `streaming` | 当前 Agent 工作状态：`idle`（空闲）/ `loading`（加载中）/ `streaming`（流式输出中）/ `result`（完成）/ `error`（出错） |
| `AICODE_SESSION_MODE` | `build` | 当前会话模式：`build`（构建）/ `plan`（计划）/ `auto`（自动） |
| `AICODE_REASONING_EFFORT` | `high` | 当前思考强度档位：`none` / `minimal` / `low` / `medium` / `high` / `xhigh` / `max` |
| `AICODE_REFRESH_REASON` | `llm` | 本次面板刷新的触发原因：`session`（进入/切换会话，数据就绪后触发）、`llm`（LLM 请求返回后自动刷新）、`done`（AI 一轮任务完成后自动刷新，此时 `AICODE_AGENT_STATE` 为最终状态）、`manual`（手动点击刷新/错误重试）、`button`（卡片内刷新按钮触发） |

### 工作区变量

| 环境变量名 | 示例值 | 说明 |
| :--- | :--- | :--- |
| `AICODE_WORKSPACE` | `/root/workspace` | 当前工作区在容器内的根路径，恒为 `~/workspace`（展开为 `/root/workspace`）；宿主真实路径不会传给脚本 |
| `AICODE_WORKSPACE_NAME` | `my-app` | 工作区目录名 |

### 提供商变量

| 环境变量名 | 示例值 | 说明 |
| :--- | :--- | :--- |
| `AICODE_PROVIDER_NAME` | `OpenAI API` | 提供商名称 |
| `AICODE_PROVIDER_TYPE` | `OPENAI` | 协议类型（OPENAI / ANTHROPIC / GEMINI） |
| `AICODE_PROVIDER_API_KEY` | `sk-...` | 提供商 API Key |
| `AICODE_PROVIDER_BASE_URL` | `https://api.openai.com/` | 提供商 Base URL |

---

## 3. 设计理念与架构原则

- **声明式描述 (Declarative UI)**：脚本仅负责通过标准输出吐出 JSON 描述树，不侵入 Android 原生绘制代码。
- **纯原生 Compose 渲染**：由原生 Jetpack Compose 组件树进行解析与绘制，保持流畅的展开/折叠动效。
- **自动主题适配**：所有语义化颜色（如 `Good`, `Warning`, `Attention`, `Accent`, `Subtle`）会自动跟随 App 的明亮/暗黑主题智能变色，无需硬编码多套主题。
- **双态支持 (Dual-State Architecture)**：
  - **收起态 (Compact View)**：位于输入框上方的紧凑单行条目（高度约 28dp~34dp），用于常驻展示核心余量或状态。
  - **展开态 (Expanded View)**：点击展开后的丰富卡片面板（自适应高度），支持多列网格、明细账单、大字指标块及操作按钮。
- **事件驱动的实时刷新机制**：
  - **每次 LLM 请求返回时即时刷新**：在 Agent 工作流中，每一次单次 API 请求完成（获得最新 input/output Tokens 时），App 会自动带上最新上下文重新执行脚本并刷新面板；
  - **进入页面与手动刷新**：首次进入会话、在设置中修改脚本、或点击面板刷新按钮均会即时触发刷新。

---

## 4. 根结构定义 (Root Card Schema)

面板脚本的标准输出应输出如下根结构：

```json
{
  "type": "AdaptiveCard",
  "version": "1.5",
  "compact": {
    "type": "Row",
    "items": [ ... ]
  },
  "body": [ ... ]
}
```

### 根字段属性说明

| 字段 | 类型 | 必填 | 默认值 | 说明 |
| :--- | :--- | :--- | :--- | :--- |
| `type` | String | 是 | - | 固定为 `"AdaptiveCard"` |
| `version` | String | 否 | `"1.5"` | 规范版本号，推荐 `"1.5"` |
| `compact` | Container | 否 | `null` | **收起态**内容定义（单行紧凑容器）。若缺省，系统将自动从 `body` 提取前 2~3 个主要指标智能生成 |
| `body` | Array | 是 | `[]` | **展开态**卡片主体容器与组件树列表。按钮等交互元素也需放在 `body` 中（见下方 `ActionButton`） |

---

## 5. 组件与容器全集规范 (Elements Reference)

### 5.1 容器与排版类组件

> **通用属性 `visible`**：所有组件均可传 `"visible": false` 隐藏该元素，解析时直接跳过不渲染。适合根据环境变量动态控制显隐。

#### `ColumnSet`（多列网格容器）
将子元素按横向多列并排排布，是构建多周期指标、左右对比卡片的核心容器。

- **属性说明**：
  | 属性 | 类型 | 默认值 | 说明 |
  | :--- | :--- | :--- | :--- |
  | `type` | String | 固定 `"ColumnSet"` | - |
  | `columns` | Array<Column\> | `[]` | 包含的列对象列表 |
  | `spacing` | String / Number | `"Medium"` | 列间距枚举或数字（如 `8`、`"10dp"`） |
  | `gap` | Number / String | `null` | 列与列之间的精确间距（如 `8`、`"12dp"`，优先级高于 `spacing`） |
  | `padding` | Number / Array / Object | `null` | 容器内边距（如 `8`、`[4, 8]` 或 `{ "top": 4, "right": 8 }`） |
  | `margin` | Number / Array / Object | `null` | 容器外边距 |
  | `minHeight` | Number / String | `null` | 最小高度约束（如 `48`、`"48dp"`） |
  | `horizontalAlignment` | String | `"Left"` | 水平对齐：`"Left"`, `"Center"`, `"Right"` |

#### `Column`（单个列）
`ColumnSet` 的子元素，内部垂直堆叠各个内容组件。

- **属性说明**：
  | 属性 | 类型 | 默认值 | 说明 |
  | :--- | :--- | :--- | :--- |
  | `type` | String | 固定 `"Column"` | - |
  | `width` | String / Number | `"stretch"` | 宽度模式：<br>• `"auto"`：根据内容自适应最小宽度<br>• `"stretch"`：均分剩余可用空间<br>• `"1"`, `"2"`, `"3"` 等数字：弹性权重比例<br>• `"50px"`, `"50dp"`：固定宽度 |
  | `separator` | Boolean | `false` | 为 `true` 时与前一列之间显示垂直细分割线 |
  | `spacing` | String | `"None"` | 列内子元素纵向间距枚举：`"None"`, `"Small"`, `"Medium"`, `"Large"` |
  | `gap` | Number / String | `null` | 列内子元素精确纵向间距（如 `4`、`"6dp"`，优先级高于 `spacing`） |
  | `padding` | Number / Array / Object | `null` | 列内边距（如 `4`、`[2, 4]` 等） |
  | `minHeight` / `maxHeight` | Number / String | `null` | 最小/最大高度约束 |
  | `minWidth` / `maxWidth` | Number / String | `null` | 最小/最大宽度约束 |
  | `verticalContentAlignment` | String | `"Top"` | 列内纵向对齐：`"Top"`, `"Center"`, `"Bottom"` |
  | `items` | Array<Element\> | `[]` | 列内垂直排列的组件列表 |

#### `Container`（通用块级容器）
用于将若干元素打包成一个独立的卡片、高亮区块或分组背景。

- **属性说明**：
  | 属性 | 类型 | 默认值 | 说明 |
  | :--- | :--- | :--- | :--- |
  | `type` | String | 固定 `"Container"` | - |
  | `style` | String | `"Default"` | 容器风格：`"Default"`, `"Subtle"`, `"Emphasis"`, `"Good"`, `"Warning"`, `"Attention"`, `"Accent"` |
  | `bleed` | Boolean | `false` | 是否扩展至父容器边缘 |
  | `spacing` | String / Number | `"None"` | 容器与上方元素的间距 |
  | `gap` | Number / String | `null` | 容器内子元素的纵向间隔（如 `6`、`"8dp"`） |
  | `padding` | Number / Array / Object | `null` | 容器内边距 |
  | `margin` | Number / Array / Object | `null` | 容器外边距 |
  | `cornerRadius` | Number / String | `null` | 自定义背景和边框圆角半径（如 `8`、`"8dp"`） |
  | `minHeight` / `maxHeight` | Number / String | `null` | 容器高度约束 |
  | `minWidth` / `maxWidth` | Number / String | `null` | 容器宽度约束 |
  | `items` | Array<Element\> | `[]` | 容器内的子组件树 |

#### `Row`（单行水平排列）
用于将多个小元素（如标签 + 状态点 + 文字）在同一行紧凑横向排布，常用于 `compact` 收起态。

- **属性说明**：
  | 属性 | 类型 | 默认值 | 说明 |
  | :--- | :--- | :--- | :--- |
  | `type` | String | 固定 `"Row"` | - |
  | `spacing` | String / Number | `"Small"` | 行内子元素间距枚举或数值 |
  | `gap` | Number / String | `null` | 子元素间精确间距（如 `4`、`"6dp"`） |
  | `padding` / `margin` | Number / Array / Object | `null` | 内/外边距 |
  | `verticalAlignment` | String | `"Center"` | 纵向对齐方式：`"Top"`, `"Center"`, `"Bottom"` |
  | `items` | Array<Element\> | `[]` | 水平排列的子元素列表 |

> **Row 子元素 weight**：Row 中每个子元素可额外传 `"weight"` 数值属性控制弹性宽度比例。例如 `{ "type": "TextBlock", "text": "...", "weight": 2 }` 让该文本块占 2 份宽度。未指定 `weight` 的 Column / ColumnSet 默认 `weight=1`，其他类型默认不拉伸。

#### `Spacer`（空白占位）
显式插入空白间距，用于在 Row / Column / Container 中精确控制元素间距。

- **属性说明**：
  | 属性 | 类型 | 默认值 | 说明 |
  | :--- | :--- | :--- | :--- |
  | `type` | String | 固定 `"Spacer"` | - |
  | `height` | Number / String | `null` | 固定高度（dp），在 Column / Container 中生效 |
  | `width` | Number / String | `null` | 固定宽度（dp），在 Row 中生效 |

> 两者均缺省时默认占 `Small` 高度。如需在 Row 中弹性占位，请使用 Row 子元素的 `weight` 属性。

#### `FlowRow`（自动换行流式布局）
子元素横向排列并自动换行，适合 Badge 群、标签列表等数量不固定的场景。

- **属性说明**：
  | 属性 | 类型 | 默认值 | 说明 |
  | :--- | :--- | :--- | :--- |
  | `type` | String | 固定 `"FlowRow"` | - |
  | `items` | Array<Element\> | `[]` | 子元素列表 |
  | `gap` | Number / String | `null` | 水平间距（dp） |
  | `verticalGap` | Number / String | `null` | 换行后的垂直间距（dp） |
  | `padding` / `margin` | Number / Array / Object | `null` | 内/外边距 |
  | `minHeight` / `maxHeight` | Number / String | `null` | 高度约束 |

#### `ScrollRow`（横向滚动容器）
子元素横向排列且可滑动滚动，适合内容超出屏宽时使用。

- **属性说明**：
  | 属性 | 类型 | 默认值 | 说明 |
  | :--- | :--- | :--- | :--- |
  | `type` | String | 固定 `"ScrollRow"` | - |
  | `items` | Array<Element\> | `[]` | 子元素列表 |
  | `gap` | Number / String | `null` | 子元素间距（dp） |
  | `padding` / `margin` | Number / Array / Object | `null` | 内/外边距 |
  | `minHeight` / `maxHeight` | Number / String | `null` | 高度约束 |

#### `TabSet`（标签页容器）
在有限空间内通过 Tab 切换展示多组内容，适合将余量、Token、费用等信息分页展示。

- **属性说明**：
  | 属性 | 类型 | 默认值 | 说明 |
  | :--- | :--- | :--- | :--- |
  | `type` | String | 固定 `"TabSet"` | - |
  | `tabs` | Array<Tab\> | `[]` | 标签页数组 |
  | `tabPosition` | String | `"top"` | 标签栏位置：`"top"`（上方）/ `"bottom"`（下方） |
  | `tabStyle` | String | `"primary"` | 标签栏样式：`"primary"`（Material 指示条）/ `"pills"`（胶囊按钮） |
  | `indicatorColor` | String | `null` | 选中态强调色（语义色或 Hex），影响指示条/胶囊/选中文字 |
  | `tabBackgroundColor` | String | `null` | 标签栏背景色（语义色或 Hex） |
  | `tabContentColor` | String | `null` | 未选中标签的文字颜色（语义色或 Hex） |
  | `cornerRadius` | Number / String | `null` | `pills` 样式下胶囊的圆角半径（dp） |
  | `padding` / `margin` | Number / Array / Object | `null` | 内/外边距 |

- **Tab 子元素属性**：
  | 属性 | 类型 | 默认值 | 说明 |
  | :--- | :--- | :--- | :--- |
  | `type` | String | 固定 `"Tab"` | - |
  | `label` | String | `""` | 标签页标题文本 |
  | `icon` | String | `null` | 标签页图标名称（如 `"external-link"`、`"copy"`、`"help"`） |
  | `badge` | String | `null` | 标签右上角小徽章文本（如 `"3"`、`"!"`） |
  | `color` | String | `null` | 该标签选中时的强调色（语义色或 Hex，优先于 TabSet 的 `indicatorColor`） |
  | `items` | Array<Element\> | `[]` | 该标签页下的子组件列表 |

> **收起态行为**：compact 态下 TabSet 不渲染 Tab 栏，只展示第一个 Tab 的内容。

---

### 5.2 核心展示组件

#### `TextBlock`（文本块）
最基础也是最常用的文本排版组件。

- **属性说明**：
  | 属性 | 类型 | 默认值 | 说明 |
  | :--- | :--- | :--- | :--- |
  | `type` | String | 固定 `"TextBlock"` | - |
  | `text` | String | `""` | 文本内容（支持加粗 `**text**` 与行内代码 \`code\` 等轻量语法） |
  | `size` | String / Number | `"Default"` | 字号：<br>• 语义档位：`"Micro"`(10sp), `"Small"`(12sp), `"Default"`(14sp), `"Medium"`(16sp), `"Large"`(18sp), `"ExtraLarge"`(22sp)<br>• 任意数值：支持直接传入 `15`、`24` 或 `"24sp"` |
  | `lineHeight` | Number / String | `null` | 行高：支持 `20`、`"20sp"`，方便多行排版或与图标垂直居中 |
  | `weight` | String | `"Default"` | 字重：`"Lighter"` (细), `"Default"` (常规), `"Bolder"` (加粗) |
  | `color` | String | `"Default"` | 文字颜色：语义色或 `#10B981` 等 Hex 颜色 |
  | `isSubtle` | Boolean | `false` | 为 `true` 时自动降低透明度，呈现次要文字灰度效果 |
  | `maxLines` | Int | `null` | 最大行数限制，超出自动显示省略号（`...`） |
  | `horizontalAlignment` | String | `"Left"` | 对齐：`"Left"`, `"Center"`, `"Right"`, `"Justify"` |
  | `padding` / `margin` | Number / Array / Object | `null` | 内/外边距 |

#### `ProgressBar`（进度条）
直观展示配额使用率、时间倒计时或 Token 消耗进度。

- **属性说明**：
  | 属性 | 类型 | 默认值 | 说明 |
  | :--- | :--- | :--- | :--- |
  | `type` | String | 固定 `"ProgressBar"` | - |
  | `value` | Float / Int | `0` | 当前百分比数值（`0` ~ `100`）。支持浮点数如 `82.5` |
  | `color` | String | `"Accent"` | 进度条高亮条填充色（语义色或 Hex 自定义颜色） |
  | `trackColor` | String | `null` | 进度条底部背景轨道颜色。默认自动使用半透明灰色 |
  | `height` | Int / String | `6` | 进度条粗细（dp），收起态建议 `3`~`4`，展开态建议 `6`~`8` |
  | `animated` | Boolean | `true` | 是否在数值变动时播放平滑过渡动画 |
  | `cornerRadius` | Int / String | `null` | 轨道圆角半径（dp），缺省为全圆胶囊（`CircleShape`） |
  | `showPercent` | Boolean | `false` | 是否在进度条轨道内居中显示百分比数值 |
  | `text` | String | `null` | 在进度条轨道内居中显示的自定义文本（如 `"已用 80%"`） |
  | `textColor` | String | `null` | 轨道内嵌文本颜色（默认自适应底色） |

#### `Metric`（指标数值卡片 / StatBlock）
专为面板设计的大字数据组件，内置规范的「标题 + 大字主数值 + 变化/单位 + 副文本」组合。

- **属性说明**：
  | 属性 | 类型 | 默认值 | 说明 |
  | :--- | :--- | :--- | :--- |
  | `type` | String | 固定 `"Metric"` | - |
  | `label` | String | `""` | 上方小标签标题（如 `"5h 余量"`, `"当前余额"`） |
  | `value` | String | `""` | 中间核心大字（如 `"$12.45"`, `"80%"`） |
  | `unit` | String | `""` | 紧随数值的小单位/后缀（如 `"CNY"`, `"Tokens"`, `"/ 5h"`） |
  | `subText` | String | `""` | 底部副文本（如 `"≈ ¥89.32"`, `"今日消耗 $0.83"`） |
  | `percent` | Float | `null` | 若传入百分比，会在数值下方自动附带圆角进度条 |
  | `color` | String | `"Default"` | 主题色（影响数值与进度条高亮） |
  | `trend` | String | `null` | 趋势标记：`"Up"` (▲ 绿色), `"Down"` (▼ 红色), `"Neutral"` |

#### `Badge` / `Tag`（胶囊徽章）
用于展示状态标签（如 `"按量计费"`, `"企业版"`, `"额度充足"`, `"即将重置"`）。

- **属性说明**：
  | 属性 | 类型 | 默认值 | 说明 |
  | :--- | :--- | :--- | :--- |
  | `type` | String | 固定 `"Badge"` | - |
  | `text` | String | `""` | 徽章内的短文本 |
  | `style` | String | `"Default"` | 风格：`"Good"` (浅绿底绿字), `"Warning"` (浅黄底黄字), `"Attention"` (浅红底红字), `"Accent"` (浅蓝底蓝字), `"Subtle"` (灰底黑字) |
  | `icon` | String | `null` | 徽章前置小图标名称 |

#### `StatusDot`（状态呼吸小圆点）
极简的状态指示圆点，常用于收起态中快速指示连通性与余额健康度。

- **属性说明**：
  | 属性 | 类型 | 默认值 | 说明 |
  | :--- | :--- | :--- | :--- |
  | `type` | String | 固定 `"StatusDot"` | - |
  | `color` | String | `"Good"` | 圆点颜色（`"Good"`, `"Warning"`, `"Attention"`, 或 Hex） |
  | `size` | Int | `6` | 圆点直径（dp，默认 6dp） |

#### `FactSet`（键值对明细列表）
用于整齐展示多项细则数据（如重置时间、RPM/TPM 限额、账单明细）。

- **属性说明**：
  | 属性 | 类型 | 默认值 | 说明 |
  | :--- | :--- | :--- | :--- |
  | `type` | String | 固定 `"FactSet"` | - |
  | `facts` | Array<Fact\> | `[]` | 键值对数组，每项为 `{ "title": "标题", "value": "内容" }` |

#### `Image`（图标元素）
渲染内置矢量图标。当前支持以下图标名称（不区分大小写）：`external-link`、`copy`、`help`。

- **属性说明**：
  | 属性 | 类型 | 默认值 | 说明 |
  | :--- | :--- | :--- | :--- |
  | `type` | String | 固定 `"Image"` | - |
  | `icon` | String | `null` | 图标名称（如 `"external-link"`、`"copy"`、`"help"`） |
  | `size` | Int | `16` | 图标尺寸（dp） |
  | `color` | String | `"Default"` | 图标颜色（语义色或 Hex） |
  | `padding` / `margin` | Number / Array / Object | `null` | 内/外边距 |

#### `Divider`（分割线）
横向分隔线，用于划分卡片的不同内容区块。

- **属性说明**：
  | 属性 | 类型 | 默认值 | 说明 |
  | :--- | :--- | :--- | :--- |
  | `type` | String | 固定 `"Divider"` | - |
  | `spacing` | String | `"Medium"` | 分割线上下外边距：`"Small"`, `"Medium"`, `"Large"` |

---

### 5.3 交互动作类 (Actions)

### 5.3 交互按钮类 (ActionButton)

按钮可作为普通元素放在 `body` 任意位置（Row / Column / Container / FlowRow 等），替代旧的根级 `actions` 字段。

#### `ActionButton`（可点击按钮）
点击打开外部链接或复制文本到剪贴板。

- **属性说明**：
  | 属性 | 类型 | 必填 | 默认值 | 说明 |
  | :--- | :--- | :--- | :--- |
  | `type` | String | 是 | - | 固定 `"ActionButton"`（别名 `"button"`、`"action"`） |
  | `title` | String | 是 | - | 按钮文本（如 `"前往充值"`, `"复制卡密"`） |
  | `action` | String | 否 | `"openUrl"` | 行为类型：`"openUrl"`（打开链接）/ `"copy"`（复制文本）/ `"refresh"`（刷新面板） |
  | `url` | String | 条件 | - | `action=openUrl` 时必填，完整的 HTTP/HTTPS 链接 |
  | `value` | String | 条件 | - | `action=copy` 时必填，要复制的文本内容 |
  | `icon` | String | 否 | `null` | 按钮前置图标名称（如 `"external-link"`、`"copy"`） |
  | `style` | String | 否 | `"Default"` | 按钮背景风格：`"Default"`（纯色淡底）、`"Subtle"`、`"Good"`、`"Warning"`、`"Attention"`、`"Accent"` |
  | `color` | String | 否 | `"Accent"` | 按钮文字/图标颜色（语义色或 Hex） |
  | `padding` / `margin` | Number / Array / Object | 否 | `null` | 内/外边距 |

示例：

```json
{
  "type": "Row",
  "items": [
    { "type": "TextBlock", "text": "余额不足？", "weight": 1 },
    {
      "type": "ActionButton",
      "title": "前往充值",
      "action": "openUrl",
      "url": "https://api.openai.com",
      "icon": "external-link",
      "style": "Accent",
      "color": "Accent"
    }
  ]
}
```

---

## 6. 色彩规范与主题映射表

Adaptive Cards 使用语义色彩系统，确保在亮色模式（Light Mode）与深色模式（Dark Mode）下均具备优秀的对比度与视觉美感：

| 语义颜色 (Color Key) | 浅色主题视觉呈现 | 深色主题视觉呈现 | 适用业务场景 |
| :--- | :--- | :--- | :--- |
| **`Good`** | 翠绿 `#10B981` | 清新亮绿 `#34D399` | 余额充足、配额充裕、服务正常 |
| **`Warning`** | 琥珀橙 `#F59E0B` | 明亮橙黄 `#FBBF24` | 额度低于 20%、即将触发限频 |
| **`Attention`** | 玫红/警报红 `#EF4444` | 浅警报红 `#F87171` | 配额耗尽、欠费停机、脚本查询异常 |
| **`Accent`** | 品牌蓝/科技紫 `#3B82F6` | 浅科技蓝 `#60A5FA` | 主指标高亮、次要周期、普通进度 |
| **`Default`** | 深灰黑 `#1F2937` | 纯白灰 `#F3F4F6` | 主标题、核心大字金额 |
| **`Subtle`** | 弱化灰 `#6B7280` | 次要浅灰 `#9CA3AF` | 辅助单位、副说明、折算汇率 |

---

## 7. 经典实战模板全景示例

### 场景 A：订阅制多周期面板（5h 快速周期 + 7d 周期 + 1m 月度包）

```json
{
  "type": "AdaptiveCard",
  "version": "1.5",
  "compact": {
    "type": "ColumnSet",
    "spacing": "Medium",
    "columns": [
      {
        "type": "Column",
        "width": "stretch",
        "items": [
          { "type": "TextBlock", "text": "5h 80%", "size": "Small", "weight": "Bolder", "color": "Good" },
          { "type": "ProgressBar", "value": 80, "color": "Good", "height": 3 }
        ]
      },
      {
        "type": "Column",
        "width": "stretch",
        "items": [
          { "type": "TextBlock", "text": "7d 65%", "size": "Small", "weight": "Bolder", "color": "Accent" },
          { "type": "ProgressBar", "value": 65, "color": "Accent", "height": 3 }
        ]
      },
      {
        "type": "Column",
        "width": "stretch",
        "items": [
          { "type": "TextBlock", "text": "1m 92%", "size": "Small", "weight": "Bolder", "color": "Subtle" },
          { "type": "ProgressBar", "value": 92, "color": "Subtle", "height": 3 }
        ]
      }
    ]
  },
  "body": [
    {
      "type": "ColumnSet",
      "columns": [
        {
          "type": "Column",
          "width": "stretch",
          "items": [
            { "type": "TextBlock", "text": "5h 周期", "size": "Small", "isSubtle": true },
            { "type": "TextBlock", "text": "80%", "size": "Medium", "weight": "Bolder", "color": "Good" },
            { "type": "ProgressBar", "value": 80, "color": "Good" },
            { "type": "TextBlock", "text": "4.0 / 5.0 小时", "size": "Small", "isSubtle": true }
          ]
        },
        {
          "type": "Column",
          "width": "stretch",
          "separator": true,
          "items": [
            { "type": "TextBlock", "text": "7d 周期", "size": "Small", "isSubtle": true },
            { "type": "TextBlock", "text": "65%", "size": "Medium", "weight": "Bolder", "color": "Accent" },
            { "type": "ProgressBar", "value": 65, "color": "Accent" },
            { "type": "TextBlock", "text": "4.5 / 7.0 天", "size": "Small", "isSubtle": true }
          ]
        },
        {
          "type": "Column",
          "width": "stretch",
          "separator": true,
          "items": [
            { "type": "TextBlock", "text": "1m 周期", "size": "Small", "isSubtle": true },
            { "type": "TextBlock", "text": "92%", "size": "Medium", "weight": "Bolder", "color": "#8B5CF6" },
            { "type": "ProgressBar", "value": 92, "color": "#8B5CF6" },
            { "type": "TextBlock", "text": "27.6 / 30 天", "size": "Small", "isSubtle": true }
          ]
        }
      ]
    }
  ]
}
```

---

### 场景 B：消费账单 + 模型/Token 动态看板

```json
{
  "type": "AdaptiveCard",
  "version": "1.5",
  "compact": {
    "type": "Row",
    "items": [
      { "type": "StatusDot", "color": "Good" },
      { "type": "TextBlock", "text": "可用余额 $18.42", "weight": "Bolder" },
      { "type": "TextBlock", "text": "(今日 $0.45)", "size": "Small", "isSubtle": true }
    ]
  },
  "body": [
    {
      "type": "ColumnSet",
      "columns": [
        {
          "type": "Column",
          "width": "stretch",
          "items": [
            { "type": "TextBlock", "text": "当前可用余额", "size": "Small", "isSubtle": true },
            { "type": "TextBlock", "text": "$18.42", "size": "ExtraLarge", "weight": "Bolder", "color": "Good" },
            { "type": "TextBlock", "text": "≈ ¥132.60 CNY", "size": "Small", "isSubtle": true }
          ]
        },
        {
          "type": "Column",
          "width": "stretch",
          "separator": true,
          "items": [
            { "type": "TextBlock", "text": "本月累计消费", "size": "Small", "isSubtle": true },
            { "type": "TextBlock", "text": "$6.58", "size": "ExtraLarge", "weight": "Bolder", "color": "Accent" },
            { "type": "TextBlock", "text": "今日消耗 $0.45", "size": "Small", "isSubtle": true }
          ]
        }
      ]
    },
    { "type": "Divider" },
    {
      "type": "FactSet",
      "facts": [
        { "title": "当前模型", "value": "claude-3-7-sonnet" },
        { "title": "本次请求 Token", "value": "↑ 1,420 / ↓ 365" },
        { "title": "会话累计 Token", "value": "28,540" }
      ]
    },
    {
      "type": "Row",
      "items": [
        { "type": "TextBlock", "text": "", "weight": 1 },
        {
          "type": "ActionButton",
          "title": "管理控制台",
          "action": "openUrl",
          "url": "https://api.openai.com",
          "icon": "external-link"
        }
      ]
    }
  ]
}
```

---

## 8. AI 助手编写面板脚本的设计建议

1. **利用传入的环境变量**：
   通过 `os.environ.get("AICODE_LAST_INPUT_TOKENS", "0")` 等读取每次单次请求的真实 Token 数据，无缝拼装出当次请求的 Token 消耗、缓存命中率或费用估算。
2. **信息分层**：
   - `compact` 保持单行精炼（如 `[🟢 正常] 5h: 80% | $18.42`）；
   - `body` 提供多列展开、账单细则与操作按钮。
3. **异常兜底**：
   当网络超时或 API 错误时，输出带 `StatusDot(color="Attention")` 的卡片，友好提示异常。

---

### 场景 C：多排标签流式布局 + 横向滚动指标

```json
{
  "type": "AdaptiveCard",
  "version": "1.5",
  "compact": {
    "type": "Row",
    "items": [
      { "type": "StatusDot", "color": "Good" },
      { "type": "TextBlock", "text": "正常", "weight": "Bolder" },
      { "type": "TextBlock", "text": "", "weight": 1 },
      { "type": "Badge", "text": "Pro", "style": "Accent", "icon": "external-link" }
    ]
  },
  "body": [
    {
      "type": "Container",
      "style": "Subtle",
      "cornerRadius": 8,
      "items": [
        { "type": "TextBlock", "text": "可用模型", "weight": "Bolder", "size": "Medium" },
        { "type": "Spacer", "height": 4 },
        {
          "type": "FlowRow",
          "gap": 6,
          "verticalGap": 4,
          "items": [
            { "type": "Badge", "text": "GPT-4o", "style": "Accent" },
            { "type": "Badge", "text": "Claude 3.7", "style": "Good" },
            { "type": "Badge", "text": "Gemini 2.0", "style": "Warning" },
            { "type": "Badge", "text": "DeepSeek", "style": "Subtle" }
          ]
        }
      ]
    },
    { "type": "Spacer", "height": 8 },
    {
      "type": "ScrollRow",
      "gap": 12,
      "items": [
        { "type": "Metric", "label": "余额", "value": "$18.42", "color": "Good" },
        { "type": "Metric", "label": "今日", "value": "$0.45", "color": "Accent" },
        { "type": "Metric", "label": "本月", "value": "$6.58", "color": "Warning" },
        { "type": "Metric", "label": "配额", "value": "82%", "color": "Attention" }
      ]
    }
  ]
}
```
