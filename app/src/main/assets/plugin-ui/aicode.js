(function () {
  'use strict';

  var pending = {};
  var nextId = 1;

  var backHandler = null;

  // 宿主返回键派发入口：页面注册的处理器同步返回 true 表示已消费，宿主不再退出插件页。
  window.__aicodeBack = function () {
    if (typeof backHandler !== 'function') return false;
    try {
      return backHandler() === true;
    } catch (e) {
      console.error('aicode: back handler failed', e);
      return false;
    }
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

  window.aicode = {
    context: function () {
      return call('context');
    },
    toast: function (message) {
      return call('toast', { message: String(message) });
    },
    ui: {
      close: function () {
        return call('ui.close');
      },
      // 注册页面内返回：系统返回手势 / 返回键先交给它，返回 true 时宿主不退出插件页。
      onBack: function (handler) {
        backHandler = typeof handler === 'function' ? handler : null;
        return Promise.resolve();
      }
    },
    fs: {
      read: function (path) {
        return call('fs.read', { path: String(path) });
      },
      write: function (path, content) {
        return call('fs.write', { path: String(path), content: String(content) });
      },
      list: function (path) {
        return call('fs.list', { path: String(path) });
      },
      delete: function (path) {
        return call('fs.delete', { path: String(path) });
      },
      stat: function (path) {
        return call('fs.stat', { path: String(path) });
      }
    },
    applyTheme: function (variables) {
      Object.keys(variables || {}).forEach(function (key) {
        document.documentElement.style.setProperty(key, variables[key]);
      });
    }
  };
})();
