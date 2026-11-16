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

// plugins/samples/community.demo-kit/src/index.ts
var ENV_BASENAME = /^\.env(?:\..+)?$/i;
var ENV_TOKEN = /(^|[\s"'`=<>|;&()/*?\\])\.env(?:\.[A-Za-z0-9._-]+)?($|[\s"'`<>|;&()/*?\\])/i;
var PATH_KEYS = ["path", "paths", "file", "files", "output_path", "input_path", "images", "image"];
var COMMAND_TOOLS = /* @__PURE__ */ new Set(["bash", "terminal", "shizuku"]);
var DETECT_TOOLS = /* @__PURE__ */ new Set(["list", "search"]);
var lastRun = null;
function flattenStrings(value, out = []) {
  if (typeof value === "string") {
    out.push(value);
  } else if (Array.isArray(value)) {
    for (const item of value) flattenStrings(item, out);
  }
  return out;
}
function basename(value) {
  const normalized = String(value).trim().replace(/^['"]|['"]$/g, "").replace(/\\/g, "/").replace(/\/+$/g, "");
  const parts = normalized.split("/");
  return parts[parts.length - 1] || "";
}
function isEnvPath(value) {
  return ENV_BASENAME.test(basename(value));
}
function inspectEnvAccess(toolName, args) {
  const hits = [];
  for (const key of PATH_KEYS) {
    if (args[key] === void 0) continue;
    for (const value of flattenStrings(args[key])) {
      if (isEnvPath(value)) hits.push(`${key}=${value}`);
    }
  }
  if (DETECT_TOOLS.has(toolName) && typeof args.args === "string" && ENV_TOKEN.test(args.args)) {
    hits.push("args");
  }
  if (COMMAND_TOOLS.has(toolName)) {
    for (const key of ["command", "input"]) {
      if (typeof args[key] === "string" && ENV_TOKEN.test(args[key])) hits.push(key);
    }
  }
  return hits;
}
var handlers = {
  "tools.echo": (args) => {
    var _a;
    const text = String((_a = args.text) != null ? _a : "");
    void hostCall("host.fs.write", {
      path: "data://echo.log",
      content: text + "\n"
    }).catch(() => {
    });
    emit("echo.done", { length: text.length });
    return { text, length: text.length, plugin: "community.demo-kit", runtime: "process" };
  },
  "tools.lastRun": () => lastRun,
  "tools.notify": async (args) => {
    var _a, _b;
    await hostCall("host.notify", {
      title: String((_a = args.title) != null ? _a : ""),
      body: String((_b = args.body) != null ? _b : ""),
      key: args.key === void 0 ? void 0 : String(args.key)
    });
    return { ok: true };
  },
  "hooks.runFinished": (args) => {
    lastRun = args;
    return "stored";
  },
  "hooks.denyEnv": (payload) => {
    const toolName = String(payload.toolName || "").toLowerCase();
    const args = payload.arguments && typeof payload.arguments === "object" ? payload.arguments : {};
    const hits = inspectEnvAccess(toolName, args);
    if (hits.length === 0) return {};
    const detail = hits[0].slice(0, 120);
    return {
      decision: "deny",
      reason: `\u63D2\u4EF6\u6F14\u793A\u5957\u4EF6\u5DF2\u62D2\u7EDD\u8BBF\u95EE .env \u6587\u4EF6\uFF08${detail}\uFF09\uFF1A\u8BE5\u6587\u4EF6\u53EF\u80FD\u5305\u542B\u5BC6\u94A5\u3002`,
      logs: [`blocked ${toolName}: ${hits.join(", ")}`]
    };
  }
};
definePlugin({ handlers });
