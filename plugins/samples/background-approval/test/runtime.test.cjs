'use strict';

const assert = require('node:assert/strict');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const { buildSync } = require('esbuild');

const sample = path.resolve(__dirname, '..');
const sdkEntry = path.resolve(__dirname, '../../../sdk/src/index.ts');
const runtimeSource = buildSync({
  entryPoints: [path.join(sample, 'src/index.ts')],
  bundle: true,
  platform: 'node',
  format: 'cjs',
  target: 'node12',
  write: false,
  logLevel: 'silent',
  alias: { '@jieapi/plugin-sdk': sdkEntry }
}).outputFiles[0].text;
const flush = () => new Promise(resolve => setImmediate(resolve));

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
