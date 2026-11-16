"use strict";
var __create = Object.create;
var __defProp = Object.defineProperty;
var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
var __getOwnPropNames = Object.getOwnPropertyNames;
var __getProtoOf = Object.getPrototypeOf;
var __hasOwnProp = Object.prototype.hasOwnProperty;
var __copyProps = (to, from, except, desc) => {
  if (from && typeof from === "object" || typeof from === "function") {
    for (let key of __getOwnPropNames(from))
      if (!__hasOwnProp.call(to, key) && key !== except)
        __defProp(to, key, { get: () => from[key], enumerable: !(desc = __getOwnPropDesc(from, key)) || desc.enumerable });
  }
  return to;
};
var __toESM = (mod, isNodeMode, target) => (target = mod != null ? __create(__getProtoOf(mod)) : {}, __copyProps(
  // If the importer is in node compatibility mode or this is not an ESM
  // file that has been converted to a CommonJS file using a Babel-
  // compatible transform (i.e. "__esModule" has not been set), then set
  // "default" to the CommonJS "module.exports" for node compatibility.
  isNodeMode || !mod || !mod.__esModule ? __defProp(target, "default", { value: mod, enumerable: true }) : target,
  mod
));

// plugins/samples/ssh-manager/src/index.ts
var import_fs = require("fs");
var import_path = __toESM(require("path"));

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

// plugins/samples/ssh-manager/src/index.ts
var DATA_DIR = process.env.AICODE_DATA_DIR || import_path.default.join(process.env.HOME || "/root", ".aicode", "plugins", "data", "demo.ssh-manager");
var CONFIG_PATH = import_path.default.join(DATA_DIR, "servers.json");
var CONNECT_TIMEOUT_MS = 25e3;
var DEFAULT_EXEC_TIMEOUT_SECONDS = 30;
var MAX_EXEC_TIMEOUT_SECONDS = 120;
var MAX_OUTPUT_CHARS = 2e4;
var connections = /* @__PURE__ */ new Map();
var ssh2 = null;
function readServers() {
  try {
    const parsed = JSON.parse((0, import_fs.readFileSync)(CONFIG_PATH, "utf8"));
    return Array.isArray(parsed.servers) ? parsed.servers : [];
  } catch {
    return [];
  }
}
function expandHome(value) {
  const text = String(value || "").trim();
  if (text === "~") return process.env.HOME || "/root";
  if (text.startsWith("~/")) return import_path.default.join(process.env.HOME || "/root", text.slice(2));
  if (text.startsWith("data://")) return import_path.default.join(DATA_DIR, text.slice("data://".length));
  return text;
}
function getSsh2() {
  if (!ssh2) {
    try {
      ssh2 = require("ssh2");
    } catch {
      throw new Error("ssh2 \u672A\u5B89\u88C5\uFF0C\u8BF7\u5148\u5728\u63D2\u4EF6\u8BE6\u60C5\u70B9\u300C\u51C6\u5907\u4F9D\u8D56\u300D");
    }
  }
  return ssh2;
}
function connectionStatus(serverId) {
  var _a;
  return ((_a = connections.get(serverId)) == null ? void 0 : _a.status) || "offline";
}
function connectionKey(server) {
  return [
    server.host,
    server.port,
    server.username,
    server.auth,
    server.privateKeyPath,
    server.passphrase,
    server.password
  ].map((value) => String(value == null ? "" : value)).join("\0");
}
function publicServer(server) {
  const serverId = String(server.id || "");
  const conn = connections.get(serverId);
  return {
    id: serverId,
    name: String(server.name || server.host || server.id || ""),
    host: String(server.host || ""),
    port: Number(server.port) || 22,
    username: String(server.username || ""),
    enabled: server.enabled !== false,
    auth: server.auth === "password" ? "password" : "key",
    status: connectionStatus(serverId),
    error: (conn == null ? void 0 : conn.error) || null
  };
}
function publicServers() {
  return readServers().map(publicServer);
}
function emitStatus(serverId, status, error) {
  emit("ssh.status", {
    serverId,
    status,
    error: error ? String(error).slice(0, 300) : null
  });
}
function buildConnectionConfig(server) {
  const config = {
    host: String(server.host || ""),
    port: Number(server.port) || 22,
    username: String(server.username || ""),
    readyTimeout: CONNECT_TIMEOUT_MS,
    keepaliveInterval: 1e4,
    keepaliveCountMax: 3
  };
  if (!config.host) throw new Error(`\u670D\u52A1\u5668 ${server.id} \u7F3A\u5C11 host`);
  if (!config.username) throw new Error(`\u670D\u52A1\u5668 ${server.id} \u7F3A\u5C11 username`);
  if (server.auth === "password") {
    config.password = String(server.password || "");
  } else {
    const keyPath = expandHome(server.privateKeyPath);
    if (!keyPath) throw new Error(`\u670D\u52A1\u5668 ${server.id} \u7F3A\u5C11 privateKeyPath`);
    config.privateKey = (0, import_fs.readFileSync)(keyPath);
    if (server.passphrase) config.passphrase = String(server.passphrase);
  }
  return config;
}
function connectServer(server) {
  if (!server || !server.id) return Promise.reject(new Error("invalid server"));
  const serverId = String(server.id);
  const configKey = connectionKey(server);
  const existing = connections.get(serverId);
  if (existing && existing.configKey === configKey) {
    if (existing.status === "online") return Promise.resolve(existing);
    if (existing.pending) return existing.pending;
  } else if (existing) {
    closeServer(serverId);
  }
  let Client;
  try {
    Client = getSsh2().Client;
  } catch (error) {
    emitStatus(serverId, "offline", error.message);
    return Promise.reject(error);
  }
  const client = new Client();
  const conn = {
    client,
    status: "connecting",
    error: null,
    pending: null,
    server,
    configKey,
    closed: false
  };
  connections.set(serverId, conn);
  emitStatus(serverId, "connecting");
  const pending2 = new Promise((resolve, reject) => {
    let settled = false;
    const timer = setTimeout(() => {
      try {
        client.end();
      } catch {
      }
      finish(new Error("\u8FDE\u63A5\u8D85\u65F6"));
    }, CONNECT_TIMEOUT_MS + 5e3);
    function finish(error) {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      conn.pending = null;
      if (conn.closed) {
        if (error) reject(error);
        else reject(new Error("\u8FDE\u63A5\u5DF2\u5173\u95ED"));
        return;
      }
      if (error) {
        conn.status = "offline";
        conn.error = String(error.message || error).slice(0, 300);
        emitStatus(serverId, "offline", conn.error);
        reject(error);
      } else {
        conn.status = "online";
        conn.error = null;
        emitStatus(serverId, "online");
        resolve(conn);
      }
    }
    client.on("ready", () => finish(null));
    client.on("error", (error) => finish(error));
    client.on("close", () => {
      if (conn.closed || connections.get(serverId) !== conn) return;
      const wasOnline = conn.status === "online";
      conn.status = "offline";
      conn.pending = null;
      if (wasOnline) emitStatus(serverId, "offline", conn.error);
    });
    try {
      client.connect(buildConnectionConfig(server));
    } catch (error) {
      finish(error);
    }
  });
  conn.pending = pending2;
  pending2.catch(() => {
  });
  return pending2;
}
function closeServer(serverId) {
  const conn = connections.get(serverId);
  if (!conn) return;
  conn.closed = true;
  try {
    conn.client.end();
  } catch {
  }
  connections.delete(serverId);
  emitStatus(serverId, "offline");
}
function bootstrap() {
  const servers = readServers();
  const enabled = servers.filter((server) => server.enabled !== false);
  for (const server of enabled) {
    connectServer(server).catch((error) => {
      void hostCall("host.notify", {
        title: "SSH \u8FDE\u63A5\u5931\u8D25",
        body: `${server.name || server.host || server.id}: ${String(error.message || error).slice(0, 200)}`,
        key: `ssh-${server.id}`
      }).catch(() => {
      });
    });
  }
}
async function ensureConnection(server) {
  const serverId = String(server.id || "");
  const conn = connections.get(serverId);
  if (conn && conn.status === "online") return conn;
  return connectServer(server);
}
function execRemote(server, command, timeoutSeconds) {
  return ensureConnection(server).then(
    (conn) => new Promise((resolve) => {
      let settled = false;
      let output = "";
      let exitCode = null;
      let timedOut = false;
      let stream;
      const timer = setTimeout(() => {
        timedOut = true;
        try {
          stream == null ? void 0 : stream.close();
        } catch {
        }
        finish();
      }, Math.max(1, timeoutSeconds) * 1e3);
      function append(data) {
        if (output.length >= MAX_OUTPUT_CHARS) return;
        output += String(data);
        if (output.length > MAX_OUTPUT_CHARS) {
          output = output.slice(0, MAX_OUTPUT_CHARS) + "\n...(\u8F93\u51FA\u5DF2\u622A\u65AD)";
        }
      }
      function finish() {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        resolve({ output, exitCode, timedOut });
      }
      conn.client.exec(command, { pty: false }, (error, channel) => {
        if (error) {
          clearTimeout(timer);
          settled = true;
          resolve({ output: String(error.message || error), exitCode: null, timedOut: false });
          return;
        }
        stream = channel;
        channel.on("data", append);
        channel.stderr.on("data", append);
        channel.on("close", (code) => {
          exitCode = typeof code === "number" ? code : null;
          finish();
        });
      });
    })
  );
}
function findServer(serverId) {
  return readServers().find((server) => String(server.id) === String(serverId));
}
var handlers = {
  "hooks.boot": () => {
    void Promise.resolve().then(bootstrap).catch((error) => {
      log(`bootstrap failed: ${error.message || error}`);
    });
    return { started: true };
  },
  "tools.list": () => ({ servers: publicServers() }),
  "tools.status": async (args) => {
    const serverId = args.serverId === void 0 ? "" : String(args.serverId);
    if (!serverId) return { servers: publicServers() };
    const server = findServer(serverId);
    if (!server) throw new Error(`\u672A\u77E5 serverId: ${serverId}`);
    const result = await execRemote(server, "uptime; df -h /", 15);
    return { server: publicServer(server), ...result };
  },
  "tools.exec": async (args) => {
    const serverId = String(args.serverId || "");
    const server = findServer(serverId);
    if (!server) throw new Error(`\u672A\u77E5 serverId: ${serverId}`);
    const command = String(args.command || "").trim();
    if (!command) throw new Error("command \u4E0D\u80FD\u4E3A\u7A7A");
    if (command.length > 4e3) throw new Error("command \u8FC7\u957F");
    const timeout = Math.min(
      MAX_EXEC_TIMEOUT_SECONDS,
      Math.max(1, Number(args.timeout) || DEFAULT_EXEC_TIMEOUT_SECONDS)
    );
    const result = await execRemote(server, command, timeout);
    return { serverId, command, ...result };
  },
  "ui.status": () => ({ servers: publicServers() }),
  "ui.connect": async (args) => {
    const server = findServer(String(args.serverId || ""));
    if (!server) throw new Error("\u672A\u77E5\u670D\u52A1\u5668");
    await connectServer(server);
    return { server: publicServer(server) };
  },
  "ui.disconnect": (args) => {
    closeServer(String(args.serverId || ""));
    return { ok: true };
  },
  "ui.reload": async () => {
    const servers = readServers();
    const byId = new Map(servers.map((server) => [String(server.id), server]));
    for (const id of Array.from(connections.keys())) {
      const server = byId.get(id);
      const conn = connections.get(id);
      if (!server || server.enabled === false || !conn || conn.configKey !== connectionKey(server)) {
        closeServer(id);
      }
    }
    const enabled = servers.filter((server) => server.enabled !== false);
    await Promise.allSettled(enabled.map((server) => connectServer(server)));
    return { servers: publicServers() };
  }
};
process.on("unhandledRejection", (error) => {
  log(`ssh-manager unhandled rejection: ${(error == null ? void 0 : error.message) || error}`);
});
definePlugin({ handlers });
