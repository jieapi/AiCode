(function polyfill() {
  const relList = document.createElement("link").relList;
  if (relList && relList.supports && relList.supports("modulepreload")) {
    return;
  }
  for (const link of document.querySelectorAll('link[rel="modulepreload"]')) {
    processPreload(link);
  }
  new MutationObserver((mutations) => {
    for (const mutation of mutations) {
      if (mutation.type !== "childList") {
        continue;
      }
      for (const node of mutation.addedNodes) {
        if (node.tagName === "LINK" && node.rel === "modulepreload")
          processPreload(node);
      }
    }
  }).observe(document, { childList: true, subtree: true });
  function getFetchOpts(link) {
    const fetchOpts = {};
    if (link.integrity) fetchOpts.integrity = link.integrity;
    if (link.referrerPolicy) fetchOpts.referrerPolicy = link.referrerPolicy;
    if (link.crossOrigin === "use-credentials")
      fetchOpts.credentials = "include";
    else if (link.crossOrigin === "anonymous") fetchOpts.credentials = "omit";
    else fetchOpts.credentials = "same-origin";
    return fetchOpts;
  }
  function processPreload(link) {
    if (link.ep)
      return;
    link.ep = true;
    const fetchOpts = getFetchOpts(link);
    fetch(link.href, fetchOpts);
  }
})();
const Vue = window.Vue;
if (!Vue) throw new Error("Aicode page requires /__aicode/vue.global.prod.js");
Vue.BaseTransition;
Vue.BaseTransitionPropsValidators;
Vue.Comment;
Vue.DeprecationTypes;
Vue.EffectScope;
Vue.ErrorCodes;
Vue.ErrorTypeStrings;
const Fragment = Vue.Fragment;
Vue.KeepAlive;
Vue.ReactiveEffect;
Vue.Static;
Vue.Suspense;
Vue.Teleport;
Vue.Text;
Vue.TrackOpTypes;
Vue.Transition;
Vue.TransitionGroup;
Vue.TriggerOpTypes;
Vue.VueElement;
Vue.__esModule;
Vue.assertNumber;
Vue.callWithAsyncErrorHandling;
Vue.callWithErrorHandling;
Vue.camelize;
Vue.capitalize;
Vue.cloneVNode;
Vue.compatUtils;
Vue.compile;
const computed = Vue.computed;
const createApp = Vue.createApp;
Vue.createBlock;
const createCommentVNode = Vue.createCommentVNode;
const createElementBlock = Vue.createElementBlock;
const createElementVNode = Vue.createElementVNode;
Vue.createHydrationRenderer;
Vue.createPropsRestProxy;
Vue.createRenderer;
Vue.createSSRApp;
Vue.createSlots;
Vue.createStaticVNode;
const createTextVNode = Vue.createTextVNode;
Vue.createVNode;
Vue.customRef;
Vue.defineAsyncComponent;
Vue.defineComponent;
Vue.defineCustomElement;
Vue.defineEmits;
Vue.defineExpose;
Vue.defineModel;
Vue.defineOptions;
Vue.defineProps;
Vue.defineSSRCustomElement;
Vue.defineSlots;
Vue.devtools;
Vue.effect;
Vue.effectScope;
Vue.getCurrentInstance;
Vue.getCurrentScope;
Vue.getCurrentWatcher;
Vue.getTransitionRawChildren;
Vue.guardReactiveProps;
Vue.h;
Vue.handleError;
Vue.hasInjectionContext;
Vue.hydrate;
Vue.hydrateOnIdle;
Vue.hydrateOnInteraction;
Vue.hydrateOnMediaQuery;
Vue.hydrateOnVisible;
Vue.initCustomFormatter;
Vue.initDirectivesForSSR;
Vue.inject;
Vue.isMemoSame;
Vue.isProxy;
Vue.isReactive;
Vue.isReadonly;
Vue.isRef;
Vue.isRuntimeOnly;
Vue.isShallow;
Vue.isVNode;
Vue.markRaw;
Vue.mergeDefaults;
Vue.mergeModels;
Vue.mergeProps;
const nextTick = Vue.nextTick;
Vue.nodeOps;
const normalizeClass = Vue.normalizeClass;
Vue.normalizeProps;
Vue.normalizeStyle;
Vue.onActivated;
Vue.onBeforeMount;
Vue.onBeforeUnmount;
Vue.onBeforeUpdate;
Vue.onDeactivated;
Vue.onErrorCaptured;
const onMounted = Vue.onMounted;
Vue.onRenderTracked;
Vue.onRenderTriggered;
Vue.onScopeDispose;
Vue.onServerPrefetch;
Vue.onUnmounted;
Vue.onUpdated;
Vue.onWatcherCleanup;
const openBlock = Vue.openBlock;
Vue.patchProp;
Vue.popScopeId;
Vue.provide;
Vue.proxyRefs;
Vue.pushScopeId;
Vue.queuePostFlushCb;
const reactive = Vue.reactive;
Vue.readonly;
const ref = Vue.ref;
Vue.registerRuntimeCompiler;
Vue.render;
const renderList = Vue.renderList;
Vue.renderSlot;
Vue.resolveComponent;
Vue.resolveDirective;
Vue.resolveDynamicComponent;
Vue.resolveFilter;
Vue.resolveTransitionHooks;
Vue.setBlockTracking;
Vue.setDevtoolsHook;
Vue.setTransitionHooks;
Vue.shallowReactive;
Vue.shallowReadonly;
Vue.shallowRef;
Vue.ssrContextKey;
Vue.ssrUtils;
Vue.stop;
const toDisplayString = Vue.toDisplayString;
Vue.toHandlerKey;
Vue.toHandlers;
Vue.toRaw;
Vue.toRef;
Vue.toRefs;
Vue.toValue;
Vue.transformVNodeArgs;
Vue.triggerRef;
Vue.unref;
Vue.useAttrs;
Vue.useCssModule;
Vue.useCssVars;
Vue.useHost;
Vue.useId;
Vue.useModel;
Vue.useSSRContext;
Vue.useShadowRoot;
Vue.useSlots;
Vue.useTemplateRef;
Vue.useTransitionState;
Vue.vModelCheckbox;
Vue.vModelDynamic;
Vue.vModelRadio;
const vModelSelect = Vue.vModelSelect;
const vModelText = Vue.vModelText;
Vue.vShow;
Vue.version;
Vue.warn;
Vue.watch;
Vue.watchEffect;
Vue.watchPostEffect;
Vue.watchSyncEffect;
Vue.withAsyncContext;
Vue.withCtx;
Vue.withDefaults;
const withDirectives = Vue.withDirectives;
Vue.withKeys;
Vue.withMemo;
const withModifiers = Vue.withModifiers;
Vue.withScopeId;
const _hoisted_1 = { id: "app" };
const _hoisted_2 = { class: "app" };
const _hoisted_3 = { class: "members" };
const _hoisted_4 = { class: "member-head" };
const _hoisted_5 = ["onUpdate:modelValue", "aria-label"];
const _hoisted_6 = ["onUpdate:modelValue", "onChange"];
const _hoisted_7 = { value: "" };
const _hoisted_8 = ["value"];
const _hoisted_9 = ["onUpdate:modelValue"];
const _hoisted_10 = { value: "" };
const _hoisted_11 = ["value"];
const _hoisted_12 = ["onUpdate:modelValue"];
const _hoisted_13 = { class: "actions" };
const _hoisted_14 = ["disabled"];
const _hoisted_15 = {
  key: 0,
  class: "approval"
};
const _hoisted_16 = { class: "approval-head" };
const _hoisted_17 = { class: "approval-tool" };
const _hoisted_18 = { class: "approval-summary" };
const _hoisted_19 = { class: "approval-details" };
const _hoisted_20 = { class: "actions" };
const _hoisted_21 = ["disabled"];
const _hoisted_22 = ["disabled"];
const _hoisted_23 = ["disabled"];
const _hoisted_24 = { class: "bubble-head" };
const _hoisted_25 = { class: "bubble-body" };
const _hoisted_26 = ["src"];
const _hoisted_27 = {
  key: 1,
  class: "attachment"
};
const _hoisted_28 = ["src"];
const _hoisted_29 = ["disabled"];
const _sfc_main = {
  __name: "team",
  setup(__props) {
    const TOOLS = [
      {
        type: "custom",
        name: "team_vote",
        description: "记录群聊成员对某个议题的选择，并返回插件本地的投票统计。",
        inputSchema: {
          type: "object",
          properties: {
            topic: { type: "string", description: "议题" },
            options: { type: "array", items: { type: "string" }, description: "候选选项" },
            choice: { type: "string", description: "本次选择" },
            voter: { type: "string", description: "投票成员，缺省为当前发言成员" }
          },
          required: ["choice"]
        }
      },
      { type: "app", name: "writeFile" }
    ];
    const members = reactive([
      {
        key: "a",
        label: "成员 A",
        name: "产品经理",
        prompt: "你是群聊里的产品经理，回答要简短，先给结论。",
        providerId: "",
        model: "",
        models: [],
        modelState: "idle",
        supportsTools: true,
        supportsVision: true
      },
      {
        key: "b",
        label: "成员 B",
        name: "架构师",
        prompt: "你是群聊里的架构师，指出风险与取舍，回答要简短。",
        providerId: "",
        model: "",
        models: [],
        modelState: "idle",
        supportsTools: true,
        supportsVision: true
      }
    ]);
    const providers = ref([]);
    const statusText = ref("");
    const statusError = ref(false);
    const chatMessages = ref([]);
    const chatEl = ref(null);
    const inputText = ref("");
    const pendingImage = ref(null);
    const imageInput = ref(null);
    const sending = ref(false);
    const applying = ref(false);
    const approval = ref(null);
    const approvalBusy = ref(false);
    const approvalRememberable = computed(
      () => (approval.value && approval.value.rememberablePatterns ? approval.value.rememberablePatterns.length : 0) > 0
    );
    const pendingImageSrc = computed(() => pendingImage.value ? imageSource(pendingImage.value) : "");
    const pendingImageLabel = computed(() => {
      if (!pendingImage.value) return "";
      const image = pendingImage.value;
      return image.name + " · " + Math.max(1, Math.round(image.size / 1024)) + " KiB";
    });
    let handles = [null, null];
    let history = [];
    let voteTally = {};
    let messageSeq = 0;
    let sessionId = "plugin:demo.team-chat:team-chat";
    function setStatus(text, isError) {
      statusText.value = text || "";
      statusError.value = isError === true;
    }
    function memberName(index) {
      return members[index].name.trim() || "成员 " + (index + 1);
    }
    function modelPlaceholder(member) {
      if (!member.providerId) return "先选择供应商";
      if (member.modelState === "loading") return "加载中…";
      if (member.modelState === "error") return "加载失败";
      return member.models.length ? "选择模型" : "没有已添加的模型";
    }
    function imageSource(image) {
      return "data:" + image.mimeType + ";base64," + image.base64;
    }
    async function loadModels(index, providerId) {
      const member = members[index];
      if (providerId === void 0) providerId = member.providerId;
      member.providerId = providerId;
      member.models = [];
      member.model = "";
      if (!providerId) {
        member.modelState = "idle";
        return;
      }
      member.modelState = "loading";
      try {
        const result = await aicode.model.listModels({ providerId });
        member.models = result.models || [];
        member.modelState = "ready";
        if (member.models.length === 1) member.model = member.models[0].id;
      } catch (e) {
        member.models = [];
        member.modelState = "error";
        setStatus((e.code ? e.code + ": " : "") + (e.message || String(e)), true);
      }
    }
    async function loadProviders() {
      setStatus("加载供应商…");
      try {
        const result = await aicode.model.listProviders();
        providers.value = result.providers.filter((provider) => provider.enabled && provider.hasKey);
        for (let index = 0; index < members.length; index++) {
          const member = members[index];
          if (!providers.value.some((provider) => provider.id === member.providerId)) {
            member.providerId = "";
            member.model = "";
          }
          if (!member.providerId && providers.value.length) {
            member.providerId = providers.value[0].id;
          }
          await loadModels(index);
        }
        setStatus(providers.value.length ? "" : "没有可用的供应商，请先在设置中添加并填写 Key。", !providers.value.length);
      } catch (e) {
        setStatus(e.message || String(e), true);
      }
    }
    async function applyMembers() {
      applying.value = true;
      setStatus("创建句柄…");
      try {
        for (let index = 0; index < members.length; index++) {
          const member = members[index];
          if (handles[index]) {
            try {
              await aicode.model.close(handles[index]);
            } catch (_) {
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
        setStatus(ready ? "句柄已就绪" : "至少为一个成员选择供应商和模型。", !ready);
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
        role,
        name,
        content: "",
        meta: "",
        images: [],
        error: false
      });
      chatMessages.value.push(message);
      void scrollChat();
      return message;
    }
    function setMessageImages(message, images) {
      message.images = (images || []).filter((image) => image && image.base64).map((image) => ({ src: imageSource(image) }));
    }
    function buildMessages() {
      const merged = [];
      history.forEach((item) => {
        const last = merged[merged.length - 1];
        if (last && last.role === item.role && item.role !== "tool") {
          last.content = [last.content, item.content].filter(Boolean).join("\n\n");
          if (item.images && item.images.length) {
            last.images = (last.images || []).concat(item.images).slice(-4);
          }
          return;
        }
        merged.push({
          role: item.role,
          content: item.content,
          images: item.images ? item.images.slice() : void 0
        });
      });
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
      if (call.arguments && typeof call.arguments === "object") return call.arguments;
      if (typeof call.arguments === "string") {
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
        role: "assistant",
        content: final.content || "",
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
      const topic = String(args.topic || "未命名议题");
      const choice = String(args.choice || "");
      if (!choice) {
        return JSON.stringify({ error: "MISSING_CHOICE", message: "team_vote 需要 choice" });
      }
      const tally = voteTally[topic] || (voteTally[topic] = {});
      (Array.isArray(args.options) ? args.options : []).forEach((option) => {
        const key = String(option);
        if (tally[key] == null) tally[key] = 0;
      });
      tally[choice] = (tally[choice] || 0) + 1;
      return JSON.stringify({
        topic,
        voter: String(args.voter || memberName(memberIndex)),
        choice,
        tally
      });
    }
    function setApproval(pending) {
      approval.value = pending ? pending.approval : null;
    }
    async function executeHostTool(name, args) {
      const timer = setInterval(async () => {
        try {
          const pending = await aicode.tools.pending(sessionId);
          setApproval(pending);
        } catch (_) {
        }
      }, 700);
      try {
        return await aicode.tools.execute(name, args, { sessionId, timeoutMs: 12e4 });
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
        setStatus("已提交审批：" + choice);
      } catch (e) {
        setStatus((e.code ? e.code + ": " : "") + (e.message || String(e)), true);
      } finally {
        approvalBusy.value = false;
      }
    }
    async function runTool(call, memberIndex, message) {
      if (call.argumentsTruncated) {
        return JSON.stringify({ error: "ARGUMENTS_TRUNCATED", message: "工具参数被截断，请减少内容后重试" });
      }
      if (call.name === "team_vote") {
        message.meta = "本地执行 team_vote…";
        return runTeamVote(call, memberIndex);
      }
      if (call.name === "writeFile") {
        message.meta = "等待宿主执行 writeFile…";
        try {
          const result = await executeHostTool("writeFile", callArguments(call));
          message.meta = result.status === "success" ? "writeFile 已完成" : "writeFile 返回 " + result.status;
          return JSON.stringify(result);
        } catch (e) {
          message.meta = "writeFile 未完成";
          return JSON.stringify({ error: e.code || "TOOL_ERROR", message: e.message || String(e) });
        }
      }
      return JSON.stringify({ error: "UNKNOWN_TOOL", message: "未知工具：" + call.name });
    }
    async function requestMember(index, messages, message) {
      let streamed = "";
      let current = messages;
      const tools = members[index].supportsTools === false ? void 0 : TOOLS;
      let prompt = members[index].prompt;
      if (tools) {
        prompt += "\n\n需要表决时调用 team_vote；需要把结论落盘时调用 writeFile。";
      }
      const onEvent = (event) => {
        if (event.type === "text") {
          streamed += event.text;
          message.content = streamed;
          void scrollChat();
        } else if (event.type === "toolCall") {
          message.meta = "调用工具 " + event.name + "…";
        } else if (event.type === "retry") {
          message.meta = "重试中…";
        } else if (event.type === "keySwitch") {
          message.meta = "切换 Key " + event.index + "/" + event.total;
        }
      };
      for (let round = 0; round < 3; round++) {
        if (round > 0 && streamed) {
          streamed += "\n\n";
          message.content = streamed;
        }
        const result = await aicode.model.stream({
          handleId: handles[index],
          messages: current,
          tools,
          systemPrompt: prompt
        }, onEvent);
        if (result.status !== "success") return result;
        const calls = result.final && result.final.toolCalls || [];
        if (calls.length === 0) return result;
        current = current.concat([assistantMessage(result.final, calls)]);
        for (let callIndex = 0; callIndex < calls.length; callIndex++) {
          const call = calls[callIndex];
          const output = await runTool(call, index, message);
          current.push({
            role: "tool",
            toolCallId: call.id,
            name: call.name,
            content: output
          });
        }
        message.meta = "工具结果已返回，继续请求…";
      }
      return { status: "error", error: { code: "TOOL_LOOP_LIMIT", message: "工具轮数超过上限" } };
    }
    function readImage(file) {
      return new Promise((resolve, reject) => {
        const allowed = ["image/png", "image/jpeg", "image/webp", "image/gif"];
        if (allowed.indexOf(file.type) < 0) {
          reject(new Error("仅支持 png / jpeg / webp / gif"));
          return;
        }
        const reader = new FileReader();
        reader.onload = () => {
          const dataUrl = String(reader.result || "");
          const comma = dataUrl.indexOf(",");
          const base64 = comma >= 0 ? dataUrl.slice(comma + 1) : "";
          const padding = base64.endsWith("==") ? 2 : base64.endsWith("=") ? 1 : 0;
          const size = Math.floor(base64.length * 3 / 4) - padding;
          if (!base64) {
            reject(new Error("读取图片失败"));
          } else if (size > 1024 * 1024) {
            reject(new Error("图片超过 1 MiB，请压缩后再试"));
          } else {
            resolve({ mimeType: file.type, base64, name: file.name, size });
          }
        };
        reader.onerror = () => {
          reject(new Error("读取图片失败"));
        };
        reader.readAsDataURL(file);
      });
    }
    function clearPendingImage() {
      pendingImage.value = null;
      if (imageInput.value) imageInput.value.value = "";
    }
    function pickImage() {
      if (imageInput.value) imageInput.value.click();
    }
    async function onImageChange(event) {
      const file = event.target.files && event.target.files[0];
      if (!file) return;
      try {
        pendingImage.value = await readImage(file);
        setStatus("图片已附加");
      } catch (e) {
        clearPendingImage();
        setStatus(e.message || String(e), true);
      }
    }
    async function sendGroupMessage() {
      const text = inputText.value.trim();
      if (!text && !pendingImage.value || sending.value) return;
      if (!handles.some(Boolean)) {
        await applyMembers();
        if (!handles.some(Boolean)) return;
      }
      sending.value = true;
      inputText.value = "";
      const images = pendingImage.value ? [{ mimeType: pendingImage.value.mimeType, base64: pendingImage.value.base64 }] : [];
      const content = text || "请查看这张图片";
      clearPendingImage();
      history.push({ role: "user", content, images });
      const userMessage = appendBubble("user", "你");
      userMessage.content = content;
      setMessageImages(userMessage, images);
      try {
        for (let index = 0; index < members.length; index++) {
          if (!handles[index]) continue;
          const name = memberName(index);
          const message = appendBubble("assistant", name);
          try {
            const result = await requestMember(index, messagesForMember(buildMessages(), index), message);
            if (result.status === "success") {
              const finalText = result.final && result.final.content || message.content || "";
              message.content = finalText;
              setMessageImages(message, result.final && result.final.images);
              const usage = result.final && result.final.usage;
              message.meta = usage ? usage.input + " in / " + usage.output + " out" : "";
              history.push({ role: "assistant", content: name + ": " + finalText });
            } else if (result.status === "cancelled") {
              message.content = message.content || "已取消";
            } else {
              message.error = true;
              message.content = result.error && result.error.message || "请求失败";
              message.meta = result.error ? result.error.code : "";
            }
          } catch (e) {
            message.error = true;
            message.content = e.message || String(e);
          }
        }
      } finally {
        sending.value = false;
        setStatus("");
      }
    }
    onMounted(() => {
      aicode.context().then((context) => {
        if (context && context.pluginId) sessionId = "plugin:" + context.pluginId + ":team-chat";
      }).catch(() => {
      });
      void loadProviders();
    });
    return (_ctx, _cache) => {
      return openBlock(), createElementBlock("div", _hoisted_1, [
        createElementVNode("main", _hoisted_2, [
          _cache[7] || (_cache[7] = createElementVNode(
            "header",
            null,
            [
              createElementVNode("div", null, [
                createElementVNode("h1", null, "团队群聊"),
                createElementVNode("p", { class: "hint" }, "每个成员一个模型句柄；发送时依次请求，后发言的成员能看到前面的回复。模型可调用 team_vote 或 writeFile，写文件前会在页面内请求确认。")
              ])
            ],
            -1
            /* CACHED */
          )),
          createElementVNode("section", _hoisted_3, [
            (openBlock(true), createElementBlock(
              Fragment,
              null,
              renderList(members, (member, index) => {
                return openBlock(), createElementBlock("article", {
                  key: member.key,
                  class: "member"
                }, [
                  createElementVNode("div", _hoisted_4, [
                    createElementVNode(
                      "strong",
                      null,
                      toDisplayString(member.label),
                      1
                      /* TEXT */
                    ),
                    withDirectives(createElementVNode("input", {
                      "onUpdate:modelValue": ($event) => member.name = $event,
                      "aria-label": member.label + " 名称"
                    }, null, 8, _hoisted_5), [
                      [vModelText, member.name]
                    ])
                  ]),
                  createElementVNode("label", null, [
                    _cache[4] || (_cache[4] = createTextVNode(
                      "供应商 ",
                      -1
                      /* CACHED */
                    )),
                    withDirectives(createElementVNode("select", {
                      "onUpdate:modelValue": ($event) => member.providerId = $event,
                      onChange: ($event) => loadModels(index, $event.target.value)
                    }, [
                      createElementVNode(
                        "option",
                        _hoisted_7,
                        toDisplayString(providers.value.length ? "选择供应商" : "没有可用供应商"),
                        1
                        /* TEXT */
                      ),
                      (openBlock(true), createElementBlock(
                        Fragment,
                        null,
                        renderList(providers.value, (provider) => {
                          return openBlock(), createElementBlock("option", {
                            key: provider.id,
                            value: provider.id
                          }, toDisplayString(provider.name) + toDisplayString(provider.isDefault ? "（默认）" : ""), 9, _hoisted_8);
                        }),
                        128
                        /* KEYED_FRAGMENT */
                      ))
                    ], 40, _hoisted_6), [
                      [vModelSelect, member.providerId]
                    ])
                  ]),
                  createElementVNode("label", null, [
                    _cache[5] || (_cache[5] = createTextVNode(
                      "模型 ",
                      -1
                      /* CACHED */
                    )),
                    withDirectives(createElementVNode("select", {
                      "onUpdate:modelValue": ($event) => member.model = $event
                    }, [
                      createElementVNode(
                        "option",
                        _hoisted_10,
                        toDisplayString(modelPlaceholder(member)),
                        1
                        /* TEXT */
                      ),
                      (openBlock(true), createElementBlock(
                        Fragment,
                        null,
                        renderList(member.models, (model) => {
                          return openBlock(), createElementBlock("option", {
                            key: model.id,
                            value: model.id
                          }, toDisplayString(model.displayName) + " · " + toDisplayString(model.id), 9, _hoisted_11);
                        }),
                        128
                        /* KEYED_FRAGMENT */
                      ))
                    ], 8, _hoisted_9), [
                      [vModelSelect, member.model]
                    ])
                  ]),
                  createElementVNode("label", null, [
                    _cache[6] || (_cache[6] = createTextVNode(
                      "提示词",
                      -1
                      /* CACHED */
                    )),
                    withDirectives(createElementVNode("input", {
                      "onUpdate:modelValue": ($event) => member.prompt = $event
                    }, null, 8, _hoisted_12), [
                      [vModelText, member.prompt]
                    ])
                  ])
                ]);
              }),
              128
              /* KEYED_FRAGMENT */
            ))
          ]),
          createElementVNode("div", _hoisted_13, [
            createElementVNode("button", {
              type: "button",
              onClick: loadProviders
            }, "重新加载供应商"),
            createElementVNode("button", {
              type: "button",
              disabled: applying.value,
              onClick: applyMembers
            }, "创建 / 更新句柄", 8, _hoisted_14)
          ]),
          approval.value ? (openBlock(), createElementBlock("section", _hoisted_15, [
            createElementVNode("div", _hoisted_16, [
              createElementVNode(
                "strong",
                null,
                toDisplayString(approval.value.title || "需要确认"),
                1
                /* TEXT */
              ),
              createElementVNode(
                "span",
                _hoisted_17,
                toDisplayString(approval.value.toolName || ""),
                1
                /* TEXT */
              )
            ]),
            createElementVNode(
              "p",
              _hoisted_18,
              toDisplayString(approval.value.summary || ""),
              1
              /* TEXT */
            ),
            createElementVNode(
              "pre",
              _hoisted_19,
              toDisplayString(approval.value.details || approval.value.argsPreview || ""),
              1
              /* TEXT */
            ),
            createElementVNode("div", _hoisted_20, [
              createElementVNode("button", {
                type: "button",
                "data-choice": "once",
                disabled: approvalBusy.value,
                onClick: _cache[0] || (_cache[0] = ($event) => resolveApproval("once"))
              }, "允许一次", 8, _hoisted_21),
              approvalRememberable.value ? (openBlock(), createElementBlock("button", {
                key: 0,
                type: "button",
                "data-choice": "always",
                disabled: approvalBusy.value,
                onClick: _cache[1] || (_cache[1] = ($event) => resolveApproval("always"))
              }, "始终允许", 8, _hoisted_22)) : createCommentVNode("v-if", true),
              createElementVNode("button", {
                type: "button",
                "data-choice": "reject",
                disabled: approvalBusy.value,
                onClick: _cache[2] || (_cache[2] = ($event) => resolveApproval("reject"))
              }, "拒绝", 8, _hoisted_23)
            ])
          ])) : createCommentVNode("v-if", true),
          createElementVNode(
            "section",
            {
              ref_key: "chatEl",
              ref: chatEl,
              class: "chat"
            },
            [
              (openBlock(true), createElementBlock(
                Fragment,
                null,
                renderList(chatMessages.value, (message) => {
                  return openBlock(), createElementBlock(
                    "article",
                    {
                      key: message.id,
                      class: normalizeClass(["bubble", [message.role, { error: message.error }]])
                    },
                    [
                      createElementVNode("div", _hoisted_24, [
                        createElementVNode(
                          "span",
                          null,
                          toDisplayString(message.name),
                          1
                          /* TEXT */
                        ),
                        createElementVNode(
                          "span",
                          null,
                          toDisplayString(message.meta),
                          1
                          /* TEXT */
                        )
                      ]),
                      createElementVNode("div", _hoisted_25, [
                        createElementVNode(
                          "span",
                          null,
                          toDisplayString(message.content),
                          1
                          /* TEXT */
                        ),
                        (openBlock(true), createElementBlock(
                          Fragment,
                          null,
                          renderList(message.images, (image, imageIndex) => {
                            return openBlock(), createElementBlock("img", {
                              key: imageIndex,
                              class: "bubble-image",
                              src: image.src,
                              alt: ""
                            }, null, 8, _hoisted_26);
                          }),
                          128
                          /* KEYED_FRAGMENT */
                        ))
                      ])
                    ],
                    2
                    /* CLASS */
                  );
                }),
                128
                /* KEYED_FRAGMENT */
              ))
            ],
            512
            /* NEED_PATCH */
          ),
          pendingImage.value ? (openBlock(), createElementBlock("div", _hoisted_27, [
            createElementVNode("img", {
              src: pendingImageSrc.value,
              alt: "待发送图片"
            }, null, 8, _hoisted_28),
            createElementVNode(
              "span",
              null,
              toDisplayString(pendingImageLabel.value),
              1
              /* TEXT */
            ),
            createElementVNode("button", {
              type: "button",
              onClick: clearPendingImage
            }, "移除")
          ])) : createCommentVNode("v-if", true),
          createElementVNode(
            "form",
            {
              class: "composer",
              onSubmit: withModifiers(sendGroupMessage, ["prevent"])
            },
            [
              createElementVNode(
                "input",
                {
                  ref_key: "imageInput",
                  ref: imageInput,
                  id: "image-input",
                  type: "file",
                  accept: "image/png,image/jpeg,image/webp,image/gif",
                  hidden: "",
                  onChange: onImageChange
                },
                null,
                544
                /* NEED_HYDRATION, NEED_PATCH */
              ),
              createElementVNode("button", {
                id: "pick-image",
                type: "button",
                title: "添加图片",
                onClick: pickImage
              }, "图片"),
              withDirectives(createElementVNode(
                "input",
                {
                  id: "input",
                  "onUpdate:modelValue": _cache[3] || (_cache[3] = ($event) => inputText.value = $event),
                  placeholder: "输入群聊消息…",
                  autocomplete: "off"
                },
                null,
                512
                /* NEED_PATCH */
              ), [
                [vModelText, inputText.value]
              ]),
              createElementVNode("button", {
                id: "send",
                type: "submit",
                disabled: sending.value
              }, "轮流发送", 8, _hoisted_29)
            ],
            32
            /* NEED_HYDRATION */
          ),
          createElementVNode(
            "p",
            {
              class: normalizeClass(["status", { error: statusError.value }])
            },
            toDisplayString(statusText.value),
            3
            /* TEXT, CLASS */
          )
        ])
      ]);
    };
  }
};
const app = createApp(_sfc_main);
if (window.AicodeUI) app.use(window.AicodeUI);
app.mount("#app");
