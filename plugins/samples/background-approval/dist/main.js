"use strict";

// plugins/sdk/src/index.ts
var import_readline = require("readline");
var SUPPORTED_METHODS = /* @__PURE__ */ new Set(["tool.invoke", "hook.invoke", "ui.invoke"]);
var pending = /* @__PURE__ */ new Map();
var hostSeq = 0;
var started = false;
function send(message) {
  process.stdout.write(JSON.stringify(message) + "\n");
}
function errorMessage(error) {
  return String((error == null ? void 0 : error.message) || error);
}
function errorCode(error) {
  const code = error == null ? void 0 : error.code;
  return typeof code === "string" && code ? code : "PLUGIN_ERROR";
}
function hostCall(method, params) {
  const id = "host-" + ++hostSeq;
  return new Promise((resolve, reject) => {
    pending.set(id, {
      resolve: (value) => resolve(value),
      reject
    });
    send({ jsonrpc: "2.0", id, method, params: params != null ? params : {} });
  });
}
function handleRequest(id, method, params, handlers2) {
  if (!SUPPORTED_METHODS.has(method)) {
    send({ jsonrpc: "2.0", id, error: { code: "METHOD_UNKNOWN", message: method } });
    return;
  }
  const name = typeof params.handler === "string" ? params.handler : "";
  const handler = handlers2[name];
  if (typeof handler !== "function") {
    send({ jsonrpc: "2.0", id, error: { code: "HANDLER_NOT_FOUND", message: name } });
    return;
  }
  Promise.resolve().then(() => {
    var _a;
    return handler((_a = params.args) != null ? _a : {});
  }).then((result) => send({ jsonrpc: "2.0", id, result: result === void 0 ? null : result })).catch(
    (error) => send({
      jsonrpc: "2.0",
      id,
      error: { code: errorCode(error), message: errorMessage(error) }
    })
  );
}
function handleResponse(message) {
  const id = String(message.id);
  const waiter = pending.get(id);
  if (!waiter) return;
  pending.delete(id);
  if (message.error) {
    const error = new Error(message.error.message || "host error");
    error.code = message.error.code || "HOST_ERROR";
    waiter.reject(error);
  } else {
    waiter.resolve(message.result === void 0 ? null : message.result);
  }
}
function definePlugin(definition) {
  if (started) throw new Error("definePlugin \u53EA\u80FD\u8C03\u7528\u4E00\u6B21");
  started = true;
  const handlers2 = definition.handlers;
  (0, import_readline.createInterface)({ input: process.stdin }).on("line", (line) => {
    var _a;
    if (!line.trim()) return;
    let message;
    try {
      message = JSON.parse(line);
    } catch {
      return;
    }
    if (message.method && message.id !== void 0) {
      handleRequest(message.id, message.method, (_a = message.params) != null ? _a : {}, handlers2);
    } else if (message.method) {
      return;
    } else if (message.id !== void 0) {
      handleResponse(message);
    }
  });
}

// plugins/samples/background-approval/src/index.ts
var BALL_SIZE = 48;
var REVIEW_TIMEOUT_MS = 4.5 * 60 * 1e3;
var FOREGROUND_POLL_MS = 1e3;
var PREVIEW_LIMIT = 1800;
var state = {
  ballId: null,
  ballPromise: null,
  active: null,
  queue: [],
  pollTimer: null,
  pumping: false,
  publishing: Promise.resolve(),
  requestSeq: 0
};
function summarizeArguments(value) {
  let text;
  try {
    text = JSON.stringify(value === void 0 ? {} : value, null, 2);
  } catch {
    text = String(value);
  }
  if (text.length > PREVIEW_LIMIT) text = text.slice(0, PREVIEW_LIMIT) + "\n\u2026";
  return text;
}
async function isAppForeground() {
  try {
    return await hostCall("host.app.isForeground") === true;
  } catch {
    return true;
  }
}
async function ensureBall() {
  if (state.ballId) return state.ballId;
  if (!state.ballPromise) {
    state.ballPromise = hostCall("host.overlay.create", {
      surface: "ball",
      width: BALL_SIZE,
      height: BALL_SIZE,
      visibilityPolicy: "background"
    }).then((info) => {
      state.ballId = String(info.overlayId);
      return state.ballId;
    }).finally(() => {
      state.ballPromise = null;
    });
  }
  return state.ballPromise;
}
async function postBall(payload) {
  await ensureBall();
  try {
    await hostCall("host.overlay.postMessage", { surface: "ball", payload });
  } catch (error) {
    if (error.code !== "OVERLAY_NOT_FOUND") throw error;
    state.ballId = null;
    await ensureBall();
    await hostCall("host.overlay.postMessage", { surface: "ball", payload });
  }
}
function publishState() {
  state.publishing = state.publishing.catch(() => {
  }).then(() => {
    if (!state.active && state.queue.length > 0) return;
    return postBall({
      type: "approvalState",
      request: state.active ? state.active.request : null,
      count: state.queue.length + (state.active ? 1 : 0)
    });
  });
  return state.publishing;
}
function refreshState() {
  void publishState().catch(() => {
  });
}
function stopForegroundPoll() {
  if (state.pollTimer) {
    clearInterval(state.pollTimer);
    state.pollTimer = null;
  }
}
function startForegroundPoll() {
  stopForegroundPoll();
  state.pollTimer = setInterval(async () => {
    const active = state.active;
    if (!active) {
      stopForegroundPoll();
      return;
    }
    if (await isAppForeground() && state.active === active) {
      finishActive("pass", "App \u5DF2\u56DE\u5230\u524D\u53F0");
    }
  }, FOREGROUND_POLL_MS);
}
function finishActive(decision, reason) {
  const active = state.active;
  if (!active) return;
  state.active = null;
  clearTimeout(active.timer);
  stopForegroundPoll();
  active.resolve({ decision, reason, logs: [reason] });
  if (state.queue.length > 0) {
    void pump();
  } else {
    refreshState();
  }
}
function drainQueue(decision, reason) {
  const pending2 = state.queue.splice(0);
  pending2.forEach((item) => {
    clearTimeout(item.timer);
    item.resolve({ decision, reason, logs: [reason] });
  });
  refreshState();
}
function onQueuedTimeout(item) {
  if (state.active === item) {
    finishActive("pass", "\u540E\u53F0\u5BA1\u6279\u7B49\u5F85\u8D85\u65F6");
    return;
  }
  const index = state.queue.indexOf(item);
  if (index < 0) return;
  state.queue.splice(index, 1);
  item.resolve({ decision: "pass", reason: "\u540E\u53F0\u5BA1\u6279\u7B49\u5F85\u8D85\u65F6", logs: ["\u540E\u53F0\u5BA1\u6279\u7B49\u5F85\u8D85\u65F6"] });
  refreshState();
  if (!state.active) void pump();
}
async function pump() {
  if (state.pumping || state.active || state.queue.length === 0) return;
  state.pumping = true;
  try {
    if (await isAppForeground()) {
      drainQueue("pass", "App \u5904\u4E8E\u524D\u53F0\uFF0C\u4EA4\u56DE\u5E94\u7528\u5185\u786E\u8BA4");
      return;
    }
    const next = state.queue.shift();
    if (!next) return;
    state.active = next;
    startForegroundPoll();
    await publishState();
  } catch (error) {
    const detail = error.code || error.message;
    finishActive("pass", "\u5BA1\u6279\u6D6E\u5C42\u4E0D\u53EF\u7528: " + detail);
  } finally {
    state.pumping = false;
    if (!state.active && state.queue.length > 0) void pump();
  }
}
function reviewPayload(args) {
  return {
    requestId: String(args.callId || "review-" + ++state.requestSeq),
    toolName: String(args.toolName || "unknown"),
    preview: summarizeArguments(args.arguments),
    mode: String(args.mode || ""),
    agentName: args.agentName ? String(args.agentName) : null
  };
}
var handlers = {
  "hooks.boot": async () => {
    try {
      await ensureBall();
      await publishState();
      return { ok: true, overlayId: state.ballId };
    } catch (error) {
      return {
        ok: false,
        code: error.code || "OVERLAY_FAILED",
        message: String(error.message || error)
      };
    }
  },
  "hooks.reviewPermission": async (payload) => {
    if (payload.appForeground === true || await isAppForeground()) {
      return { decision: "pass", logs: ["App \u5728\u524D\u53F0\uFF0C\u4EA4\u56DE\u5E94\u7528\u5185\u786E\u8BA4"] };
    }
    try {
      await ensureBall();
    } catch (error) {
      return {
        decision: "pass",
        logs: ["\u6D6E\u5C42\u4E0D\u53EF\u7528: " + (error.code || error.message)]
      };
    }
    const request = reviewPayload(payload);
    return new Promise((resolve) => {
      const item = {
        request,
        resolve,
        timer: setTimeout(() => onQueuedTimeout(item), REVIEW_TIMEOUT_MS)
      };
      state.queue.push(item);
      if (state.active) refreshState();
      void pump();
    });
  },
  "overlay.message": async (args) => {
    const payload = args.payload && typeof args.payload === "object" ? args.payload : {};
    const overlayId = args.overlayId ? String(args.overlayId) : "";
    if (state.ballId && overlayId && overlayId !== state.ballId) return { ok: true };
    if (payload.type === "ballReady" || payload.type === "ballClick") {
      await publishState();
      if (!state.active && state.queue.length > 0) void pump();
      return { ok: true };
    }
    if (payload.type === "foreground") {
      const active = state.active;
      if (active && await isAppForeground() && state.active === active) {
        finishActive("pass", "App \u5DF2\u56DE\u5230\u524D\u53F0");
      }
      return { ok: true };
    }
    if (payload.type === "displayError") {
      if (state.active && payload.requestId === state.active.request.requestId) {
        finishActive("pass", "\u65E0\u6CD5\u5C55\u5F00\u5BA1\u6279\u6D6E\u5C42");
      }
      return { ok: true };
    }
    if (payload.type === "decision" && state.active) {
      if (String(payload.requestId || "") !== state.active.request.requestId) {
        return { ok: false, error: "stale request" };
      }
      if (payload.decision !== "allow" && payload.decision !== "deny") {
        return { ok: false, error: "invalid decision" };
      }
      const allow = payload.decision === "allow";
      finishActive(
        allow ? "allow" : "deny",
        allow ? "\u7528\u6237\u5728\u540E\u53F0\u5BA1\u6279\u6D6E\u5C42\u4E2D\u540C\u610F" : "\u7528\u6237\u5728\u540E\u53F0\u5BA1\u6279\u6D6E\u5C42\u4E2D\u62D2\u7EDD"
      );
      return { ok: true };
    }
    return { ok: true };
  }
};
definePlugin({ handlers });
