(function () {
  'use strict';

  var pending = {};
  var nextId = 1;

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
