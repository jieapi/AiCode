'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const sample = path.join(__dirname, '..', 'plugin-samples', 'background-approval');
const runtimeSource = fs.readFileSync(path.join(sample, 'dist/main.js'), 'utf8');
const pageSource = [...fs.readFileSync(path.join(sample, 'pages/ball.html'), 'utf8')
  .matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/g)].map(match => match[1]).find(source => source.trim());
const flush = () => new Promise(resolve => setImmediate(resolve));
const plain = value => JSON.parse(JSON.stringify(value));

function loadRuntime() {
  let onLine;
  let seq = 0;
  const pending = new Map();
  const env = { foreground: false, calls: [], timers: new Map(), intervals: new Map(), failCreate: false };
  const receive = message => onLine(JSON.stringify(message));
  vm.runInNewContext(runtimeSource, {
    require(name) {
      assert.equal(name, 'readline');
      return { createInterface: () => ({ on: (_event, handler) => { onLine = handler; } }) };
    },
    process: {
      stdin: {},
      stdout: {
        write(line) {
          const message = JSON.parse(line);
          if (!message.method) {
            const callback = pending.get(message.id);
            pending.delete(message.id);
            if (message.error) callback.reject(message.error);
            else callback.resolve(message.result);
            return;
          }
          env.calls.push(message);
          queueMicrotask(() => {
            if (message.method === 'host.overlay.create' && env.failCreate) {
              receive({ id: message.id, error: { code: 'OVERLAY_NOT_AUTHORIZED', message: 'not authorized' } });
            } else {
              const result = message.method === 'host.app.isForeground' ? env.foreground
                : message.method === 'host.overlay.create' ? { overlayId: 'demo.background-approval:ball' } : { ok: true };
              receive({ id: message.id, result });
            }
          });
        }
      }
    },
    setTimeout(callback, delay) { const id = Symbol('timer'); env.timers.set(id, { callback, delay }); return id; },
    clearTimeout(id) { env.timers.delete(id); },
    setInterval(callback) { const id = Symbol('interval'); env.intervals.set(id, callback); return id; },
    clearInterval(id) { env.intervals.delete(id); }
  }, { filename: 'background-approval/main.js' });
  env.invoke = (handler, args = {}) => new Promise((resolve, reject) => {
    const id = 'invoke-' + (++seq);
    pending.set(id, { resolve, reject });
    receive({ id, method: 'hook.invoke', params: { handler, args } });
  });
  env.message = payload => env.invoke('overlay.message', { overlayId: 'demo.background-approval:ball', payload });
  env.states = () => env.calls.filter(call => call.method === 'host.overlay.postMessage').map(call => call.params.payload);
  return env;
}

const review = (id = 'request-1') => ({ callId: id, toolName: 'Bash', arguments: { command: 'pwd' }, appForeground: false });

test('background approval uses one small overlay and collapses after the final decision', async () => {
  const env = loadRuntime();
  await env.invoke('hooks.boot');
  const result = env.invoke('hooks.reviewPermission', review());
  await flush();
  const create = env.calls.filter(call => call.method === 'host.overlay.create');
  assert.equal(create.length, 1);
  assert.deepEqual(create[0].params, { surface: 'ball', width: 48, height: 48, visibilityPolicy: 'background' });
  assert.equal(env.states().at(-1).request.requestId, 'request-1');
  await env.message({ type: 'decision', requestId: 'request-1', decision: 'allow' });
  assert.equal((await result).decision, 'allow');
  await flush();
  assert.deepEqual(env.states().at(-1), { type: 'approvalState', request: null, count: 0 });
  assert.equal(env.calls.some(call => call.method === 'host.overlay.close'), false);
  assert.equal(env.timers.size, 0);
  assert.equal(env.intervals.size, 0);
});

test('queued reviews switch directly without publishing an intermediate empty card', async () => {
  const env = loadRuntime();
  await env.invoke('hooks.boot');
  const first = env.invoke('hooks.reviewPermission', review('first'));
  await flush();
  const second = env.invoke('hooks.reviewPermission', review('second'));
  await flush();
  assert.equal(env.states().at(-1).count, 2);
  const start = env.states().length;
  await Promise.all([
    env.message({ type: 'ballReady' }),
    env.message({ type: 'decision', requestId: 'first', decision: 'deny' })
  ]);
  assert.equal((await first).decision, 'deny');
  await flush();
  const next = env.states().slice(start);
  assert.ok(next.length > 0);
  assert.ok(next.every(state => state.request && state.request.requestId === 'second'));
  await env.message({ type: 'decision', requestId: 'second', decision: 'allow' });
  assert.equal((await second).decision, 'allow');
});

test('stale and invalid decisions cannot approve the active review', async () => {
  const env = loadRuntime();
  const result = env.invoke('hooks.reviewPermission', review());
  await flush();
  assert.equal((await env.message({ type: 'decision', requestId: 'old', decision: 'allow' })).ok, false);
  assert.equal((await env.message({ type: 'decision', requestId: 'request-1', decision: 'other' })).ok, false);
  assert.equal(env.timers.size, 1);
  await env.message({ type: 'decision', requestId: 'request-1', decision: 'deny' });
  assert.equal((await result).decision, 'deny');
});

test('foreground requests bypass overlays and returning to the app drains queued reviews', async () => {
  const env = loadRuntime();
  assert.equal((await env.invoke('hooks.reviewPermission', { ...review(), appForeground: true })).decision, 'pass');
  assert.equal(env.calls.length, 0);
  const first = env.invoke('hooks.reviewPermission', review('first'));
  await flush();
  const second = env.invoke('hooks.reviewPermission', review('second'));
  await flush();
  env.foreground = true;
  await env.message({ type: 'foreground' });
  assert.equal((await first).decision, 'pass');
  assert.equal((await second).decision, 'pass');
  await flush();
  assert.equal(env.states().at(-1).request, null);
  assert.equal(env.timers.size, 0);
});

test('timeouts and display failures pass back to the in-app approval flow', async () => {
  const env = loadRuntime();
  const first = env.invoke('hooks.reviewPermission', review('first'));
  await flush();
  const timer = [...env.timers.values()][0];
  assert.equal(timer.delay, 4.5 * 60 * 1000);
  timer.callback();
  assert.equal((await first).decision, 'pass');
  const second = env.invoke('hooks.reviewPermission', review('second'));
  await flush();
  await env.message({ type: 'displayError', requestId: 'second' });
  assert.equal((await second).decision, 'pass');
  const denied = loadRuntime();
  denied.failCreate = true;
  assert.equal((await denied.invoke('hooks.reviewPermission', review())).decision, 'pass');
});

function loadPage(reducedMotion = false) {
  const elements = new Map();
  const env = {
    info: { width: 48, height: 48, x: 328, y: 704, density: 2, foreground: false },
    resizes: [], messages: [], dragRegions: [], pending: null
  };
  const element = id => {
    if (!elements.has(id)) {
      const classes = new Set();
      elements.set(id, {
        textContent: '', hidden: false, disabled: false, scrollTop: 0, attributes: {}, listeners: {},
        classList: {
          add: (...names) => names.forEach(name => classes.add(name)),
          remove: (...names) => names.forEach(name => classes.delete(name)),
          toggle(name, enabled) { if (enabled) classes.add(name); else classes.delete(name); },
          contains: name => classes.has(name)
        },
        setAttribute(name, value) { this.attributes[name] = value; },
        addEventListener(name, handler) { this.listeners[name] = handler; }
      });
    }
    return elements.get(id);
  };
  env.finish = change => {
    const resize = change || env.pending;
    env.pending = null;
    env.info.x = Math.max(0, Math.min(400 - resize.width, resize.options.x == null ? env.info.x : resize.options.x));
    env.info.y = Math.max(0, Math.min(800 - resize.height, resize.options.y == null ? env.info.y : resize.options.y));
    env.info.width = resize.width;
    env.info.height = resize.height;
    env.receive({ type: 'resize', ...plain(env.info) });
  };
  vm.runInNewContext(pageSource, {
    window: { matchMedia: () => ({ matches: reducedMotion }) },
    document: { getElementById: element },
    aicode: {
      overlay: {
        async self() { return plain(env.info); },
        async setDragRegion(rect) { env.dragRegions.push(plain(rect)); },
        async resize(width, height, options = {}) {
          const change = { width, height, options: plain(options) };
          env.resizes.push(change);
          if (options.animate) env.pending = change;
          else env.finish(change);
        },
        async postMessage(payload) { env.messages.push(plain(payload)); },
        onMessage(handler) { env.receive = handler; }
      }
    }
  }, { filename: 'background-approval/ball.html' });
  env.element = element;
  env.click = id => element(id).listeners.click();
  env.state = (id, count = 1) => env.receive({
    type: 'approvalState', count: id ? count : 0,
    request: id ? { requestId: id, toolName: 'Bash', preview: 'pwd' } : null
  });
  return env;
}

test('the approval page expands, minimizes and restores its original ball anchor', async () => {
  const env = loadPage();
  await flush();
  env.state('first');
  await flush();
  assert.deepEqual(env.resizes.at(-1), { width: 320, height: 280, options: { animate: true, x: null, y: null } });
  assert.equal(env.element('allow').disabled, true);
  env.finish();
  assert.equal(env.element('allow').disabled, false);
  assert.equal(env.element('shell').classList.contains('ready'), true);
  assert.deepEqual(env.dragRegions.at(-1), { left: 0, top: 0, width: 272, height: 48 });
  env.click('collapse');
  await flush();
  assert.deepEqual(env.resizes.at(-1).options, { animate: true, x: 328, y: 704 });
  env.finish();
  assert.equal(env.info.x, 328);
  assert.equal(env.info.y, 704);
  assert.equal(env.element('badge').classList.contains('show'), true);
  const size = env.resizes.length;
  env.state('first');
  await flush();
  assert.equal(env.resizes.length, size, '同一请求刷新不能重新展开已收起的卡片');
  env.receive({ type: 'click' });
  await flush();
  env.finish();
  env.click('allow');
  env.click('allow');
  assert.equal(env.messages.filter(message => message.type === 'decision').length, 1);
  env.state(null);
  await flush();
  env.finish();
  assert.equal(env.info.width, 48);
});

test('the next request updates in place and a dragged card keeps the ball with it', async () => {
  const env = loadPage();
  await flush();
  env.state('first', 2);
  await flush();
  env.finish();
  env.click('allow');
  const size = env.resizes.length;
  env.state('second');
  await flush();
  assert.equal(env.resizes.length, size);
  assert.equal(env.element('allow').disabled, false);
  env.info.x -= 30;
  env.info.y -= 40;
  env.receive({ type: 'dragEnd', x: env.info.x, y: env.info.y });
  env.state(null);
  await flush();
  env.finish();
  assert.equal(env.info.x, 298);
  assert.equal(env.info.y, 664);
});

test('rapid expansion cancellation and foreground changes leave the page collapsed', async () => {
  const env = loadPage();
  await flush();
  env.state('first');
  env.state(null);
  await flush();
  assert.equal(env.resizes.at(-1).width, 48);
  env.finish();
  env.state('second');
  await flush();
  env.finish();
  env.receive({ type: 'appState', foreground: true });
  await flush();
  env.finish();
  assert.equal(env.info.width, 48);
  assert.equal(env.element('allow').disabled, true);
  assert.equal(env.messages.at(-1).type, 'foreground');
  env.receive({ type: 'appState', foreground: false });
  await flush();
  env.finish();
  assert.equal(env.info.width, 320);
  assert.equal(env.element('allow').disabled, false);
});

test('reduced motion uses immediate geometry changes while preserving interaction', async () => {
  const env = loadPage(true);
  await flush();
  env.state('first');
  await flush();
  assert.equal(env.resizes.at(-1).options.animate, false);
  assert.equal(env.element('allow').disabled, false);
  assert.equal(env.info.width, 320);
});
