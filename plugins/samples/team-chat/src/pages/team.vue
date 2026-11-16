<template>
  <div id="app">
    <main class="app">
        <header>
          <div>
            <h1>团队群聊</h1>
            <p class="hint">每个成员一个模型句柄；发送时依次请求，后发言的成员能看到前面的回复。模型可调用 team_vote 或 writeFile，写文件前会在页面内请求确认。</p>
          </div>
        </header>

        <section class="members">
          <article v-for="(member, index) in members" :key="member.key" class="member">
            <div class="member-head">
              <strong>{{ member.label }}</strong>
              <input v-model="member.name" :aria-label="member.label + ' 名称'">
            </div>
            <label>供应商
              <select v-model="member.providerId" @change="loadModels(index, $event.target.value)">
                <option value="">{{ providers.length ? '选择供应商' : '没有可用供应商' }}</option>
                <option v-for="provider in providers" :key="provider.id" :value="provider.id">{{ provider.name }}{{ provider.isDefault ? '（默认）' : '' }}</option>
              </select>
            </label>
            <label>模型
              <select v-model="member.model">
                <option value="">{{ modelPlaceholder(member) }}</option>
                <option v-for="model in member.models" :key="model.id" :value="model.id">{{ model.displayName }} · {{ model.id }}</option>
              </select>
            </label>
            <label>提示词<input v-model="member.prompt"></label>
          </article>
        </section>

        <div class="actions">
          <button type="button" @click="loadProviders">重新加载供应商</button>
          <button type="button" :disabled="applying" @click="applyMembers">创建 / 更新句柄</button>
        </div>

        <section v-if="approval" class="approval">
          <div class="approval-head">
            <strong>{{ approval.title || '需要确认' }}</strong>
            <span class="approval-tool">{{ approval.toolName || '' }}</span>
          </div>
          <p class="approval-summary">{{ approval.summary || '' }}</p>
          <pre class="approval-details">{{ approval.details || approval.argsPreview || '' }}</pre>
          <div class="actions">
            <button type="button" data-choice="once" :disabled="approvalBusy" @click="resolveApproval('once')">允许一次</button>
            <button v-if="approvalRememberable" type="button" data-choice="always" :disabled="approvalBusy" @click="resolveApproval('always')">始终允许</button>
            <button type="button" data-choice="reject" :disabled="approvalBusy" @click="resolveApproval('reject')">拒绝</button>
          </div>
        </section>

        <section ref="chatEl" class="chat">
          <article v-for="message in chatMessages" :key="message.id" class="bubble" :class="[message.role, { error: message.error }]">
            <div class="bubble-head">
              <span>{{ message.name }}</span>
              <span>{{ message.meta }}</span>
            </div>
            <div class="bubble-body">
              <span>{{ message.content }}</span>
              <img v-for="(image, imageIndex) in message.images" :key="imageIndex" class="bubble-image" :src="image.src" alt="">
            </div>
          </article>
        </section>

        <div v-if="pendingImage" class="attachment">
          <img :src="pendingImageSrc" alt="待发送图片">
          <span>{{ pendingImageLabel }}</span>
          <button type="button" @click="clearPendingImage">移除</button>
        </div>

        <form class="composer" @submit.prevent="sendGroupMessage">
          <input ref="imageInput" id="image-input" type="file" accept="image/png,image/jpeg,image/webp,image/gif" hidden @change="onImageChange">
          <button id="pick-image" type="button" title="添加图片" @click="pickImage">图片</button>
          <input id="input" v-model="inputText" placeholder="输入群聊消息…" autocomplete="off">
          <button id="send" type="submit" :disabled="sending">轮流发送</button>
        </form>
        <p class="status" :class="{ error: statusError }">{{ statusText }}</p>
      </main>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref } from 'vue';

const TOOLS = [
  {
    type: 'custom',
    name: 'team_vote',
    description: '记录群聊成员对某个议题的选择，并返回插件本地的投票统计。',
    inputSchema: {
      type: 'object',
      properties: {
        topic: {type: 'string', description: '议题'},
        options: {type: 'array', items: {type: 'string'}, description: '候选选项'},
        choice: {type: 'string', description: '本次选择'},
        voter: {type: 'string', description: '投票成员，缺省为当前发言成员'}
      },
      required: ['choice']
    }
  },
  {type: 'app', name: 'writeFile'}
];

const members = reactive([
  {
    key: 'a',
    label: '成员 A',
    name: '产品经理',
    prompt: '你是群聊里的产品经理，回答要简短，先给结论。',
    providerId: '',
    model: '',
    models: [],
    modelState: 'idle',
    supportsTools: true,
    supportsVision: true
  },
  {
    key: 'b',
    label: '成员 B',
    name: '架构师',
    prompt: '你是群聊里的架构师，指出风险与取舍，回答要简短。',
    providerId: '',
    model: '',
    models: [],
    modelState: 'idle',
    supportsTools: true,
    supportsVision: true
  }
]);

const providers = ref([]);
const statusText = ref('');
const statusError = ref(false);
const chatMessages = ref([]);
const chatEl = ref(null);
const inputText = ref('');
const pendingImage = ref(null);
const imageInput = ref(null);
const sending = ref(false);
const applying = ref(false);
const approval = ref(null);
const approvalBusy = ref(false);

const approvalRememberable = computed(
  () => (approval.value && approval.value.rememberablePatterns ? approval.value.rememberablePatterns.length : 0) > 0
);
const pendingImageSrc = computed(() => (pendingImage.value ? imageSource(pendingImage.value) : ''));
const pendingImageLabel = computed(() => {
  if (!pendingImage.value) return '';
  const image = pendingImage.value;
  return image.name + ' · ' + Math.max(1, Math.round(image.size / 1024)) + ' KiB';
});

let handles = [null, null];
let history = [];
let voteTally = {};
let messageSeq = 0;
let sessionId = 'plugin:demo.team-chat:team-chat';

function setStatus(text, isError) {
  statusText.value = text || '';
  statusError.value = isError === true;
}

function memberName(index) {
  return members[index].name.trim() || '成员 ' + (index + 1);
}

function modelPlaceholder(member) {
  if (!member.providerId) return '先选择供应商';
  if (member.modelState === 'loading') return '加载中…';
  if (member.modelState === 'error') return '加载失败';
  return member.models.length ? '选择模型' : '没有已添加的模型';
}

function imageSource(image) {
  return 'data:' + image.mimeType + ';base64,' + image.base64;
}

async function loadModels(index, providerId) {
  const member = members[index];
  if (providerId === undefined) providerId = member.providerId;
  member.providerId = providerId;
  member.models = [];
  member.model = '';
  if (!providerId) {
    member.modelState = 'idle';
    return;
  }
  member.modelState = 'loading';
  try {
    const result = await aicode.model.listModels({ providerId: providerId });
    member.models = result.models || [];
    member.modelState = 'ready';
    if (member.models.length === 1) member.model = member.models[0].id;
  } catch (e) {
    member.models = [];
    member.modelState = 'error';
    setStatus((e.code ? e.code + ': ' : '') + (e.message || String(e)), true);
  }
}

async function loadProviders() {
  setStatus('加载供应商…');
  try {
    const result = await aicode.model.listProviders();
    providers.value = result.providers.filter((provider) => provider.enabled && provider.hasKey);
    for (let index = 0; index < members.length; index++) {
      const member = members[index];
      if (!providers.value.some((provider) => provider.id === member.providerId)) {
        member.providerId = '';
        member.model = '';
      }
      if (!member.providerId && providers.value.length) {
        member.providerId = providers.value[0].id;
      }
      await loadModels(index);
    }
    setStatus(providers.value.length ? '' : '没有可用的供应商，请先在设置中添加并填写 Key。', !providers.value.length);
  } catch (e) {
    setStatus(e.message || String(e), true);
  }
}

async function applyMembers() {
  applying.value = true;
  setStatus('创建句柄…');
  try {
    for (let index = 0; index < members.length; index++) {
      const member = members[index];
      if (handles[index]) {
        try {
          await aicode.model.close(handles[index]);
        } catch (_) {
          // 插件重载或页面重开后旧句柄可能已被宿主清理。
        }
        handles[index] = null;
      }
      if (!member.providerId || !member.model) continue;
      const meta = member.models.find((item) => item.id === member.model);
      member.supportsTools = !meta || meta.supportsTools !== false;
      member.supportsVision = !meta || meta.supportsVision !== false;
      const created = await aicode.model.create({
        providerId: member.providerId,
        model: member.model,
        name: memberName(index),
        systemPrompt: member.prompt
      });
      handles[index] = created.handle.id;
    }
    const ready = handles.some(Boolean);
    setStatus(ready ? '句柄已就绪' : '至少为一个成员选择供应商和模型。', !ready);
  } catch (e) {
    setStatus(e.message || String(e), true);
  } finally {
    applying.value = false;
  }
}

async function scrollChat() {
  await nextTick();
  if (chatEl.value) chatEl.value.scrollTop = chatEl.value.scrollHeight;
}

function appendBubble(role, name) {
  const message = reactive({
    id: ++messageSeq,
    role: role,
    name: name,
    content: '',
    meta: '',
    images: [],
    error: false
  });
  chatMessages.value.push(message);
  void scrollChat();
  return message;
}

function setMessageImages(message, images) {
  message.images = (images || [])
    .filter((image) => image && image.base64)
    .map((image) => ({ src: imageSource(image) }));
}

// 群聊里多个成员会连续发言，相邻同角色消息会破坏 user/assistant 交替；
// 发送前把同角色消息合并，图片顺带拼接（单条最多 4 张）。
function buildMessages() {
  const merged = [];
  history.forEach((item) => {
    const last = merged[merged.length - 1];
    if (last && last.role === item.role && item.role !== 'tool') {
      last.content = [last.content, item.content].filter(Boolean).join('\n\n');
      if (item.images && item.images.length) {
        last.images = (last.images || []).concat(item.images).slice(-4);
      }
      return;
    }
    merged.push({
      role: item.role,
      content: item.content,
      images: item.images ? item.images.slice() : undefined
    });
  });
  // 旧图片不重复上传：内联图片单次总量只有 2 MiB，只保留最近 2 张。
  let imageBudget = 2;
  for (let index = merged.length - 1; index >= 0; index--) {
    const message = merged[index];
    if (!message.images || message.images.length === 0) continue;
    if (imageBudget <= 0) {
      delete message.images;
      continue;
    }
    message.images = message.images.slice(-imageBudget);
    imageBudget -= message.images.length;
  }
  return merged;
}

function messagesForMember(messages, index) {
  if (members[index].supportsVision !== false) return messages;
  return messages.map((message) => {
    if (!message.images) return message;
    const copy = Object.assign({}, message);
    delete copy.images;
    return copy;
  });
}

function callArguments(call) {
  if (call.arguments && typeof call.arguments === 'object') return call.arguments;
  if (typeof call.arguments === 'string') {
    try {
      return JSON.parse(call.arguments);
    } catch (_) {
      return {};
    }
  }
  return {};
}

function assistantMessage(final, calls) {
  return {
    role: 'assistant',
    content: final.content || '',
    reasoning: final.reasoning,
    providerState: final.providerState,
    toolCalls: calls.map((call) => {
      return {
        id: call.id,
        name: call.name,
        arguments: call.argumentsTruncated ? {} : callArguments(call)
      };
    })
  };
}

function runTeamVote(call, memberIndex) {
  const args = callArguments(call);
  const topic = String(args.topic || '未命名议题');
  const choice = String(args.choice || '');
  if (!choice) {
    return JSON.stringify({ error: 'MISSING_CHOICE', message: 'team_vote 需要 choice' });
  }
  const tally = voteTally[topic] || (voteTally[topic] = {});
  (Array.isArray(args.options) ? args.options : []).forEach((option) => {
    const key = String(option);
    if (tally[key] == null) tally[key] = 0;
  });
  tally[choice] = (tally[choice] || 0) + 1;
  return JSON.stringify({
    topic: topic,
    voter: String(args.voter || memberName(memberIndex)),
    choice: choice,
    tally: tally
  });
}

function setApproval(pending) {
  approval.value = pending ? pending.approval : null;
}

// execute 遇到 ASK 会一直挂起；轮询 pending 把审批条显示出来，resolve 后 execute 才继续。
async function executeHostTool(name, args) {
  const timer = setInterval(async () => {
    try {
      const pending = await aicode.tools.pending(sessionId);
      setApproval(pending);
    } catch (_) {
      // 页面关闭或会话失效时忽略，execute 本身会返回错误。
    }
  }, 700);
  try {
    return await aicode.tools.execute(name, args, { sessionId: sessionId, timeoutMs: 120000 });
  } finally {
    clearInterval(timer);
    setApproval(null);
  }
}

async function resolveApproval(choice) {
  if (!approval.value || approvalBusy.value) return;
  const requestId = approval.value.id;
  approvalBusy.value = true;
  try {
    await aicode.tools.resolve(requestId, choice);
    setStatus('已提交审批：' + choice);
  } catch (e) {
    setStatus((e.code ? e.code + ': ' : '') + (e.message || String(e)), true);
  } finally {
    approvalBusy.value = false;
  }
}

async function runTool(call, memberIndex, message) {
  if (call.argumentsTruncated) {
    return JSON.stringify({ error: 'ARGUMENTS_TRUNCATED', message: '工具参数被截断，请减少内容后重试' });
  }
  if (call.name === 'team_vote') {
    message.meta = '本地执行 team_vote…';
    return runTeamVote(call, memberIndex);
  }
  if (call.name === 'writeFile') {
    message.meta = '等待宿主执行 writeFile…';
    try {
      const result = await executeHostTool('writeFile', callArguments(call));
      message.meta = result.status === 'success' ? 'writeFile 已完成' : 'writeFile 返回 ' + result.status;
      return JSON.stringify(result);
    } catch (e) {
      message.meta = 'writeFile 未完成';
      return JSON.stringify({ error: e.code || 'TOOL_ERROR', message: e.message || String(e) });
    }
  }
  return JSON.stringify({ error: 'UNKNOWN_TOOL', message: '未知工具：' + call.name });
}

async function requestMember(index, messages, message) {
  let streamed = '';
  let current = messages;
  const tools = members[index].supportsTools === false ? undefined : TOOLS;
  let prompt = members[index].prompt;
  if (tools) {
    prompt += '\n\n需要表决时调用 team_vote；需要把结论落盘时调用 writeFile。';
  }

  const onEvent = (event) => {
    if (event.type === 'text') {
      streamed += event.text;
      message.content = streamed;
      void scrollChat();
    } else if (event.type === 'toolCall') {
      message.meta = '调用工具 ' + event.name + '…';
    } else if (event.type === 'retry') {
      message.meta = '重试中…';
    } else if (event.type === 'keySwitch') {
      message.meta = '切换 Key ' + event.index + '/' + event.total;
    }
  };

  for (let round = 0; round < 3; round++) {
    if (round > 0 && streamed) {
      streamed += '\n\n';
      message.content = streamed;
    }
    const result = await aicode.model.stream({
      handleId: handles[index],
      messages: current,
      tools: tools,
      systemPrompt: prompt
    }, onEvent);

    if (result.status !== 'success') return result;
    const calls = (result.final && result.final.toolCalls) || [];
    if (calls.length === 0) return result;

    current = current.concat([assistantMessage(result.final, calls)]);
    for (let callIndex = 0; callIndex < calls.length; callIndex++) {
      const call = calls[callIndex];
      const output = await runTool(call, index, message);
      // 工具中间消息只用于本轮请求，不写进群聊历史。
      current.push({
        role: 'tool',
        toolCallId: call.id,
        name: call.name,
        content: output
      });
    }
    message.meta = '工具结果已返回，继续请求…';
  }
  return { status: 'error', error: { code: 'TOOL_LOOP_LIMIT', message: '工具轮数超过上限' } };
}

function readImage(file) {
  return new Promise((resolve, reject) => {
    const allowed = ['image/png', 'image/jpeg', 'image/webp', 'image/gif'];
    if (allowed.indexOf(file.type) < 0) {
      reject(new Error('仅支持 png / jpeg / webp / gif'));
      return;
    }
    const reader = new FileReader();
    reader.onload = () => {
      const dataUrl = String(reader.result || '');
      const comma = dataUrl.indexOf(',');
      const base64 = comma >= 0 ? dataUrl.slice(comma + 1) : '';
      const padding = base64.endsWith('==') ? 2 : (base64.endsWith('=') ? 1 : 0);
      const size = Math.floor(base64.length * 3 / 4) - padding;
      if (!base64) {
        reject(new Error('读取图片失败'));
      } else if (size > 1024 * 1024) {
        reject(new Error('图片超过 1 MiB，请压缩后再试'));
      } else {
        resolve({ mimeType: file.type, base64: base64, name: file.name, size: size });
      }
    };
    reader.onerror = () => {
      reject(new Error('读取图片失败'));
    };
    reader.readAsDataURL(file);
  });
}

function clearPendingImage() {
  pendingImage.value = null;
  if (imageInput.value) imageInput.value.value = '';
}

function pickImage() {
  if (imageInput.value) imageInput.value.click();
}

async function onImageChange(event) {
  const file = event.target.files && event.target.files[0];
  if (!file) return;
  try {
    pendingImage.value = await readImage(file);
    setStatus('图片已附加');
  } catch (e) {
    clearPendingImage();
    setStatus(e.message || String(e), true);
  }
}

async function sendGroupMessage() {
  const text = inputText.value.trim();
  if ((!text && !pendingImage.value) || sending.value) return;
  if (!handles.some(Boolean)) {
    await applyMembers();
    if (!handles.some(Boolean)) return;
  }

  sending.value = true;
  inputText.value = '';
  const images = pendingImage.value
    ? [{ mimeType: pendingImage.value.mimeType, base64: pendingImage.value.base64 }]
    : [];
  const content = text || '请查看这张图片';
  clearPendingImage();
  history.push({ role: 'user', content: content, images: images });
  const userMessage = appendBubble('user', '你');
  userMessage.content = content;
  setMessageImages(userMessage, images);

  try {
    for (let index = 0; index < members.length; index++) {
      if (!handles[index]) continue;
      const name = memberName(index);
      const message = appendBubble('assistant', name);
      try {
        const result = await requestMember(index, messagesForMember(buildMessages(), index), message);
        if (result.status === 'success') {
          const finalText = (result.final && result.final.content) || message.content || '';
          message.content = finalText;
          setMessageImages(message, result.final && result.final.images);
          const usage = result.final && result.final.usage;
          message.meta = usage ? (usage.input + ' in / ' + usage.output + ' out') : '';
          history.push({ role: 'assistant', content: name + ': ' + finalText });
        } else if (result.status === 'cancelled') {
          message.content = message.content || '已取消';
        } else {
          message.error = true;
          message.content = (result.error && result.error.message) || '请求失败';
          message.meta = result.error ? result.error.code : '';
        }
      } catch (e) {
        message.error = true;
        message.content = e.message || String(e);
      }
    }
  } finally {
    sending.value = false;
    setStatus('');
  }
}

onMounted(() => {
  aicode.context().then((context) => {
    if (context && context.pluginId) sessionId = 'plugin:' + context.pluginId + ':team-chat';
  }).catch(() => {});
  void loadProviders();
});
</script>

<style>
:root { color-scheme: light dark; }
    * { box-sizing: border-box; }
    html, body { height: 100%; margin: 0; }
    body {
      font-family: system-ui, -apple-system, "Segoe UI", sans-serif;
      background: var(--aicode-page-bg, var(--aicode-bg, #ffffff));
      color: var(--aicode-on-surface, #1f2328);
    }
    .app {
      max-width: 860px;
      height: 100%;
      margin: 0 auto;
      display: flex;
      flex-direction: column;
      gap: 12px;
      padding: 16px;
    }
    header { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; }
    h1 { margin: 0; font-size: 19px; }
    .hint { margin: 0; color: var(--aicode-subtle-text, #59636e); font-size: 12px; }
    .members { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
    @media (max-width: 640px) { .members { grid-template-columns: 1fr; } }
    .member {
      border: 1px solid var(--aicode-outline, #d0d7de);
      border-radius: 12px;
      padding: 10px;
      display: flex;
      flex-direction: column;
      gap: 8px;
      background: var(--aicode-surface, #f6f8fa);
    }
    .member-head { display: flex; align-items: center; gap: 8px; }
    .member-head strong { flex-shrink: 0; }
    label { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: var(--aicode-subtle-text, #59636e); }
    input, select, button {
      font: inherit;
      color: inherit;
      background: var(--aicode-page-bg, #ffffff);
      border: 1px solid var(--aicode-outline, #d0d7de);
      border-radius: 8px;
      padding: 8px 10px;
      min-width: 0;
    }
    button {
      cursor: pointer;
      background: var(--aicode-primary, #0969da);
      border-color: transparent;
      color: var(--aicode-on-primary, #ffffff);
      font-weight: 600;
    }
    button:disabled { opacity: .55; cursor: default; }
    .actions { display: flex; gap: 8px; }
    .actions button:first-child {
      background: var(--aicode-page-bg, #ffffff);
      color: inherit;
      border-color: var(--aicode-outline, #d0d7de);
    }
    .chat {
      flex: 1;
      min-height: 160px;
      overflow: auto;
      border: 1px solid var(--aicode-outline, #d0d7de);
      border-radius: 12px;
      padding: 10px;
      display: flex;
      flex-direction: column;
      gap: 8px;
      background: var(--aicode-page-bg, #ffffff);
    }
    .bubble {
      border-radius: 10px;
      padding: 8px 10px;
      background: var(--aicode-surface, #f6f8fa);
      border: 1px solid var(--aicode-outline, #d0d7de);
      white-space: pre-wrap;
      word-break: break-word;
    }
    .bubble.user { align-self: flex-end; background: var(--aicode-primary-container, #ddf4ff); }
    .bubble.error { border-color: var(--aicode-error, #cf222e); }
    .bubble-head { display: flex; justify-content: space-between; gap: 8px; font-size: 11px; color: var(--aicode-subtle-text, #59636e); margin-bottom: 3px; }
    .bubble-body { font-size: 14px; line-height: 1.45; }
    .bubble-image { display: block; max-width: 100%; max-height: 220px; margin-top: 6px; border-radius: 8px; }
    .composer { display: flex; gap: 8px; }
    .composer input { flex: 1; }
    .composer #pick-image { flex-shrink: 0; background: var(--aicode-page-bg, #ffffff); color: inherit; border-color: var(--aicode-outline, #d0d7de); }
    .attachment { display: flex; align-items: center; gap: 8px; font-size: 12px; }
    .attachment[hidden] { display: none; }
    .attachment img { width: 56px; height: 56px; object-fit: cover; border-radius: 8px; border: 1px solid var(--aicode-outline, #d0d7de); }
    .attachment button { padding: 6px 10px; background: var(--aicode-page-bg, #ffffff); color: inherit; border-color: var(--aicode-outline, #d0d7de); }
    .approval {
      border: 1px solid var(--aicode-warning, #bf8700);
      border-radius: 12px;
      padding: 10px;
      display: flex;
      flex-direction: column;
      gap: 6px;
      background: var(--aicode-warning-container, #fff8c5);
    }
    .approval[hidden] { display: none; }
    .approval-head { display: flex; justify-content: space-between; gap: 8px; font-size: 13px; }
    .approval-tool { color: var(--aicode-subtle-text, #59636e); font-size: 12px; }
    .approval-summary { margin: 0; font-size: 13px; }
    .approval-details { margin: 0; max-height: 120px; overflow: auto; font-size: 11px; white-space: pre-wrap; word-break: break-word; }
    .approval .actions button { padding: 6px 10px; }
    .approval .actions button[data-choice="reject"] { background: var(--aicode-page-bg, #ffffff); color: var(--aicode-error, #cf222e); border-color: var(--aicode-error, #cf222e); }
    .status { margin: 0; min-height: 18px; font-size: 12px; color: var(--aicode-subtle-text, #59636e); }
    .status.error { color: var(--aicode-error, #cf222e); }
</style>
