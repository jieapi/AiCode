"use strict";

// plugins/samples/provider-demo/src/index.ts
var import_http = require("http");
var import_url = require("url");

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
function log(message) {
  process.stderr.write("[plugin] " + message + "\n");
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

// plugins/samples/provider-demo/src/index.ts
var HOST = "127.0.0.1";
var PORT = 18080;
var MODEL = "demo-echo";
var MAX_BODY_BYTES = 4 * 1024 * 1024;
var server = null;
function sendJson(res, status, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": Buffer.byteLength(body)
  });
  res.end(body);
}
function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on("data", (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY_BYTES) {
        reject(new Error("request body too large"));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    req.on("error", reject);
  });
}
function textOf(content) {
  if (typeof content === "string") return content;
  if (!Array.isArray(content)) return "";
  return content.map((part) => {
    if (typeof part === "string") return part;
    if (part && typeof part.text === "string") return part.text;
    return "";
  }).join("");
}
function lastUserText(messages) {
  if (!Array.isArray(messages)) return "";
  for (let i = messages.length - 1; i >= 0; i -= 1) {
    const message = messages[i];
    if (message && message.role === "user") return textOf(message.content);
  }
  return "";
}
function roughTokens(text) {
  return Math.max(1, Math.ceil(String(text || "").length / 4));
}
function completionId() {
  return "chatcmpl-demo-" + Date.now().toString(36);
}
function handleChat(res, body) {
  const created = Math.floor(Date.now() / 1e3);
  const prompt = lastUserText(body.messages);
  const reply = "\u6F14\u793A\u63D0\u4F9B\u5546\u56DE\u58F0\uFF1A" + (prompt || "\uFF08\u6CA1\u6709\u6536\u5230\u7528\u6237\u6D88\u606F\uFF09");
  const promptTokens = roughTokens(prompt);
  const completionTokens = roughTokens(reply);
  if (body.stream) {
    const id = completionId();
    res.writeHead(200, {
      "Content-Type": "text/event-stream; charset=utf-8",
      "Cache-Control": "no-cache",
      "Connection": "keep-alive"
    });
    const chunks = reply.match(/[\s\S]{1,8}/g) || [reply];
    chunks.forEach((text) => {
      res.write(
        "data: " + JSON.stringify({
          id,
          object: "chat.completion.chunk",
          created,
          model: MODEL,
          choices: [{ index: 0, delta: { content: text }, finish_reason: null }]
        }) + "\n\n"
      );
    });
    res.write(
      "data: " + JSON.stringify({
        id,
        object: "chat.completion.chunk",
        created,
        model: MODEL,
        choices: [{ index: 0, delta: {}, finish_reason: "stop" }],
        usage: {
          prompt_tokens: promptTokens,
          completion_tokens: completionTokens,
          total_tokens: promptTokens + completionTokens
        }
      }) + "\n\n"
    );
    res.write("data: [DONE]\n\n");
    res.end();
    return;
  }
  sendJson(res, 200, {
    id: completionId(),
    object: "chat.completion",
    created,
    model: MODEL,
    choices: [
      {
        index: 0,
        message: { role: "assistant", content: reply },
        finish_reason: "stop"
      }
    ],
    usage: {
      prompt_tokens: promptTokens,
      completion_tokens: completionTokens,
      total_tokens: promptTokens + completionTokens
    }
  });
}
function handleRequest2(req, res) {
  const url = new import_url.URL(req.url || "/", "http://" + HOST + ":" + PORT);
  if (req.method === "GET" && (url.pathname === "/health" || url.pathname === "/v1/health")) {
    sendJson(res, 200, { ok: true, service: "demo.provider", port: PORT, model: MODEL });
    return;
  }
  if (req.method === "GET" && url.pathname === "/v1/models") {
    sendJson(res, 200, {
      object: "list",
      data: [{ id: MODEL, object: "model", created: 0, owned_by: "demo.provider" }]
    });
    return;
  }
  if (req.method === "POST" && url.pathname === "/v1/chat/completions") {
    readBody(req).then((raw) => {
      let body;
      try {
        body = JSON.parse(raw || "{}");
      } catch {
        sendJson(res, 400, { error: { message: "invalid json", type: "invalid_request_error" } });
        return;
      }
      handleChat(res, body);
    }).catch((error) => {
      if (!res.headersSent) {
        sendJson(res, 400, { error: { message: String(error && error.message || error) } });
      }
    });
    return;
  }
  sendJson(res, 404, { error: { message: "not found", path: url.pathname } });
}
function startServer() {
  if (server) return { ok: true, alreadyRunning: true, port: PORT, model: MODEL };
  server = (0, import_http.createServer)(handleRequest2);
  server.on("error", (error) => {
    log("provider-demo server error: " + error.message);
    server = null;
  });
  server.listen(PORT, HOST, () => log("provider-demo listening on http://" + HOST + ":" + PORT + "/v1"));
  return { ok: true, port: PORT, model: MODEL };
}
var handlers = {
  "provider.started": () => startServer(),
  "provider.start": () => startServer()
};
process.on("unhandledRejection", (error) => {
  const message = error == null ? void 0 : error.message;
  log("provider-demo unhandled rejection: " + String(message || error));
});
startServer();
definePlugin({ handlers });
