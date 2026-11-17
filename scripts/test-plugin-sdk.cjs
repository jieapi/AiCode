'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const sdkPath = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'plugin-ui', 'aicode.js');
const sdkSource = fs.readFileSync(sdkPath, 'utf8');

function loadSdk(onPost) {
  const requests = [];
  const errors = [];
  const window = {
    __aicodeBridge: {
      postMessage(message) {
        const request = JSON.parse(message);
        requests.push(request);
        if (onPost) onPost(request, harness);
      }
    }
  };
  const harness = {
    window,
    requests,
    errors,
    resolve(request, data) {
      window.__aicodeResolve(request.id, JSON.stringify({ ok: true, data }));
    },
    reject(request, code, message) {
      window.__aicodeResolve(request.id, JSON.stringify({ ok: false, error: { code, message } }));
    },
    event(name, payload) {
      window.__aicodeEvent(JSON.stringify({ name, payload }));
    }
  };
  vm.runInNewContext(sdkSource, {
    window,
    console: { error: (...args) => errors.push(args) }
  }, { filename: sdkPath });
  harness.sdk = window.aicode;
  return harness;
}

function plain(value) {
  return JSON.parse(JSON.stringify(value));
}

test('permissions forwards bound request and cancellation without changing decisions', async () => {
  const harness = loadSdk();
  const options = { sessionId: 'source|opaque', requestId: 'r1', title: 'Confirm', description: 'Run',
    operation: { kind: 'shell', command: 'pwd' }, timeoutMs: 1000 };
  const pending = harness.sdk.permissions.request(options);
  assert.equal(harness.requests[0].method, 'permissions.request');
  assert.deepEqual(harness.requests[0].params, options);
  harness.resolve(harness.requests[0], { decision: 'allow' });
  assert.deepEqual(plain(await pending), { decision: 'allow' });
  const cancelled = harness.sdk.permissions.cancel('r1');
  assert.equal(harness.requests[1].method, 'permissions.cancel');
  assert.deepEqual(harness.requests[1].params, { requestId: 'r1' });
  harness.resolve(harness.requests[1], { cancelled: true });
  assert.deepEqual(plain(await cancelled), { cancelled: true });
});

function changed(request, extra = {}) {
  return {
    subscriptionId: request.params.subscriptionId,
    path: request.params.path,
    contextKey: 'workspace-a',
    changes: [{ path: request.params.path + '/updated.json', kind: 'modified' }],
    truncated: false,
    ...extra
  };
}

function trackListeners(sdk) {
  const listeners = new Set();
  const originalOn = sdk.events.on;
  sdk.events.on = function (handler) {
    const off = originalOn(handler);
    listeners.add(handler);
    return function () {
      listeners.delete(handler);
      off();
    };
  };
  return listeners;
}

async function cancelSubscription(harness, cancel, subscriptionId) {
  const previousCount = harness.requests.length;
  const pending = cancel();
  assert.equal(typeof pending.then, 'function');
  assert.equal(harness.requests.length, previousCount + 1);
  const request = harness.requests[previousCount];
  assert.equal(request.method, 'fs.unwatch');
  assert.deepEqual(request.params, { subscriptionId });
  harness.resolve(request, null);
  await pending;
}

for (const watchedPath of [
  'data://settings/layout.json',
  'data://cache/exports',
  'workspace://src/components',
  'workspace://notes/project.md'
]) {
  test('fs.watch supports the generic path ' + watchedPath + ' with the handler overload', async () => {
    const harness = loadSdk();
    const received = [];
    const registration = harness.sdk.fs.watch(watchedPath, payload => received.push(plain(payload)));
    assert.equal(typeof registration.then, 'function');
    assert.equal(harness.requests.length, 1);
    const request = harness.requests[0];
    assert.equal(request.method, 'fs.watch');
    assert.match(request.params.subscriptionId, /^[A-Za-z0-9_-]{1,64}$/);
    assert.deepEqual(request.params, {
      path: watchedPath,
      recursive: false,
      subscriptionId: request.params.subscriptionId
    });

    harness.resolve(request, { subscriptionId: request.params.subscriptionId });
    const cancel = await registration;
    assert.equal(typeof cancel, 'function');
    const payload = changed(request);
    harness.event('fs.changed', payload);
    assert.deepEqual(received, [payload]);
    await cancelSubscription(harness, cancel, request.params.subscriptionId);
    assert.deepEqual(harness.errors, []);
  });
}

test('fs.watch forwards recursive and contextKey options without changing the path', async () => {
  const harness = loadSdk();
  const received = [];
  const registration = harness.sdk.fs.watch('workspace://assets', {
    recursive: true,
    contextKey: 'remote-project-a'
  }, payload => received.push(plain(payload)));
  const request = harness.requests[0];
  assert.deepEqual(request.params, {
    path: 'workspace://assets',
    recursive: true,
    contextKey: 'remote-project-a',
    subscriptionId: request.params.subscriptionId
  });
  harness.resolve(request, { subscriptionId: request.params.subscriptionId });
  const cancel = await registration;
  const payload = changed(request, { contextKey: 'remote-project-a', truncated: true });
  harness.event('fs.changed', payload);
  assert.deepEqual(received, [payload]);
  await cancelSubscription(harness, cancel, request.params.subscriptionId);
});

test('two fs.watch subscriptions only receive their own fs.changed payloads', async () => {
  const harness = loadSdk();
  const firstEvents = [];
  const secondEvents = [];
  const allEvents = [];
  const off = harness.sdk.events.on(event => allEvents.push(plain(event)));
  const firstPending = harness.sdk.fs.watch('data://profile.json', payload => firstEvents.push(plain(payload)));
  const secondPending = harness.sdk.fs.watch('workspace://src', {}, payload => secondEvents.push(plain(payload)));
  const [first, second] = harness.requests;
  assert.notEqual(first.params.subscriptionId, second.params.subscriptionId);
  assert.equal(second.params.recursive, false);
  harness.resolve(first, { subscriptionId: first.params.subscriptionId });
  harness.resolve(second, { subscriptionId: second.params.subscriptionId });
  const [cancelFirst, cancelSecond] = await Promise.all([firstPending, secondPending]);
  const firstPayload = changed(first);
  const secondPayload = changed(second);
  const unrelated = changed(first, { subscriptionId: 'unrelated-subscription' });
  harness.event('fs.changed', firstPayload);
  harness.event('fs.changed', secondPayload);
  harness.event('fs.changed', unrelated);
  harness.event('plugin.changed', firstPayload);

  assert.deepEqual(firstEvents, [firstPayload]);
  assert.deepEqual(secondEvents, [secondPayload]);
  assert.deepEqual(allEvents, [
    { name: 'fs.changed', payload: firstPayload },
    { name: 'fs.changed', payload: secondPayload },
    { name: 'fs.changed', payload: unrelated },
    { name: 'plugin.changed', payload: firstPayload }
  ]);

  await cancelSubscription(harness, cancelFirst, first.params.subscriptionId);
  harness.event('fs.changed', firstPayload);
  harness.event('fs.changed', secondPayload);
  assert.deepEqual(firstEvents, [firstPayload]);
  assert.deepEqual(secondEvents, [secondPayload, secondPayload]);
  assert.equal(allEvents.length, 6);
  await cancelSubscription(harness, cancelSecond, second.params.subscriptionId);
  off();
});

test('fs.watch installs its event listener before posting the host registration request', async () => {
  const received = [];
  const harness = loadSdk((request, host) => {
    if (request.method === 'fs.watch') host.event('fs.changed', changed(request, { contextKey: 'early-context' }));
  });
  const registration = harness.sdk.fs.watch('data://state.json', payload => received.push(plain(payload)));
  const request = harness.requests[0];
  const earlyPayload = changed(request, { contextKey: 'early-context' });
  assert.deepEqual(received, [earlyPayload]);
  harness.resolve(request, { subscriptionId: request.params.subscriptionId });
  const cancel = await registration;
  assert.deepEqual(received, [earlyPayload]);
  await cancelSubscription(harness, cancel, request.params.subscriptionId);
});

test('unwatch removes the listener immediately and repeated concurrent cancellation is idempotent', async () => {
  const harness = loadSdk();
  const listeners = trackListeners(harness.sdk);
  const received = [];
  const registration = harness.sdk.fs.watch('workspace://docs', payload => received.push(plain(payload)));
  const watchRequest = harness.requests[0];
  assert.equal(listeners.size, 1);
  harness.resolve(watchRequest, { subscriptionId: watchRequest.params.subscriptionId });
  const cancel = await registration;
  harness.event('fs.changed', changed(watchRequest));
  assert.equal(received.length, 1);

  const firstCancel = cancel();
  const secondCancel = cancel();
  assert.equal(typeof firstCancel.then, 'function');
  assert.equal(typeof secondCancel.then, 'function');
  assert.equal(listeners.size, 0);
  assert.equal(harness.requests.length, 2);
  const unwatchRequest = harness.requests[1];
  assert.equal(unwatchRequest.method, 'fs.unwatch');
  assert.deepEqual(unwatchRequest.params, { subscriptionId: watchRequest.params.subscriptionId });
  harness.event('fs.changed', changed(watchRequest));
  assert.equal(received.length, 1);
  harness.resolve(unwatchRequest, null);
  await Promise.all([firstCancel, secondCancel]);
  const thirdCancel = cancel();
  assert.equal(typeof thirdCancel.then, 'function');
  await thirdCancel;
  assert.equal(harness.requests.length, 2);
});

test('failed host registration removes only its listener and preserves other event listeners', async () => {
  const harness = loadSdk();
  const listeners = trackListeners(harness.sdk);
  const received = [];
  const lifecycle = [];
  const off = harness.sdk.events.on(event => lifecycle.push(plain(event)));
  const registration = harness.sdk.fs.watch('data://restricted.json', payload => received.push(plain(payload)));
  const request = harness.requests[0];
  assert.equal(listeners.size, 2);
  const rejected = assert.rejects(registration, { code: 'PATH_DENIED', message: 'path denied' });
  harness.reject(request, 'PATH_DENIED', 'path denied');
  await rejected;
  assert.equal(listeners.size, 1);

  const payload = changed(request);
  harness.event('fs.changed', payload);
  const shown = { state: 'shown', contextKey: 'current-context' };
  harness.event('ui.lifecycle', shown);
  assert.deepEqual(received, []);
  assert.deepEqual(lifecycle, [
    { name: 'fs.changed', payload },
    { name: 'ui.lifecycle', payload: shown }
  ]);
  assert.equal(harness.requests.length, 1);
  off();
  assert.equal(listeners.size, 0);
});

test('bridge exceptions during registration also release the watch listener', async () => {
  const harness = loadSdk(() => { throw new Error('bridge disconnected'); });
  const listeners = trackListeners(harness.sdk);
  const received = [];
  const registration = harness.sdk.fs.watch('workspace://generated', payload => received.push(plain(payload)));
  await assert.rejects(registration, { message: 'bridge disconnected' });
  assert.equal(listeners.size, 0);
  assert.equal(harness.requests.length, 1);
  harness.event('fs.changed', changed(harness.requests[0]));
  assert.deepEqual(received, []);
});

test('read, list, delete and stat forward contextKey while keeping the original single-path signature', async () => {
  const harness = loadSdk();
  for (const method of ['read', 'list', 'delete', 'stat']) {
    for (const options of [undefined, {}, { contextKey: 'remote-project-b' }]) {
      const pending = options === undefined
        ? harness.sdk.fs[method]('workspace://files/item.json')
        : harness.sdk.fs[method]('workspace://files/item.json', options);
      const request = harness.requests[harness.requests.length - 1];
      const expected = { path: 'workspace://files/item.json' };
      if (options && options.contextKey) expected.contextKey = options.contextKey;
      assert.equal(request.method, 'fs.' + method);
      assert.deepEqual(request.params, expected);
      const response = { method, content: 'unchanged result' };
      harness.resolve(request, response);
      assert.deepEqual(plain(await pending), response);
    }
  }
});

test('write forwards contextKey in its third argument and preserves path/content string conversion', async () => {
  const harness = loadSdk();
  const withContext = harness.sdk.fs.write('data://settings.json', '{"enabled":true}', { contextKey: 'local-project' });
  const first = harness.requests[0];
  assert.equal(first.method, 'fs.write');
  assert.deepEqual(first.params, {
    path: 'data://settings.json',
    content: '{"enabled":true}',
    contextKey: 'local-project'
  });
  harness.resolve(first, { written: true });
  assert.deepEqual(plain(await withContext), { written: true });

  const legacy = harness.sdk.fs.write(42, 17);
  const second = harness.requests[1];
  assert.equal(second.method, 'fs.write');
  assert.deepEqual(second.params, { path: '42', content: '17' });
  harness.resolve(second, null);
  assert.equal(await legacy, null);
});

test('overlay.resize preserves immediate calls and forwards optional animated movement', async () => {
  const harness = loadSdk();
  const immediate = harness.sdk.overlay.resize('48', '48');
  const first = harness.requests[0];
  assert.equal(first.method, 'overlay.resize');
  assert.deepEqual(first.params, { width: 48, height: 48 });
  harness.resolve(first, { width: 48, height: 48 });
  await immediate;

  const options = { animate: true, x: 24.5, y: 12 };
  const animated = harness.sdk.overlay.resize(320, 280, options);
  const second = harness.requests[1];
  assert.deepEqual(second.params, { width: 320, height: 280, animate: true, x: 24.5, y: 12 });
  assert.deepEqual(options, { animate: true, x: 24.5, y: 12 });
  harness.resolve(second, { width: 320, height: 280 });
  await animated;
});

test('ui.lifecycle continues reaching events.on before, during and after a file subscription', async () => {
  const harness = loadSdk();
  const lifecycle = [];
  const fileEvents = [];
  const off = harness.sdk.events.on(event => lifecycle.push(plain(event)));
  const hidden = { state: 'hidden', reason: 'background', contextKey: 'project-a' };
  harness.event('ui.lifecycle', hidden);
  const registration = harness.sdk.fs.watch('data://view-state.json', payload => fileEvents.push(plain(payload)));
  const request = harness.requests[0];
  const shown = { state: 'shown', reason: 'foreground', contextKey: 'project-a' };
  harness.event('ui.lifecycle', shown);
  harness.resolve(request, { subscriptionId: request.params.subscriptionId });
  const cancel = await registration;
  const changedContext = { state: 'contextChanged', reason: 'workspace', contextKey: 'project-b' };
  harness.event('ui.lifecycle', changedContext);
  await cancelSubscription(harness, cancel, request.params.subscriptionId);
  const closed = { state: 'closed', reason: 'pageClosed', contextKey: 'project-b' };
  harness.event('ui.lifecycle', closed);

  assert.deepEqual(lifecycle, [hidden, shown, changedContext, closed].map(payload => ({ name: 'ui.lifecycle', payload })));
  assert.deepEqual(fileEvents, []);
  off();
  harness.event('ui.lifecycle', shown);
  assert.equal(lifecycle.length, 4);
});

test('chat methods forward session and message parameters', async () => {
  const harness = loadSdk();
  const sdk = harness.sdk;

  const sessions = sdk.chat.listSessions({ includeSubagents: true, limit: 20 });
  const first = harness.requests[0];
  assert.equal(first.method, 'chat.listSessions');
  assert.deepEqual(first.params, { includeSubagents: true, limit: 20 });
  harness.resolve(first, { sessions: [] });
  assert.deepEqual(plain(await sessions), { sessions: [] });

  const created = sdk.chat.createSession({ title: 'Quick prompt', mode: 'plan' });
  const second = harness.requests[1];
  assert.equal(second.method, 'chat.createSession');
  assert.deepEqual(second.params, { title: 'Quick prompt', mode: 'plan' });
  harness.resolve(second, { session: { id: 'session-1' } });
  await created;

  const messages = sdk.chat.listMessages('session-1', { limit: 10, after: 100 });
  const third = harness.requests[2];
  assert.equal(third.method, 'chat.listMessages');
  assert.deepEqual(third.params, { sessionId: 'session-1', limit: 10, after: 100 });
  harness.resolve(third, { sessionId: 'session-1', messages: [], hasMore: false });
  await messages;

  const sent = sdk.chat.sendMessage('session-1', 42);
  const fourth = harness.requests[3];
  assert.equal(fourth.method, 'chat.sendMessage');
  assert.deepEqual(fourth.params, { sessionId: 'session-1', text: '42' });
  harness.resolve(fourth, { sessionId: 'session-1', accepted: true });
  await sent;

  const stopped = sdk.chat.stop('session-1');
  const fifth = harness.requests[4];
  assert.equal(fifth.method, 'chat.stop');
  assert.deepEqual(fifth.params, { sessionId: 'session-1' });
  harness.resolve(fifth, { sessionId: 'session-1', accepted: true });
  await stopped;

  const opened = sdk.chat.openSession('session-1');
  const sixth = harness.requests[5];
  assert.equal(sixth.method, 'chat.openSession');
  assert.deepEqual(sixth.params, { sessionId: 'session-1' });
  harness.resolve(sixth, { sessionId: 'session-1', accepted: true });
  await opened;

  assert.deepEqual(harness.errors, []);
});

test('extended host methods forward parameters', async () => {
  const harness = loadSdk();
  const cases = [
    [() => harness.sdk.fs.mkdir('data://dir'), 'fs.mkdir', { path: 'data://dir' }],
    [() => harness.sdk.fs.rename('data://a', 'data://b'), 'fs.rename', { from: 'data://a', to: 'data://b' }],
    [() => harness.sdk.fs.copy('data://a', 'data://b', { overwrite: true }), 'fs.copy', {
      from: 'data://a', to: 'data://b', overwrite: true
    }],
    [() => harness.sdk.fs.append('data://a', 'x'), 'fs.append', { path: 'data://a', content: 'x' }],
    [() => harness.sdk.fs.readBinary('data://a'), 'fs.readBinary', { path: 'data://a' }],
    [() => harness.sdk.fs.writeBinary('data://a', 'AA=='), 'fs.writeBinary', {
      path: 'data://a', base64: 'AA=='
    }],
    [() => harness.sdk.terminal.write('t1', 'ls', { appendNewline: false }), 'terminal.write', {
      tabId: 't1', text: 'ls', appendNewline: false
    }],
    [() => harness.sdk.terminal.list(), 'terminal.list', {}],
    [() => harness.sdk.chat.getRunState('s1'), 'chat.getRunState', { sessionId: 's1' }],
    [() => harness.sdk.chat.waitForRun('s1', { timeoutMs: 5000 }), 'chat.waitForRun', {
      sessionId: 's1', timeoutMs: 5000
    }],
    [() => harness.sdk.chat.renameSession('s1', '标题'), 'chat.renameSession', {
      sessionId: 's1', title: '标题'
    }],
    [() => harness.sdk.chat.pinSession('s1', true), 'chat.pinSession', {
      sessionId: 's1', pinned: true
    }],
    [() => harness.sdk.chat.deleteSession('s1'), 'chat.deleteSession', { sessionId: 's1' }],
    [() => harness.sdk.chat.listApprovals('s1'), 'chat.listApprovals', { sessionId: 's1' }],
    [() => harness.sdk.chat.resolveToolApproval('s1', 'r1', 'once'), 'chat.resolveToolApproval', {
      sessionId: 's1', requestId: 'r1', choice: 'once'
    }],
    [() => harness.sdk.chat.resolveQuestion('s1', 'r1', [{ question: 'q', selected: ['a'] }]),
      'chat.resolveQuestion', {
        sessionId: 's1', requestId: 'r1', answers: [{ question: 'q', selected: ['a'] }]
      }],
    [() => harness.sdk.chat.resolvePlanApproval('s1', 'r1', 'approve'), 'chat.resolvePlanApproval', {
      sessionId: 's1', requestId: 'r1', choice: 'approve'
    }],
    [() => harness.sdk.providers.listModels('gateway'), 'providers.listModels', { providerId: 'gateway' }],
    [() => harness.sdk.providers.addModel('gateway', 'model-a', { contextTokens: 128000 }),
      'providers.addModel', {
        providerId: 'gateway', model: 'model-a', contextTokens: 128000
      }],
    [() => harness.sdk.providers.deleteModel('gateway', 'model-a'), 'providers.deleteModel', {
      providerId: 'gateway', model: 'model-a'
    }],
    [() => harness.sdk.providers.setModelContext('gateway', 'model-a', 64000),
      'providers.setModelContext', {
        providerId: 'gateway', model: 'model-a', contextTokens: 64000
      }],
    [() => harness.sdk.providers.setApiKey('gateway', 'local-token'), 'providers.setApiKey', {
      providerId: 'gateway', apiKey: 'local-token'
    }],
    [() => harness.sdk.clipboard.read(), 'clipboard.read', {}],
    [() => harness.sdk.clipboard.write('x'), 'clipboard.write', { text: 'x' }],
    [() => harness.sdk.share.text('x', { title: 't' }), 'share.text', { text: 'x', title: 't' }],
    [() => harness.sdk.ui.openUrl('https://example.com'), 'ui.openUrl', { url: 'https://example.com' }],
    [() => harness.sdk.app.info(), 'app.info', {}],
    [() => harness.sdk.secrets.get('k'), 'secrets.get', { key: 'k' }],
    [() => harness.sdk.secrets.set('k', 'v'), 'secrets.set', { key: 'k', value: 'v' }],
    [() => harness.sdk.secrets.delete('k'), 'secrets.delete', { key: 'k' }],
    [() => harness.sdk.secrets.list(), 'secrets.list', {}],
    [() => harness.sdk.tools.list(), 'tools.list', {}],
    [() => harness.sdk.mcp.status(), 'mcp.status', {}],
    [() => harness.sdk.http.request({ url: 'https://example.com', method: 'POST' }), 'http.request', {
      url: 'https://example.com', method: 'POST'
    }],
    [() => harness.sdk.log.write('info', 'hi', { tag: 't' }), 'log.write', {
      level: 'info', message: 'hi', tag: 't'
    }]
  ];

  for (const [invoke, method, params] of cases) {
    const promise = invoke();
    const request = harness.requests[harness.requests.length - 1];
    assert.equal(request.method, method);
    assert.deepEqual(request.params, params);
    harness.resolve(request, {});
    await promise;
  }

  assert.deepEqual(harness.errors, []);
});

test('fs.watch preserves change batches and subscription failures', async () => {
  const harness = loadSdk();
  const received = [];
  const registration = harness.sdk.fs.watch('data://records', event => received.push(plain(event)));
  const request = harness.requests[0];
  harness.resolve(request, { subscriptionId: request.params.subscriptionId });
  const cancel = await registration;
  const batch = changed(request, { truncated: true });
  const failure = {
    subscriptionId: request.params.subscriptionId,
    path: request.params.path,
    error: { code: 'WATCH_FAILED', message: 'watch failed' }
  };
  harness.event('fs.changed', batch);
  harness.event('fs.changed', failure);
  assert.deepEqual(received, [batch, failure]);
  await cancelSubscription(harness, cancel, request.params.subscriptionId);
});

test('chat.deleteSession preserves the queued request acknowledgement', async () => {
  const harness = loadSdk();
  const promise = harness.sdk.chat.deleteSession('session-1');
  const request = harness.requests[0];
  assert.equal(request.method, 'chat.deleteSession');
  assert.deepEqual(request.params, { sessionId: 'session-1' });
  harness.resolve(request, { sessionId: 'session-1', accepted: true });
  assert.deepEqual(plain(await promise), { sessionId: 'session-1', accepted: true });
});

test('model.listModels preserves nullable token metadata', async () => {
  const harness = loadSdk();
  const promise = harness.sdk.model.listModels('provider-1');
  const request = harness.requests[0];
  assert.equal(request.method, 'model.listModels');
  const result = {
    providerId: 'provider-1',
    models: [{ id: 'model-1', contextTokens: 200000, inputTokens: null, outputTokens: null }]
  };
  harness.resolve(request, result);
  assert.deepEqual(plain(await promise), result);
});

test('context and overlay methods preserve their page-specific result shapes', async () => {
  const harness = loadSdk();
  const contextPromise = harness.sdk.context();
  const context = { pluginId: 'demo.pet', overlayId: 'demo.pet:ball', pageId: 'ball', version: '1.0.0', density: 2 };
  harness.resolve(harness.requests[0], context);
  assert.deepEqual(plain(await contextPromise), context);

  const created = harness.sdk.overlay.create({ surface: 'ball' });
  const info = {
    overlayId: 'demo.pet:ball', pluginId: 'demo.pet', width: 48, height: 48,
    x: 100, y: 200, draggable: true, snap: 'x', visibilityPolicy: 'always'
  };
  harness.resolve(harness.requests[1], info);
  assert.deepEqual(plain(await created), info);

  const self = harness.sdk.overlay.self();
  const selfInfo = { ...info, x: 50, y: 100, density: 2, foreground: true };
  harness.resolve(harness.requests[2], selfInfo);
  assert.deepEqual(plain(await self), selfInfo);

  const closed = harness.sdk.overlay.close();
  assert.deepEqual(harness.requests[3].params, {});
  harness.resolve(harness.requests[3], null);
  assert.equal(await closed, null);
});

test('ui.onBack asynchronously returns its unregister function', async () => {
  const harness = loadSdk();
  let calls = 0;
  const registration = harness.sdk.ui.onBack(() => { calls++; return true; });
  assert.equal(typeof registration.then, 'function');
  const off = await registration;
  assert.equal(harness.window.__aicodeBack(), true);
  assert.equal(calls, 1);
  off();
  assert.equal(harness.window.__aicodeBack(), false);
  assert.equal(calls, 1);
});

for (const status of ['success', 'error', 'cancelled']) {
  test('model.stream consumes every buffered batch before the ' + status + ' result', async () => {
    let index = 0;
    const batches = [
      { events: [{ type: 'text', text: 'first' }], cursor: 1, done: false },
      { events: [{ type: 'text', text: 'second' }], cursor: 2, done: false },
      { events: [{ type: 'text', text: 'last' }], cursor: 3, done: true, status }
    ];
    if (status === 'success') batches[2].final = { content: 'firstsecondlast' };
    if (status === 'error') batches[2].error = { code: 'UPSTREAM_ERROR', message: 'failed' };
    const harness = loadSdk((request, env) => {
      if (request.method === 'model.start') env.resolve(request, { streamId: 'stream-1' });
      else {
        assert.equal(request.method, 'model.read');
        assert.equal(request.params.cursor, index);
        env.resolve(request, { streamId: 'stream-1', ...batches[index++] });
      }
    });
    const received = [];
    const result = await harness.sdk.model.stream({ handleId: 'handle-1', messages: [] }, event => {
      received.push(event.text);
    });
    assert.equal(index, 3);
    assert.deepEqual(received, ['first', 'second', 'last']);
    assert.equal(result.done, true);
    assert.equal(result.status, status);
    assert.deepEqual(harness.errors, []);
  });
}
