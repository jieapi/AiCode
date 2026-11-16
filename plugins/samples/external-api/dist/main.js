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

// plugins/samples/external-api/src/index.ts
var ENDPOINT = "https://httpbin.org/bearer";
var SECRET_KEY = "api-token";
async function readToken() {
  const result = await hostCall("host.secrets.get", { key: SECRET_KEY });
  return String((result == null ? void 0 : result.value) || "");
}
async function keyNames() {
  const result = await hostCall("host.secrets.list");
  return Array.isArray(result == null ? void 0 : result.keys) ? result.keys : [];
}
async function callEndpoint() {
  const token = await readToken();
  if (!token) {
    return { ok: false, error: "NO_TOKEN", message: "\u8FD8\u6CA1\u6709\u4FDD\u5B58 API Key\uFF0C\u5148\u5728\u914D\u7F6E\u9875\u4FDD\u5B58\u4E00\u6761\u3002" };
  }
  try {
    const response = await hostCall("host.http.request", {
      url: ENDPOINT,
      headers: { Authorization: "Bearer " + token },
      timeout: 20
    });
    let body = {};
    try {
      body = JSON.parse(String(response.body || "{}"));
    } catch {
      body = {};
    }
    return {
      ok: response.status === 200 && body.authenticated === true,
      status: response.status,
      authenticated: body.authenticated === true
    };
  } catch (error) {
    const code = error.code;
    return {
      ok: false,
      error: typeof code === "string" ? code : "HTTP_FAILED",
      message: String(error.message || error)
    };
  }
}
var handlers = {
  "tools.whoami": async () => {
    const result = await callEndpoint();
    if (result.ok) return { authenticated: true, status: result.status };
    return {
      authenticated: false,
      status: result.status === void 0 ? null : result.status,
      message: result.message || "API Key \u672A\u88AB\u63A5\u53D7"
    };
  },
  "actions.saveKey": async (args) => {
    const token = String(args.token || "").trim();
    if (!token) return { ok: false, message: "API Key \u4E0D\u80FD\u4E3A\u7A7A" };
    await hostCall("host.secrets.set", { key: SECRET_KEY, value: token });
    return { ok: true, keys: await keyNames() };
  },
  "actions.deleteKey": async () => {
    await hostCall("host.secrets.delete", { key: SECRET_KEY });
    return { ok: true, keys: await keyNames() };
  },
  "actions.listKeys": async () => ({ ok: true, keys: await keyNames() }),
  "actions.test": async () => callEndpoint()
};
definePlugin({ handlers });
