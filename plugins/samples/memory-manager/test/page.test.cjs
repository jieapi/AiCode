'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const { transformSync } = require('esbuild');

const pluginDir = path.resolve(__dirname, '..');
const vueSource = fs.readFileSync(path.join(pluginDir, 'src/pages/memory.vue'), 'utf8');
const vueScript = [...vueSource.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/g)][0][1];
const stripped = transformSync(vueScript, { loader: 'ts' }).code
  .replace(/^import[^;]+;\s*$/gm, '')
  .replace('export default', 'const __component =');
const source = `${stripped}\nVue.createApp(__component).mount('#app');`;
const script = new vm.Script(source, { filename: 'memory.vue' });
const GLOBAL = '/root/.aicode/memory';
const PROJECT = '/root/workspace/.aicode/memory';

function memory(name, description, content) {
  return `---\nname: "${name}"\ndescription: "${description}"\n---\n${content}`;
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

function failure(code, message = code) {
  return Object.assign(new Error(message), { code });
}

function createPage(t, initial = {}, setup = () => {}) {
  const env = {
    contextKey: 'workspace-a',
    stores: new Map([['workspace-a', new Map(Object.entries(initial))]]),
    calls: [],
    watches: [],
    events: new Set(),
    timers: new Map(),
    hooks: {},
    toasts: [],
    backStopped: 0,
    initialSignal: false
  };
  const store = () => {
    if (!env.stores.has(env.contextKey)) env.stores.set(env.contextKey, new Map());
    return env.stores.get(env.contextKey);
  };
  const guard = (options) => {
    assert.equal(options.contextKey, env.contextKey, '文件调用应携带当前 contextKey');
  };
  const log = (method, filePath, options) => {
    const call = { method, path: filePath, options: options && { ...options } };
    env.calls.push(call);
    return call;
  };
  const sdk = {
    async context() {
      log('context');
      const value = { contextKey: env.contextKey, home: '/root', workspace: '/root/workspace' };
      return env.hooks.context ? env.hooks.context(value) : value;
    },
    async toast(message) {
      env.toasts.push(message);
      if (env.hooks.toast) await env.hooks.toast(message);
    },
    events: {
      on(handler) {
        env.events.add(handler);
        return () => env.events.delete(handler);
      }
    },
    ui: {
      async onBack() { return () => { env.backStopped++; }; },
      async close() { log('close'); }
    },
    fs: {
      async list(filePath, options) {
        const call = log('list', filePath, options);
        guard(options);
        assert.ok([GLOBAL, PROJECT].includes(filePath));
        const prefix = filePath + '/';
        const entries = [...store().keys()].filter((key) => key.startsWith(prefix))
          .map((key) => ({ name: key.slice(prefix.length), isDir: false }));
        if (env.hooks.list) return env.hooks.list(call, { entries });
        return { entries };
      },
      async read(filePath, options) {
        const call = log('read', filePath, options);
        guard(options);
        if (!store().has(filePath)) throw failure('NOT_FOUND');
        const value = { content: store().get(filePath) };
        if (env.hooks.read) return env.hooks.read(call, value);
        return value;
      },
      async write(filePath, content, options) {
        const call = log('write', filePath, options);
        if (env.hooks.write) await env.hooks.write(call);
        if (options.contextKey !== env.contextKey) throw failure('STALE_CONTEXT');
        assert.deepEqual(Object.keys(options), ['contextKey']);
        store().set(filePath, content);
      },
      async delete(filePath, options) {
        const call = log('delete', filePath, options);
        if (env.hooks.delete) await env.hooks.delete(call);
        if (options.contextKey !== env.contextKey) throw failure('STALE_CONTEXT');
        store().delete(filePath);
      },
      async watch(filePath, options, handler) {
        log('watch', filePath, options);
        assert.equal(options.recursive, false);
        const watch = { path: filePath, contextKey: env.contextKey, handler, stopped: 0 };
        env.watches.push(watch);
        const stop = async () => {
          watch.stopped++;
          if (env.stopFails) throw failure('CANCEL_FAILED');
        };
        if (env.initialSignal) handler({ path: filePath, changes: [], truncated: true });
        if (env.hooks.watch) await env.hooks.watch(watch);
        return stop;
      }
    }
  };
  let options;
  let state;
  script.runInNewContext({
    defineComponent: (options) => options,
    Vue: {
      createApp(config) {
        options = config;
        return {
          use() {},
          mount() {
            state = config.data();
            for (const [key, method] of Object.entries(config.methods)) state[key] = method.bind(state);
            for (const [key, getter] of Object.entries(config.computed)) {
              Object.defineProperty(state, key, { get: getter.bind(state) });
            }
          }
        };
      }
    },
    AicodeUI: {},
    aicode: sdk,
    setTimeout(handler) {
      const id = Symbol('timer');
      env.timers.set(id, handler);
      return id;
    },
    clearTimeout(id) { env.timers.delete(id); }
  });
  env.state = state;
  env.store = store;
  env.tick = () => new Promise(setImmediate);
  env.idle = async () => {
    await env.tick();
    if (state.refreshPromise) await state.refreshPromise;
    await env.tick();
  };
  env.signal = (filePath, kind = 'modified') => {
    for (const watch of env.watches) {
      if (!watch.stopped && watch.path === filePath) {
        watch.handler({ path: filePath, contextKey: env.contextKey, changes: [{ path: filePath + '/note.md', kind }] });
      }
    }
  };
  env.lifecycle = (reason, visible = true, contextKey = env.contextKey) => {
    env.contextKey = contextKey;
    for (const handler of env.events) handler({ name: 'ui.lifecycle', payload: { visible, reason, contextKey } });
  };
  env.unmount = () => {
    if (!state.disposed) options.beforeUnmount.call(state);
  };
  t.after(() => env.unmount());
  setup(env);
  options.mounted.call(state);
  return env;
}

const NOTE = GLOBAL + '/note.md';
const PROJECT_NOTE = PROJECT + '/project.md';
const initial = () => ({
  [NOTE]: memory('note', '原摘要', '原正文'),
  [PROJECT_NOTE]: memory('project', '项目摘要', '项目正文')
});

test('版本为 1.0.1，使用上下文中的文件根与 pluginApi', async (t) => {
  const manifest = JSON.parse(fs.readFileSync(path.join(pluginDir, 'plugin.json'), 'utf8'));
  assert.equal(manifest.version, '1.0.1');
  assert.equal(manifest.engines.pluginApi, '^1.0.0');
  const env = createPage(t, initial(), (env) => { env.initialSignal = true; });
  await env.idle();
  assert.deepEqual(env.calls.filter((call) => call.method === 'watch').map((call) => call.path), [GLOBAL, PROJECT]);
  assert.ok(env.calls.findIndex((call) => call.method === 'watch') < env.calls.findIndex((call) => call.method === 'list'));
  assert.equal(env.state.globalItems[0].content, '原正文');
  assert.equal(env.state.projectItems[0].description, '项目摘要');
  assert.equal(env.calls.filter((call) => call.method === 'list').length, 2);
});

test('增删改信号仅重读受影响范围，保留展开状态与有效选中项', async (t) => {
  const env = createPage(t, initial());
  await env.idle();
  const state = env.state;
  state.globalExpanded = false;
  state.selectionMode = true;
  state.selected = ['global:note.md', 'project:project.md'];
  env.calls.length = 0;
  env.store().set(GLOBAL + '/added.md', memory('added', '新增', '内容'));
  env.store().delete(NOTE);
  env.signal(GLOBAL, 'created');
  env.signal(GLOBAL, 'deleted');
  await env.idle();
  assert.equal(state.globalItems[0].name, 'added');
  assert.equal(state.globalExpanded, false);
  assert.equal(state.selectionMode, true);
  assert.deepEqual(Array.from(state.selected), ['project:project.md']);
  assert.deepEqual(env.calls.filter((call) => call.method === 'list').map((call) => call.path), [GLOBAL]);
});

test('详情同步摘要和正文，删除后回列表并明确提示', async (t) => {
  const env = createPage(t, initial());
  await env.idle();
  const state = env.state;
  state.openDetail(state.globalItems[0]);
  env.store().set(NOTE, memory('改名', '新摘要', '新正文'));
  env.signal(GLOBAL);
  await env.idle();
  assert.equal(state.view, 'detail');
  assert.equal(state.currentItem, state.globalItems[0]);
  assert.equal(state.currentItem.description, '新摘要');
  assert.equal(state.currentItem.content, '新正文');
  env.store().delete(NOTE);
  env.signal(GLOBAL, 'deleted');
  await env.idle();
  assert.equal(state.view, 'list');
  assert.equal(state.currentItem, null);
  assert.match(state.notice, /已被删除/);
});

test('刷新失败保留有效数据与详情，恢复后清除错误', async (t) => {
  const env = createPage(t, initial());
  await env.idle();
  const state = env.state;
  state.openDetail(state.globalItems[0]);
  state.selected = ['global:note.md'];
  env.hooks.list = async () => { throw failure('IO', '读取失败'); };
  env.signal(GLOBAL);
  await env.idle();
  assert.equal(state.globalItems.length, 1);
  assert.equal(state.view, 'detail');
  assert.deepEqual(Array.from(state.selected), ['global:note.md']);
  assert.match(state.refreshError, /读取失败/);
  delete env.hooks.list;
  env.signal(GLOBAL);
  await env.idle();
  assert.equal(state.refreshError, '');
});

test('列根不存在视为空，列出后已删除的文件不拖垮同范围刷新', async (t) => {
  const env = createPage(t, initial());
  await env.idle();
  env.hooks.list = async (call, result) => {
    if (call.path === PROJECT) throw failure('NOT_FOUND');
    result.entries.push({ name: 'gone.md', isDir: false }, { name: 'ignore.txt', isDir: false }, { name: 'dir.md', isDir: true });
    return result;
  };
  await env.state.loadAll();
  assert.equal(env.state.globalItems.length, 1);
  assert.equal(env.state.projectItems.length, 0);
  assert.equal(env.state.refreshError, '');
});

test('编辑刷新不改草稿，外部仅摘要变化也阻止旧草稿保存，重载后可保存', async (t) => {
  const env = createPage(t, initial());
  await env.idle();
  const state = env.state;
  state.openEdit(state.globalItems[0]);
  state.form.description = '草稿摘要';
  state.form.content = '草稿正文';
  env.signal(GLOBAL);
  env.signal(PROJECT);
  await env.idle();
  assert.equal(state.form.content, '草稿正文');
  assert.equal(state.conflict, '');
  env.store().set(NOTE, memory('note', '外改摘要', '原正文'));
  env.signal(GLOBAL);
  await env.idle();
  assert.equal(state.form.description, '草稿摘要');
  assert.equal(state.form.content, '草稿正文');
  assert.match(state.conflict, /外部修改/);
  await state.save();
  assert.equal(env.calls.filter((call) => call.method === 'write').length, 0);
  await state.reloadEdit();
  await env.idle();
  assert.equal(state.form.description, '外改摘要');
  assert.equal(state.conflict, '');
  state.form.content = '重新编辑';
  await state.save();
  assert.match(env.store().get(NOTE), /重新编辑/);
  assert.equal(state.view, 'list');
  assert.equal(state.editingItem, null);
  assert.equal(state.saving, false);
});

test('保存前再读基线，事件延迟也能发现已修改或删除的文件', async (t) => {
  const env = createPage(t, initial());
  await env.idle();
  const state = env.state;
  state.openEdit(state.globalItems[0]);
  state.form.content = '保留草稿';
  env.store().set(NOTE, memory('note', '外部摘要', '外部正文'));
  await state.save();
  assert.match(state.conflict, /外部修改/);
  assert.equal(state.form.content, '保留草稿');
  await state.reloadEdit();
  env.store().delete(NOTE);
  await state.save();
  assert.match(state.conflict, /外部删除/);
  assert.equal(env.calls.filter((call) => call.method === 'write').length, 0);
  await state.reloadEdit();
  await env.idle();
  assert.equal(state.view, 'list');
  assert.match(state.notice, /已被删除/);
});

test('放弃编辑返回最新详情，不复活旧编辑快照', async (t) => {
  const env = createPage(t, initial());
  await env.idle();
  const state = env.state;
  state.openEdit(state.globalItems[0]);
  env.store().set(NOTE, memory('note', '新摘要', '新正文'));
  env.signal(GLOBAL);
  await env.idle();
  assert.equal(state.handleBack(), true);
  assert.equal(state.view, 'detail');
  assert.equal(state.currentItem.content, '新正文');
  assert.equal(state.editingItem, null);
  assert.equal(state.conflict, '');
});

test('新建草稿不受刷新影响，保留原有同名保存语义', async (t) => {
  const env = createPage(t, initial());
  await env.idle();
  const state = env.state;
  state.openEdit(null);
  state.form.name = 'note';
  state.form.description = '新建摘要';
  state.form.content = '新建正文';
  env.signal(GLOBAL);
  await env.idle();
  assert.equal(state.form.content, '新建正文');
  assert.equal(state.conflict, '');
  await state.save();
  assert.match(env.store().get(NOTE), /新建正文/);
  assert.equal(state.view, 'list');
});

test('合并进行中的刷新，不应用比新信号更旧的结果', async (t) => {
  const env = createPage(t, initial());
  await env.idle();
  const gate = deferred();
  let blocked = false;
  env.hooks.read = async (call, value) => {
    if (call.path === NOTE && !blocked) {
      blocked = true;
      await gate.promise;
    }
    return value;
  };
  const state = env.state;
  const applied = [];
  const apply = state.applyScope;
  state.applyScope = (scope, items) => {
    applied.push(items.map((item) => item.content).join(','));
    apply(scope, items);
  };
  env.calls.length = 0;
  const first = state.requestRefresh(['global']);
  await env.tick();
  env.store().set(NOTE, memory('note', '新摘要', '最新正文'));
  for (let i = 0; i < 20; i++) env.signal(GLOBAL);
  assert.equal(state.refreshPromise, first);
  gate.resolve();
  await first;
  assert.deepEqual(applied, ['最新正文']);
  assert.equal(env.calls.filter((call) => call.method === 'list').length, 2);
});

test('刷新收尾同一轮微任务的新信号不会遗留在队列中', async (t) => {
  const env = createPage(t, initial());
  await env.idle();
  const state = env.state;
  const isCurrent = state.isCurrent;
  const apply = state.applyScope;
  let applied = false;
  let queued = false;
  state.applyScope = (scope, items) => {
    apply(scope, items);
    applied = true;
  };
  state.isCurrent = (epoch) => {
    if (applied && !queued && !Object.keys(state.pendingScopes).length) {
      queued = true;
      queueMicrotask(() => {
        env.store().set(NOTE, memory('note', '收尾更新', '收尾正文'));
        env.signal(GLOBAL);
      });
    }
    return isCurrent(epoch);
  };
  await state.requestRefresh(['global']);
  await env.idle();
  assert.equal(queued, true);
  assert.equal(state.globalItems[0].content, '收尾正文');
  assert.equal(Object.keys(state.pendingScopes).length, 0);
});

test('保存后的刷新未结束时，新编辑操作不被旧保存的收尾解锁', async (t) => {
  const env = createPage(t, initial());
  await env.idle();
  const state = env.state;
  const refreshGate = deferred();
  const writeGate = deferred();
  let blockRefresh = false;
  env.hooks.list = async (call, result) => {
    if (blockRefresh && call.path === GLOBAL) await refreshGate.promise;
    return result;
  };
  state.openEdit(state.globalItems[0]);
  state.form.content = '第一份保存';
  blockRefresh = true;
  await state.save();
  await env.tick();
  assert.equal(state.view, 'list');
  state.openEdit(null);
  state.form.name = 'second';
  state.form.content = '第二份保存';
  env.hooks.write = async () => { await writeGate.promise; };
  const saving = state.save();
  await env.tick();
  assert.equal(state.saving, true);
  refreshGate.resolve();
  await env.idle();
  assert.equal(state.saving, true);
  assert.equal(state.handleBack(), true);
  assert.equal(state.view, 'edit');
  writeGate.resolve();
  await saving;
  await env.idle();
  assert.equal(state.saving, false);
  assert.match(env.store().get(GLOBAL + '/second.md'), /第二份保存/);
});

test('上下文切换立即清空旧列表，旧刷新不阻塞也不覆盖新上下文', async (t) => {
  const env = createPage(t, initial());
  await env.idle();
  const gate = deferred();
  let blocked = false;
  env.hooks.read = async (call, value) => {
    if (call.options.contextKey === 'workspace-a' && call.path === NOTE && !blocked) {
      blocked = true;
      await gate.promise;
    }
    return value;
  };
  const state = env.state;
  state.openDetail(state.projectItems[0]);
  const first = state.loadAll();
  await env.tick();
  env.stores.set('workspace-b', new Map([[PROJECT_NOTE, memory('project', 'B摘要', 'B正文')]]));
  env.lifecycle('contextChanged', true, 'workspace-b');
  assert.equal(state.view, 'list');
  assert.equal(state.currentItem, null);
  assert.equal(state.globalItems.length, 0);
  assert.equal(state.projectItems.length, 0);
  await env.idle();
  assert.equal(state.projectItems[0].content, 'B正文');
  gate.resolve();
  await first;
  assert.equal(state.projectItems[0].content, 'B正文');
  assert.equal(state.globalItems.length, 0);
  assert.equal(env.watches.filter((watch) => watch.contextKey === 'workspace-a').every((watch) => watch.stopped === 1), true);
});

test('晚到的初始 context 响应不覆盖 lifecycle 提供的新上下文', async (t) => {
  const gate = deferred();
  const env = createPage(t, initial(), (env) => { env.hooks.context = () => gate.promise; });
  env.lifecycle('shown', true, 'workspace-b');
  await env.idle();
  gate.resolve({ contextKey: 'workspace-a' });
  await env.tick();
  assert.equal(env.state.contextKey, 'workspace-b');
  assert.equal(env.watches.every((watch) => watch.contextKey === 'workspace-b'), true);
});

test('上下文切换保留编辑草稿但禁止写新项目，切回也需明确放弃', async (t) => {
  const env = createPage(t, initial());
  await env.idle();
  const state = env.state;
  state.openEdit(state.projectItems[0]);
  state.form.content = '旧项目草稿';
  env.lifecycle('contextChanged', true, 'workspace-b');
  await env.idle();
  assert.equal(state.view, 'edit');
  assert.equal(state.form.content, '旧项目草稿');
  assert.match(state.conflict, /不能保存到新上下文/);
  await state.save();
  await state.reloadEdit();
  assert.equal(env.calls.filter((call) => call.method === 'write').length, 0);
  env.lifecycle('contextChanged', true, 'workspace-a');
  await env.idle();
  await state.save();
  assert.equal(env.calls.filter((call) => call.method === 'write').length, 0);
  state.discardEdit();
  assert.equal(state.view, 'list');
  assert.equal(state.contextConflict, false);
});

test('保存前主动核查上下文，不依赖 contextChanged 先到', async (t) => {
  const env = createPage(t, initial());
  await env.idle();
  const state = env.state;
  state.openEdit(null);
  state.form.name = 'new';
  state.form.scope = 'project';
  state.form.content = '原上下文草稿';
  env.contextKey = 'workspace-b';
  await state.save();
  await env.idle();
  assert.equal(state.form.content, '原上下文草稿');
  assert.equal(state.contextConflict, true);
  assert.equal(env.calls.filter((call) => call.method === 'write').length, 0);
});

test('切换后的旧保存响应不会丢弃仍保留的草稿', async (t) => {
  const env = createPage(t, initial());
  await env.idle();
  const state = env.state;
  const gate = deferred();
  state.openEdit(state.projectItems[0]);
  state.form.content = '保留中的草稿';
  env.hooks.write = async () => { await gate.promise; };
  const saving = state.save();
  await env.tick();
  env.lifecycle('contextChanged', true, 'workspace-b');
  await env.idle();
  gate.resolve();
  await saving;
  assert.equal(state.view, 'edit');
  assert.equal(state.form.content, '保留中的草稿');
  assert.equal(state.contextConflict, true);
  assert.equal(state.saving, false);
});

test('写调用携带编辑上下文，核查后切换也由宿主 STALE_CONTEXT 阻止', async (t) => {
  const env = createPage(t, initial());
  await env.idle();
  const state = env.state;
  state.openEdit(state.projectItems[0]);
  state.form.content = '旧项目草稿';
  env.hooks.write = async () => { env.contextKey = 'workspace-b'; };
  await state.save();
  await env.idle();
  assert.equal(state.contextConflict, true);
  assert.equal(state.form.content, '旧项目草稿');
  assert.equal(env.store().has(PROJECT_NOTE), false);
  assert.match(env.stores.get('workspace-a').get(PROJECT_NOTE), /项目正文/);
});

test('隐藏不刷新，shown/resumed 可刷新且忽略非 lifecycle 事件', async (t) => {
  const env = createPage(t, initial());
  await env.idle();
  env.calls.length = 0;
  env.lifecycle('hidden', false);
  for (const handler of env.events) handler({ name: 'ssh.status', payload: { visible: true } });
  await env.idle();
  assert.equal(env.calls.filter((call) => call.method === 'list').length, 0);
  env.store().set(NOTE, memory('note', '显示摘要', '显示正文'));
  env.lifecycle('shown');
  env.lifecycle('resumed');
  await env.idle();
  assert.equal(env.state.globalItems[0].content, '显示正文');
  assert.equal(env.calls.filter((call) => call.method === 'list').length, 2);
});

test('卸载清理订阅、事件、返回处理器与计时器，晚到订阅也取消', async (t) => {
  const gate = deferred();
  const env = createPage(t, initial(), (env) => {
    env.hooks.watch = async (watch) => { if (watch.path === PROJECT) await gate.promise; };
    env.stopFails = true;
  });
  await env.idle();
  const state = env.state;
  state.selectionMode = true;
  state.selected = ['global:note.md'];
  await state.batchDelete();
  assert.equal(env.timers.size, 1);
  env.unmount();
  gate.resolve();
  await env.tick();
  assert.equal(env.events.size, 0);
  assert.equal(env.backStopped, 1);
  assert.equal(env.timers.size, 0);
  assert.equal(env.watches.every((watch) => watch.stopped === 1), true);
  env.calls.length = 0;
  for (const watch of env.watches) watch.handler({ path: watch.path, changes: [] });
  await state.loadAll();
  await env.tick();
  assert.equal(env.calls.length, 0);
});

test('丢弃旧订阅与不匹配 contextKey 的信号，订阅失败可见但不影响初读', async (t) => {
  const env = createPage(t, initial(), (env) => {
    env.hooks.watch = async (watch) => { if (watch.path === PROJECT) throw failure('WATCH_FAILED'); };
  });
  await env.idle();
  assert.equal(env.state.globalItems.length, 1);
  assert.match(env.state.refreshError, /文件订阅失败/);
  const old = env.watches[0];
  env.lifecycle('contextChanged', true, 'workspace-b');
  await env.idle();
  env.calls.length = 0;
  old.handler({ path: GLOBAL, changes: [] });
  const current = env.watches.find((watch) => watch.contextKey === 'workspace-b' && watch.path === GLOBAL);
  current.handler({ path: GLOBAL, contextKey: 'workspace-a', changes: [] });
  await env.idle();
  assert.equal(env.calls.length, 0);
});

test('运行期订阅错误按 scope 展示且保留数据，显示与空校准信号仍能刷新', async (t) => {
  const env = createPage(t, initial());
  await env.idle();
  const state = env.state;
  state.openDetail(state.globalItems[0]);
  const globalWatch = env.watches.find((watch) => watch.path === GLOBAL);
  const projectWatch = env.watches.find((watch) => watch.path === PROJECT);
  env.calls.length = 0;
  globalWatch.handler({ path: GLOBAL, contextKey: env.contextKey, error: { code: 'WATCH_LIMIT', message: '全局范围超限' } });
  projectWatch.handler({ path: PROJECT, contextKey: env.contextKey, error: { code: 'WATCH_DENIED', message: '项目权限失效' } });
  await env.idle();
  assert.match(state.refreshError, /全局范围超限/);
  assert.match(state.refreshError, /项目权限失效/);
  assert.equal(state.globalItems.length, 1);
  assert.equal(state.projectItems.length, 1);
  assert.equal(state.currentItem.content, '原正文');
  assert.equal(env.calls.filter((call) => call.method === 'list').length, 0);
  env.store().set(NOTE, memory('note', '显示校准', '显示校准正文'));
  env.lifecycle('shown');
  await env.idle();
  assert.equal(state.currentItem.content, '显示校准正文');
  assert.match(state.refreshError, /全局范围超限/);
  assert.match(state.refreshError, /项目权限失效/);
  env.store().set(NOTE, memory('note', '空事件校准', '空事件校准正文'));
  globalWatch.handler({ path: GLOBAL, contextKey: env.contextKey, changes: [] });
  await env.idle();
  assert.equal(state.currentItem.content, '空事件校准正文');
  assert.equal(state.watchErrors.global, '');
  assert.match(state.refreshError, /项目权限失效/);
  projectWatch.handler({ path: PROJECT, contextKey: env.contextKey, changes: [] });
  await env.idle();
  assert.equal(state.refreshError, '');
});

test('单删和两步批删传上下文，批删第二次调用不引用未赋值 self', async (t) => {
  const env = createPage(t, initial());
  await env.idle();
  const state = env.state;
  await state.deleteItem(state.globalItems[0]);
  assert.equal(state.globalItems.length, 0);
  state.selectionMode = true;
  state.selected = ['project:project.md'];
  await state.batchDelete();
  assert.equal(state.batchConfirm, true);
  await state.batchDelete();
  assert.equal(state.projectItems.length, 0);
  assert.equal(state.selectionMode, false);
  assert.equal(env.timers.size, 0);
  assert.equal(env.calls.filter((call) => call.method === 'delete').length, 2);
});

test('旧批删提示晚返回不重置新上下文的选择状态', async (t) => {
  const env = createPage(t, initial());
  await env.idle();
  const state = env.state;
  const gate = deferred();
  state.selectionMode = true;
  state.selected = ['global:note.md'];
  await state.batchDelete();
  env.hooks.toast = async () => { await gate.promise; };
  const deleting = state.batchDelete();
  await env.tick();
  env.stores.set('workspace-b', new Map([[PROJECT_NOTE, memory('project', 'B摘要', 'B正文')]]));
  env.lifecycle('contextChanged', true, 'workspace-b');
  await env.idle();
  state.selectionMode = true;
  state.selected = ['project:project.md'];
  gate.resolve();
  await deleting;
  assert.equal(state.selectionMode, true);
  assert.deepEqual(Array.from(state.selected), ['project:project.md']);
});

test('批删中途切换上下文不继续删除新项目', async (t) => {
  const env = createPage(t, initial());
  await env.idle();
  const state = env.state;
  state.selectionMode = true;
  state.selected = ['global:note.md', 'project:project.md'];
  await state.batchDelete();
  env.hooks.delete = async () => { env.contextKey = 'workspace-b'; };
  await state.batchDelete();
  await env.idle();
  assert.equal(env.calls.filter((call) => call.method === 'delete').length, 1);
  assert.equal(env.stores.get('workspace-a').size, 2);
  assert.equal(state.contextKey, 'workspace-b');
});
