'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');
const esbuild = require('../plugins/node_modules/esbuild');

const source = esbuild.transformSync(fs.readFileSync(path.join(__dirname, '../plugins/sdk/src/index.ts'), 'utf8'), {
  loader: 'ts', format: 'cjs', target: 'node18'
}).code;
const sandbox = { exports: {}, module: { exports: {} }, require, process, AbortController, setTimeout, clearTimeout };
vm.runInNewContext(source, sandbox);
const { toolDelegate } = sandbox.module.exports;
const identity = { executionId: 'execution', runId: 'run', callId: 'call', toolName: 'readFile' };
const request = (operation, extra = {}) => ({ ...identity, operation, ...extra });

test('delegate is lazy, streams and returns final data', async () => {
  let started = false;
  const handler = toolDelegate(async function* (context) {
    started = true;
    assert.equal(context.arguments.path, 'final');
    yield 'reading';
    return { content: 'done' };
  });
  assert.equal(started, false);
  const progress = await handler(request('start', { arguments: { path: 'final' } }));
  assert.equal(progress.type, 'progress');
  assert.equal(progress.chunk, 'reading');
  const done = await handler(request('next'));
  assert.equal(done.type, 'completed');
  assert.equal(done.data.content, 'done');
  await assert.rejects(handler(request('next')), /Unknown/);
  await assert.rejects(handler(request('start', { arguments: {} })), /Duplicate/);
});

test('delegate cancellation aborts in-flight execution and rejects foreign identity', async () => {
  let signal;
  const handler = toolDelegate(async function* (context) {
    signal = context.signal;
    yield 'ready';
    await new Promise((resolve) => context.signal.addEventListener('abort', resolve, { once: true }));
    return null;
  });
  await handler(request('start', { arguments: {} }));
  await assert.rejects(handler(request('next', { callId: 'foreign' })), /Unknown/);
  const waiting = handler(request('next'));
  await handler(request('cancel'));
  assert.equal(signal.aborted, true);
  assert.equal((await waiting).type, 'error');
  await assert.rejects(handler(request('next')), /Unknown/);
  await assert.rejects(handler(request('start', { arguments: {} })), /Duplicate/);
});

test('delegate errors are terminal and progress size is bounded', async () => {
  const failed = toolDelegate(async function* () { throw new Error('failed'); });
  assert.equal((await failed(request('start', { arguments: {} }))).type, 'error');
  await assert.rejects(failed(request('next')), /Unknown/);
  await assert.rejects(failed(request('start', { arguments: {} })), /Duplicate/);
  const oversized = toolDelegate(async function* () { yield 'x'.repeat(65537); return null; });
  assert.equal((await oversized(request('start', { arguments: {} }))).type, 'error');
});
