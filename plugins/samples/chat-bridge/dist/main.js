"use strict";

// plugins/sdk/src/index.ts
var import_readline = require("readline");
var SUPPORTED_METHODS = /* @__PURE__ */ new Set(["tool.invoke", "hook.invoke", "ui.invoke"]);
var pending = /* @__PURE__ */ new Map();
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
function emit(name, payload = null) {
  send({ jsonrpc: "2.0", method: "plugin.event", params: { name, payload } });
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

// plugins/samples/chat-bridge/src/index.ts
var REVIEW_TIMEOUT_MS = 4.5 * 60 * 1e3;
var reviewSeq = 0;
var pendingReviews = /* @__PURE__ */ new Map();
function finishReview(requestId, decision) {
  const entry = pendingReviews.get(requestId);
  if (!entry) return false;
  clearTimeout(entry.timer);
  pendingReviews.delete(requestId);
  entry.resolve({ decision, reason: "page approval" });
  emit("chat.permission.resolved", { requestId, decision });
  return true;
}
var handlers = {
  "hooks.reviewPermission": (args) => {
    var _a;
    const requestId = String(args.callId || "review-" + ++reviewSeq);
    const payload = {
      requestId,
      sessionId: args.sessionId ? String(args.sessionId) : null,
      runId: args.runId ? String(args.runId) : null,
      scope: args.scope ? String(args.scope) : "main",
      agentName: args.agentName ? String(args.agentName) : null,
      toolName: args.toolName ? String(args.toolName) : "tool",
      arguments: (_a = args.arguments) != null ? _a : {},
      appForeground: args.appForeground === true
    };
    emit("chat.permission.requested", payload);
    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        if (!pendingReviews.has(requestId)) return;
        pendingReviews.delete(requestId);
        emit("chat.permission.expired", { requestId });
        resolve({ decision: "pass", reason: "page approval timeout" });
      }, REVIEW_TIMEOUT_MS);
      pendingReviews.set(requestId, { resolve, timer, payload });
    });
  },
  "ui.permissionDecision": (args) => {
    const requestId = String(args.requestId || "");
    const decision = args.decision === "allow" || args.decision === "deny" ? args.decision : "pass";
    if (!finishReview(requestId, decision)) {
      return { ok: false, error: "REVIEW_NOT_FOUND" };
    }
    return { ok: true, decision };
  },
  "ui.pageReady": () => ({
    pendingReviews: Array.from(pendingReviews.values()).map((entry) => entry.payload)
  }),
  "actions.notifySession": (args) => ({
    message: args.sessionId ? `\u5DF2\u89E6\u53D1\u4F1A\u8BDD\u52A8\u4F5C ${String(args.sessionId).slice(0, 8)}` : "\u672A\u6536\u5230\u4F1A\u8BDD\u4E0A\u4E0B\u6587"
  })
};
definePlugin({ handlers });
