(function () {
  'use strict';

  var pending = {};
  var nextId = 1;

  // 页面内返回处理器栈：后注册的优先执行，返回 true 的处理器消费本次返回。
  var backHandlers = [];
  var eventHandlers = [];
  var overlayHandlers = [];

  // 宿主推送到浮层页面的消息：状态、点击/拖拽反馈都走这里。
  window.__aicodeOverlayMessage = function (payload) {
    var message;
    try {
      message = payload ? JSON.parse(payload) : null;
    } catch (e) {
      return;
    }
    overlayHandlers.slice().forEach(function (handler) {
      try {
        handler(message);
      } catch (e) {
        console.error('aicode: overlay handler failed', e);
      }
    });
  };

  // 宿主推送插件事件：payload 是 JSON 字符串，形如 { name, payload }。
  window.__aicodeEvent = function (payload) {
    var event;
    try {
      event = payload ? JSON.parse(payload) : null;
    } catch (e) {
      return;
    }
    eventHandlers.slice().forEach(function (handler) {
      try {
        handler(event);
      } catch (e) {
        console.error('aicode: event handler failed', e);
      }
    });
  };

  // 宿主返回键派发入口：页面注册的处理器同步返回 true 表示已消费，宿主不再退出插件页。
  window.__aicodeBack = function () {
    var snapshot = backHandlers.slice();
    for (var i = snapshot.length - 1; i >= 0; i--) {
      try {
        if (snapshot[i]() === true) return true;
      } catch (e) {
        console.error('aicode: back handler failed', e);
      }
    }
    return false;
  };

  window.__aicodeResolve = function (id, payload) {
    var entry = pending[id];
    if (!entry) return;
    delete pending[id];

    var data;
    try {
      data = payload ? JSON.parse(payload) : null;
    } catch (e) {
      entry.reject(new Error('invalid bridge response'));
      return;
    }

    if (data && data.ok) {
      entry.resolve(data.data === undefined ? null : data.data);
      return;
    }

    var error = new Error((data && data.error && data.error.message) || 'aicode error');
    error.code = (data && data.error && data.error.code) || 'INTERNAL';
    entry.reject(error);
  };

  function call(method, params) {
    return new Promise(function (resolve, reject) {
      var bridge = window.__aicodeBridge;
      if (!bridge) {
        reject(new Error('aicode bridge unavailable'));
        return;
      }

      var id = String(nextId++);
      pending[id] = { resolve: resolve, reject: reject };
      try {
        bridge.postMessage(JSON.stringify({ id: id, method: method, params: params || {} }));
      } catch (e) {
        delete pending[id];
        reject(e);
      }
    });
  }

  function fileParams(path, options) {
    var params = { path: String(path) };
    if (options && options.contextKey != null) params.contextKey = String(options.contextKey);
    return params;
  }

  window.aicode = {
    context: function () {
      return call('context');
    },
    toast: function (message) {
      return call('toast', { message: String(message) });
    },
    // App 进程前后台状态；用于插件决定是否显示或隐藏浮层。
    app: {
      isForeground: function () {
        return call('app.isForeground');
      },
      info: function () {
        return call('app.info');
      }
    },
    // 在对话内显示可关闭提示条；options.action = { label, handler } 时点击会回调插件进程。
    tip: function (options) {
      return call('tip', options || {});
    },
    // 通过 Shizuku 在 Android 系统执行命令；需插件声明范围且使用者已单独授权。
    shizuku: {
      exec: function (command, options) {
        var params = { command: String(command) };
        if (options && options.timeout != null) params.timeout = Number(options.timeout);
        return call('shizuku.exec', params);
      }
    },
    ui: {
      close: function () {
        return call('ui.close');
      },
      // 用系统浏览器打开 http/https 链接。
      openUrl: function (url) {
        return call('ui.openUrl', { url: String(url) });
      },
      // 页面内全屏弹层开关：打开时宿主按矩形在全局背景图上挖洞，关闭后恢复。
      setOverlay: function (visible, rect) {
        var params = { visible: visible !== false };
        if (params.visible && rect) {
          params.rect = {
            left: Number(rect.left) || 0,
            top: Number(rect.top) || 0,
            width: Number(rect.width) || 0,
            height: Number(rect.height) || 0,
            topRadius: Number(rect.topRadius) || 0,
            bottomRadius: Number(rect.bottomRadius) || 0,
            coversBottom: rect.coversBottom === true
          };
        }
        return call('ui.overlay', params);
      },
      // 注册页面内返回：系统返回手势 / 返回键先交给它，返回 true 时宿主不退出插件页。
      // 返回的 Promise 解析为注销函数，便于弹窗等临时界面关闭后移除处理器。
      onBack: function (handler) {
        if (typeof handler !== 'function') return Promise.resolve(function () {});
        backHandlers.push(handler);
        return Promise.resolve(function () {
          var index = backHandlers.indexOf(handler);
          if (index >= 0) backHandlers.splice(index, 1);
        });
      }
    },
    // 监听插件进程与宿主事件；返回取消订阅函数。
    events: {
      on: function (handler) {
        if (typeof handler !== 'function') return function () {};
        eventHandlers.push(handler);
        return function () {
          var index = eventHandlers.indexOf(handler);
          if (index >= 0) eventHandlers.splice(index, 1);
        };
      }
    },
    // 宿主托管的系统浮层；普通页面可创建/关闭，浮层页面可读自身信息并接收消息。
    overlay: {
      create: function (options) {
        var params = options || {};
        if (params.surface != null) params.surface = String(params.surface);
        return call('overlay.create', params);
      },
      update: function (overlayId, options) {
        var params = options || {};
        params.overlayId = String(overlayId);
        return call('overlay.update', params);
      },
      close: function (overlayId) {
        var params = {};
        if (overlayId != null) params.overlayId = String(overlayId);
        return call('overlay.close', params);
      },
      self: function () {
        return call('overlay.self');
      },
      // 浮层页面上报拖拽区域，坐标单位为 CSS 像素。
      setDragRegion: function (rect) {
        return call('overlay.setDragRegion', {
          left: Number(rect && rect.left) || 0,
          top: Number(rect && rect.top) || 0,
          width: Number(rect && rect.width) || 0,
          height: Number(rect && rect.height) || 0
        });
      },
      moveBy: function (dx, dy) {
        return call('overlay.moveBy', { dx: Number(dx) || 0, dy: Number(dy) || 0 });
      },
      // 浮层页面调整自身尺寸，单位为 CSS 像素（dp）。
      resize: function (width, height, options) {
        var params = { width: Number(width) || 0, height: Number(height) || 0 };
        if (options) {
          params.animate = options.animate === true;
          if (options.x != null) params.x = Number(options.x);
          if (options.y != null) params.y = Number(options.y);
        }
        return call('overlay.resize', params);
      },
      // 浮层页面设置可见性策略：always / foreground / background。
      setVisibilityPolicy: function (policy) {
        return call('overlay.setVisibilityPolicy', { policy: String(policy) });
      },
      // 浮层页面接收宿主消息（状态、click、dragStart、dragEnd、longPress、snap）。
      onMessage: function (handler) {
        if (typeof handler !== 'function') return function () {};
        overlayHandlers.push(handler);
        return function () {
          var index = overlayHandlers.indexOf(handler);
          if (index >= 0) overlayHandlers.splice(index, 1);
        };
      },
      // 浮层页面把交互或状态回传给插件进程；需要插件实现 overlay.message handler。
      postMessage: function (payload) {
        return call('overlay.postMessage', { payload: payload === undefined ? null : payload });
      }
    },
    // 调用同一插件的常驻进程 handler，返回 handler 的 result；不支持跨插件调用。
    invoke: function (handler, args) {
      return call('plugin.invoke', { handler: String(handler), args: args || {} });
    },
    // 在当前工作区执行一次性 Shell 命令，返回 { output, exitCode, truncated }。
    // 适合环境检测等短命令，不适合常驻进程。
    bash: function (command, options) {
      var params = { command: String(command) };
      if (options && options.timeout != null) params.timeout = Number(options.timeout);
      return call('bash', params);
    },
    // 插件显式选择共享终端标签：命令在后台运行，页面关闭也不会停止。
    // run 返回 { tabId, state }；用 poll 按偏移读增量输出，stop 发 Ctrl-C，close 关闭标签。
    terminal: {
      run: function (command, options) {
        var params = { command: String(command) };
        if (options && options.title != null) params.title = String(options.title);
        return call('terminal.run', params);
      },
      poll: function (tabId, offset) {
        return call('terminal.poll', { tabId: String(tabId), offset: Number(offset) || 0 });
      },
      stop: function (tabId) {
        return call('terminal.stop', { tabId: String(tabId) });
      },
      close: function (tabId) {
        return call('terminal.close', { tabId: String(tabId) });
      },
      // 向共享终端标签写入输入；appendNewline 默认 true。
      write: function (tabId, text, options) {
        var params = { tabId: String(tabId), text: String(text) };
        if (options && options.appendNewline != null) params.appendNewline = !!options.appendNewline;
        return call('terminal.write', params);
      },
      // 列出当前插件创建的标签。
      list: function () {
        return call('terminal.list');
      }
    },
    // 会话与消息：读取范围限定当前工作区；send / stop / open 需要聊天页存活。
    chat: {
      listSessions: function (options) {
        var params = {};
        if (options && options.includeSubagents != null) params.includeSubagents = !!options.includeSubagents;
        if (options && options.limit != null) params.limit = Number(options.limit);
        return call('chat.listSessions', params);
      },
      createSession: function (options) {
        var params = {};
        if (options && options.title != null) params.title = String(options.title);
        if (options && options.mode != null) params.mode = String(options.mode);
        return call('chat.createSession', params);
      },
      listMessages: function (sessionId, options) {
        var params = { sessionId: String(sessionId) };
        if (options && options.limit != null) params.limit = Number(options.limit);
        if (options && options.before != null) params.before = Number(options.before);
        if (options && options.after != null) params.after = Number(options.after);
        return call('chat.listMessages', params);
      },
      sendMessage: function (sessionId, text) {
        return call('chat.sendMessage', { sessionId: String(sessionId), text: String(text) });
      },
      setDraft: function (text, options) {
        var params = { text: String(text) };
        if (options && options.sessionId != null) params.sessionId = String(options.sessionId);
        if (options && options.append != null) params.append = !!options.append;
        return call('chat.setDraft', params);
      },
      stop: function (sessionId) {
        return call('chat.stop', { sessionId: String(sessionId) });
      },
      openSession: function (sessionId) {
        return call('chat.openSession', { sessionId: String(sessionId) });
      },
      getSession: function (sessionId) {
        return call('chat.getSession', { sessionId: String(sessionId) });
      },
      renameSession: function (sessionId, title) {
        return call('chat.renameSession', { sessionId: String(sessionId), title: String(title) });
      },
      pinSession: function (sessionId, pinned) {
        return call('chat.pinSession', { sessionId: String(sessionId), pinned: !!pinned });
      },
      deleteSession: function (sessionId) {
        return call('chat.deleteSession', { sessionId: String(sessionId) });
      },
      getRunState: function (sessionId) {
        return call('chat.getRunState', { sessionId: String(sessionId) });
      },
      waitForRun: function (sessionId, options) {
        var params = { sessionId: String(sessionId) };
        if (options && options.timeoutMs != null) params.timeoutMs = Number(options.timeoutMs);
        return call('chat.waitForRun', params);
      },
      listApprovals: function (sessionId) {
        return call('chat.listApprovals', { sessionId: String(sessionId) });
      },
      resolveToolApproval: function (sessionId, requestId, choice) {
        return call('chat.resolveToolApproval', {
          sessionId: String(sessionId),
          requestId: String(requestId),
          choice: String(choice)
        });
      },
      resolveQuestion: function (sessionId, requestId, answers) {
        return call('chat.resolveQuestion', {
          sessionId: String(sessionId),
          requestId: String(requestId),
          answers: answers || []
        });
      },
      resolvePlanApproval: function (sessionId, requestId, choice) {
        return call('chat.resolvePlanApproval', {
          sessionId: String(sessionId),
          requestId: String(requestId),
          choice: String(choice)
        });
      }
    },
    // 模型句柄：直接调用已配置的供应商，不经过聊天页或 Agent 循环。
    model: {
      listProviders: function () {
        return call('model.listProviders');
      },
      listModels: function (providerId) {
        var id = providerId && typeof providerId === 'object' ? providerId.providerId : providerId;
        return call('model.listModels', { providerId: String(id || '') });
      },
      create: function (options) {
        return call('model.create', options || {});
      },
      close: function (handleId) {
        var id = handleId && typeof handleId === 'object' ? handleId.handleId : handleId;
        return call('model.close', { handleId: String(id || '') });
      },
      start: function (options) {
        return call('model.start', options || {});
      },
      read: function (options) {
        return call('model.read', options || {});
      },
      cancel: function (streamId) {
        var id = streamId && typeof streamId === 'object' ? streamId.streamId : streamId;
        return call('model.cancel', { streamId: String(id || '') });
      },
      // start + read 循环；onEvent 逐条收到 text / reasoning / retry / keySwitch，返回最终 read 结果。
      stream: function (options, onEvent) {
        var source = options || {};
        var startParams = {};
        Object.keys(source).forEach(function (key) {
          if (key !== 'waitMs') startParams[key] = source[key];
        });
        var waitMs = source.waitMs == null ? 15000 : Number(source.waitMs);
        return call('model.start', startParams).then(function (started) {
          var cursor = 0;
          function step() {
            return call('model.read', {
              streamId: started.streamId,
              cursor: cursor,
              waitMs: waitMs
            }).then(function (result) {
              cursor = result.cursor;
              (result.events || []).forEach(function (event) {
                if (typeof onEvent !== 'function') return;
                try {
                  onEvent(event);
                } catch (e) {
                  console.error('aicode: model event handler failed', e);
                }
              });
              if (result.done) return result;
              return step();
            });
          }
          return step();
        });
      }
    },
    // 仅供插件维护自己在清单 contributes.providers 中声明的提供商；其它提供商一律不可见。
    providers: {
      listModels: function (providerId) {
        return call('providers.listModels', { providerId: String(providerId || '') });
      },
      addModel: function (providerId, model, options) {
        var params = {
          providerId: String(providerId || ''),
          model: String(model || '')
        };
        if (options && options.contextTokens != null) params.contextTokens = Number(options.contextTokens);
        return call('providers.addModel', params);
      },
      deleteModel: function (providerId, model) {
        return call('providers.deleteModel', {
          providerId: String(providerId || ''),
          model: String(model || '')
        });
      },
      setModelContext: function (providerId, model, contextTokens) {
        return call('providers.setModelContext', {
          providerId: String(providerId || ''),
          model: String(model || ''),
          contextTokens: Number(contextTokens)
        });
      },
      setApiKey: function (providerId, apiKey) {
        return call('providers.setApiKey', {
          providerId: String(providerId || ''),
          apiKey: String(apiKey == null ? '' : apiKey)
        });
      }
    },
    // 文件路径是当前执行环境的真实绝对路径：本地为容器内路径（如 /root/workspace/a.txt），
    // SSH 为远端服务器路径。~ 展开到当前 home，裸相对路径挂到工作区根。
    // 插件自己的数据目录用稳定的 data:// 虚拟根（如 data://config.json），与插件 ID 和执行环境无关。
    fs: {
      read: function (path, options) {
        return call('fs.read', fileParams(path, options));
      },
      write: function (path, content, options) {
        var params = fileParams(path, options);
        params.content = String(content);
        return call('fs.write', params);
      },
      list: function (path, options) {
        return call('fs.list', fileParams(path, options));
      },
      delete: function (path, options) {
        return call('fs.delete', fileParams(path, options));
      },
      stat: function (path, options) {
        return call('fs.stat', fileParams(path, options));
      },
      mkdir: function (path, options) {
        return call('fs.mkdir', fileParams(path, options));
      },
      rename: function (from, to, options) {
        var params = { from: String(from), to: String(to) };
        if (options && options.contextKey != null) params.contextKey = String(options.contextKey);
        return call('fs.rename', params);
      },
      copy: function (from, to, options) {
        var params = { from: String(from), to: String(to), overwrite: !!(options && options.overwrite) };
        if (options && options.contextKey != null) params.contextKey = String(options.contextKey);
        return call('fs.copy', params);
      },
      append: function (path, content, options) {
        var params = fileParams(path, options);
        params.content = String(content);
        return call('fs.append', params);
      },
      readBinary: function (path, options) {
        return call('fs.readBinary', fileParams(path, options));
      },
      writeBinary: function (path, base64, options) {
        var params = fileParams(path, options);
        params.base64 = String(base64);
        return call('fs.writeBinary', params);
      },
      watch: function (path, options, handler) {
        if (typeof options === 'function') {
          handler = options;
          options = {};
        }
        if (typeof handler !== 'function') {
          return Promise.reject(new TypeError('watch handler must be a function'));
        }
        var params = fileParams(path, options);
        params.subscriptionId = 'watch_' + String(nextId++);
        params.recursive = !!(options && options.recursive);
        var active = true;
        // 先注册接收端，避免宿主首次通知早于订阅确认而丢失。
        var off = window.aicode.events.on(function (event) {
          if (active && event && event.name === 'fs.changed' && event.payload &&
              event.payload.subscriptionId === params.subscriptionId) {
            handler(event.payload);
          }
        });
        return call('fs.watch', params).then(function () {
          return function () {
            if (!active) return Promise.resolve();
            active = false;
            off();
            return call('fs.unwatch', { subscriptionId: params.subscriptionId });
          };
        }, function (error) {
          active = false;
          off();
          throw error;
        });
      }
    },
    // 系统剪贴板与分享。
    clipboard: {
      read: function () {
        return call('clipboard.read');
      },
      write: function (text) {
        return call('clipboard.write', { text: String(text) });
      }
    },
    share: {
      text: function (text, options) {
        var params = { text: String(text) };
        if (options && options.title != null) params.title = String(options.title);
        return call('share.text', params);
      }
    },
    // 插件私有的加密键值存储；list 只返回键名。
    secrets: {
      get: function (key) {
        return call('secrets.get', { key: String(key) });
      },
      set: function (key, value) {
        return call('secrets.set', { key: String(key), value: String(value) });
      },
      delete: function (key) {
        return call('secrets.delete', { key: String(key) });
      },
      list: function () {
        return call('secrets.list');
      }
    },
    // 宿主工具目录与执行：只允许内置 / MCP 工具，插件工具（plugin__）不能经此调用。
    // 执行受宿主授权策略约束：需要确认时 execute 会挂起，用 pending / resolve 处理。
    sessionSource: {
      setMessages: function (options) { return call('sessionSource.setMessages', options); },
      pushEvent: function (options) { return call('sessionSource.pushEvent', options); }
    },
    permissions: {
      request: function (options) { return call('permissions.request', options); },
      cancel: function (requestId) { return call('permissions.cancel', { requestId: String(requestId) }); }
    },
    tools: {
      list: function (options) {
        var params = {};
        if (options && options.includeSchema != null) params.includeSchema = !!options.includeSchema;
        return call('tools.list', params);
      },
      // options: { sessionId?, context?: { path?, selection?, language? }, timeoutMs? }
      execute: function (name, args, options) {
        var params = { name: String(name), arguments: args || {} };
        if (options && options.sessionId != null) params.sessionId = String(options.sessionId);
        if (options && options.timeoutMs != null) params.timeoutMs = Number(options.timeoutMs);
        if (options && options.context) {
          var source = options.context;
          var context = {};
          if (source.path != null) context.path = String(source.path);
          if (source.selection != null) context.selection = String(source.selection);
          if (source.language != null) context.language = String(source.language);
          params.context = context;
        }
        return call('tools.execute', params);
      },
      pending: function (sessionId) {
        var params = {};
        if (sessionId != null) params.sessionId = String(sessionId);
        return call('tools.pending', params);
      },
      resolve: function (requestId, choice) {
        return call('tools.resolve', { requestId: String(requestId), choice: String(choice) });
      }
    },
    mcp: {
      status: function () {
        return call('mcp.status');
      }
    },
    // 宿主代理的 HTTP 请求；不注入宿主凭据、不跟随重定向。
    http: {
      request: function (options) {
        return call('http.request', options || {});
      }
    },
    // 写宿主日志，便于插件作者在应用日志里排查。
    log: {
      write: function (level, message, options) {
        var params = { level: String(level), message: String(message) };
        if (options && options.tag != null) params.tag = String(options.tag);
        return call('log.write', params);
      }
    },
    applyTheme: function (variables) {
      Object.keys(variables || {}).forEach(function (key) {
        document.documentElement.style.setProperty(key, variables[key]);
      });
      try {
        window.dispatchEvent(new CustomEvent('aicode:theme', { detail: variables || {} }));
      } catch (e) {
        // 旧 WebView 不支持 CustomEvent 时忽略。
      }
    }
  };
})();
