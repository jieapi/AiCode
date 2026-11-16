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
function handleRequest(id, method, params, handlers) {
  if (!SUPPORTED_METHODS.has(method)) {
    send({ jsonrpc: "2.0", id, error: { code: "METHOD_UNKNOWN", message: method } });
    return;
  }
  const name = typeof params.handler === "string" ? params.handler : "";
  const handler = handlers[name];
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
  const handlers = definition.handlers;
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
      handleRequest(message.id, message.method, (_a = message.params) != null ? _a : {}, handlers);
    } else if (message.method) {
      return;
    } else if (message.id !== void 0) {
      handleResponse(message);
    }
  });
}

// plugins/samples/capability-demo/src/handlers/actions.ts
var actions = {
  "actions.fillDraft": async () => {
    await hostCall("host.chat.setDraft", { text: "\u8BF7\u603B\u7ED3\u5F53\u524D\u4F1A\u8BDD\u7684\u7ED3\u8BBA\u548C\u5F85\u529E\u3002" });
    return { message: "\u5DF2\u586B\u5165\u8F93\u5165\u6846" };
  },
  "actions.fileInfo": async (args) => {
    const path = String(args.path || "");
    if (!/^(workspace|aicode|data|plugin):\/\//.test(path)) {
      return { message: `\u5DE5\u4F5C\u533A\u5916\u8DEF\u5F84\uFF1A${path}` };
    }
    const stat = await hostCall("host.fs.stat", { path });
    const kind = stat.exists ? stat.isDir ? "\u76EE\u5F55" : "\u6587\u4EF6" : "\u4E0D\u5B58\u5728";
    return { message: `${path} \xB7 ${kind}` };
  },
  "actions.explainSelection": async (args) => {
    const selection = String(args.selection || "");
    if (!selection) return { message: "\u6CA1\u6709\u9009\u4E2D\u6587\u672C" };
    const language = String(args.language || "");
    await hostCall("host.chat.setDraft", {
      text: "\u8BF7\u89E3\u91CA\u8FD9\u6BB5\u4EE3\u7801\uFF1A\n```" + language + "\n" + selection + "\n```"
    });
    return { message: "\u5DF2\u586B\u5165\u8F93\u5165\u6846" };
  }
};

// plugins/samples/capability-demo/src/handlers/tools.ts
var tools = {
  "tools.showTip": async (args) => {
    var _a, _b;
    const actionLabel = args.actionLabel ? String(args.actionLabel) : "";
    return await hostCall("host.tip", {
      title: String((_a = args.title) != null ? _a : "\u6F14\u793A\u63D0\u793A"),
      text: String((_b = args.text) != null ? _b : "\u8FD9\u662F\u4E00\u6761\u6765\u81EA\u63D2\u4EF6\u80FD\u529B\u6F14\u793A\u7684\u63D0\u793A\u6761\u3002"),
      action: actionLabel ? { label: actionLabel, handler: "tools.tipAction" } : void 0
    });
  },
  "tools.tipAction": () => ({ ok: true, message: "\u63D0\u793A\u6761\u6309\u94AE\u56DE\u8C03\u5DF2\u5230\u8FBE\u63D2\u4EF6" }),
  "tools.shizuku": async (args) => {
    var _a;
    const command = String((_a = args.command) != null ? _a : "settings get global zen_mode");
    return await hostCall("host.shizuku.exec", { command, timeout: 30 });
  }
};

// plugins/samples/capability-demo/src/index.ts
definePlugin({
  handlers: { ...tools, ...actions }
});
