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

// plugins/samples/backup-helper/src/index.ts
var MAX_FILE_BYTES = 512 * 1024;
var MAX_FILES = 1500;
var SOURCES = [
  { scope: "global", root: "~/.aicode", entries: ["memory", "skills", "agents", "mcp.json"] },
  {
    scope: "project",
    root: "",
    entries: [".aicode/memory", ".aicode/skills", ".aicode/agents", ".aicode/mcp.json"]
  }
];
function join(root, entry) {
  return root ? root + "/" + entry : entry;
}
async function stat(path) {
  try {
    return await hostCall("host.fs.stat", { path });
  } catch {
    return { exists: false, isDir: false, size: 0 };
  }
}
async function collectFile(stagingDir, scope, root, relative, files, skipped) {
  const source = join(root, relative);
  const info = await stat(source);
  if (!info.exists) return;
  if (info.isDir) {
    let listing;
    try {
      listing = await hostCall("host.fs.list", { path: source });
    } catch {
      skipped.push({ scope, path: relative, reason: "list_failed" });
      return;
    }
    for (const entry of listing.entries || []) {
      if (files.length >= MAX_FILES) {
        skipped.push({ scope, path: relative, reason: "file_limit" });
        return;
      }
      await collectFile(stagingDir, scope, root, relative + "/" + entry.name, files, skipped);
    }
    return;
  }
  if (Number(info.size) > MAX_FILE_BYTES) {
    skipped.push({ scope, path: relative, reason: "too_large" });
    return;
  }
  try {
    const data = await hostCall("host.fs.readBinary", { path: source });
    await hostCall("host.fs.writeBinary", {
      path: `${stagingDir}/${scope}/${relative}`,
      base64: data.base64
    });
    files.push({
      scope,
      path: relative,
      size: Number(data.size) || Number(info.size) || 0
    });
  } catch {
    skipped.push({ scope, path: relative, reason: "read_failed" });
  }
}
async function collectAll(stagingDir, files, skipped) {
  for (const source of SOURCES) {
    for (const entry of source.entries) {
      if (files.length >= MAX_FILES) return;
      await collectFile(stagingDir, source.scope, source.root, entry, files, skipped);
    }
  }
}
async function readManifest(stagingDir) {
  try {
    const file = await hostCall("host.fs.read", {
      path: `${stagingDir}/manifest.json`
    });
    const manifest = JSON.parse(String(file.content || ""));
    if (manifest && Array.isArray(manifest.files)) return manifest;
  } catch {
  }
  return null;
}
async function scanSection(stagingDir, scope) {
  const files = [];
  const walk = async (relative) => {
    const path = relative ? `${stagingDir}/${scope}/${relative}` : `${stagingDir}/${scope}`;
    const info = await stat(path);
    if (!info.exists) return;
    if (info.isDir) {
      let listing;
      try {
        listing = await hostCall("host.fs.list", { path });
      } catch {
        return;
      }
      for (const entry of listing.entries || []) {
        await walk(relative ? relative + "/" + entry.name : entry.name);
      }
      return;
    }
    files.push({ scope, path: relative });
  };
  await walk("");
  return files;
}
async function restoreSection(stagingDir, manifest, skipMcp) {
  let restored = 0;
  const skipped = [];
  const files = (manifest == null ? void 0 : manifest.files) ? manifest.files.map((file) => ({ scope: file.scope, path: file.path })) : [...await scanSection(stagingDir, "global"), ...await scanSection(stagingDir, "project")];
  for (const file of files) {
    if (skipMcp && String(file.path || "").endsWith("mcp.json")) {
      skipped.push({ ...file, reason: "host_restored" });
      continue;
    }
    try {
      const data = await hostCall("host.fs.readBinary", {
        path: `${stagingDir}/${file.scope}/${file.path}`
      });
      const target = file.scope === "global" ? `~/.aicode/${file.path}` : file.path;
      await hostCall("host.fs.writeBinary", { path: target, base64: data.base64 });
      restored += 1;
    } catch {
      skipped.push({ ...file, reason: "restore_failed" });
    }
  }
  return { restored, skipped };
}
var handlers = {
  "hooks.onExportBefore": async (args) => {
    const stagingDir = String(args.dir || "");
    if (!stagingDir) throw new Error("missing staging dir");
    const files = [];
    const skipped = [];
    await collectAll(stagingDir, files, skipped);
    await hostCall("host.fs.write", {
      path: `${stagingDir}/manifest.json`,
      content: JSON.stringify({ version: 1, createdAt: Date.now(), files, skipped }, null, 2)
    });
    return {
      logs: [
        `staged ${files.length} files${skipped.length ? `, skipped ${skipped.length}` : ""}`,
        stagingDir
      ]
    };
  },
  "hooks.onImport": async (args) => {
    const stagingDir = String(args.dir || "");
    if (!stagingDir) throw new Error("missing staging dir");
    const manifest = await readManifest(stagingDir);
    const stats = args.stats;
    const skipMcp = Number(stats == null ? void 0 : stats.mcpServers) > 0;
    const result = await restoreSection(stagingDir, manifest, skipMcp);
    return {
      logs: [
        `restored ${result.restored} files${result.skipped.length ? `, skipped ${result.skipped.length}` : ""}`
      ]
    };
  }
};
definePlugin({ handlers });
