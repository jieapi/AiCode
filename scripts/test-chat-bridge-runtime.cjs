'use strict';

const assert = require('node:assert/strict');
const path = require('node:path');
const { spawn } = require('node:child_process');

const runtime = path.join(__dirname, '..', 'plugin-samples', 'chat-bridge', 'dist', 'main.js');
const child = spawn(process.execPath, [runtime], { stdio: ['pipe', 'pipe', 'inherit'] });
const messages = [];
let missing = '';
let buffer = '';

child.stdout.on('data', (chunk) => {
  buffer += chunk.toString('utf8');
  let index;
  while ((index = buffer.indexOf('\n')) >= 0) {
    const line = buffer.slice(0, index).trim();
    buffer = buffer.slice(index + 1);
    if (!line) continue;
    try {
      messages.push(JSON.parse(line));
    } catch (error) {
      missing = `invalid JSON: ${line}`;
    }
  }
});

function send(message) {
  child.stdin.write(JSON.stringify(message) + '\n');
}

function waitFor(predicate, label, timeoutMs = 2000) {
  return new Promise((resolve, reject) => {
    const started = Date.now();
    const timer = setInterval(() => {
      if (missing) {
        clearInterval(timer);
        reject(new Error(missing));
        return;
      }
      const found = messages.find(predicate);
      if (found) {
        clearInterval(timer);
        resolve(found);
        return;
      }
      if (Date.now() - started > timeoutMs) {
        clearInterval(timer);
        reject(new Error(`timeout waiting for ${label}; got ${JSON.stringify(messages)}`));
      }
    }, 20);
  });
}

async function main() {
  send({
    jsonrpc: '2.0',
    id: 1,
    method: 'hook.invoke',
    params: {
      handler: 'hooks.reviewPermission',
      args: {
        runId: 'run-1',
        sessionId: 'session-1',
        callId: 'call-1',
        toolName: 'Bash',
        arguments: { command: 'ls' },
        appForeground: true
      }
    }
  });

  const requested = await waitFor(
    (message) => message.method === 'plugin.event' &&
      message.params && message.params.name === 'chat.permission.requested',
    'permission request event'
  );
  assert.equal(requested.params.payload.requestId, 'call-1');
  assert.equal(requested.params.payload.toolName, 'Bash');

  send({
    jsonrpc: '2.0',
    id: 2,
    method: 'ui.invoke',
    params: { handler: 'ui.pageReady', args: {} }
  });
  const ready = await waitFor((message) => message.id === 2, 'page ready response');
  assert.equal(ready.result.pendingReviews.length, 1);
  assert.equal(ready.result.pendingReviews[0].requestId, 'call-1');

  send({
    jsonrpc: '2.0',
    id: 3,
    method: 'ui.invoke',
    params: {
      handler: 'ui.permissionDecision',
      args: { requestId: 'call-1', decision: 'allow' }
    }
  });
  const decided = await waitFor((message) => message.id === 3, 'permission decision response');
  assert.equal(decided.result.ok, true);
  assert.equal(decided.result.decision, 'allow');

  const hookResult = await waitFor((message) => message.id === 1, 'review hook result');
  assert.equal(hookResult.result.decision, 'allow');

  const resolved = await waitFor(
    (message) => message.method === 'plugin.event' &&
      message.params && message.params.name === 'chat.permission.resolved',
    'permission resolved event'
  );
  assert.equal(resolved.params.payload.requestId, 'call-1');

  send({
    jsonrpc: '2.0',
    id: 4,
    method: 'hook.invoke',
    params: {
      handler: 'hooks.runFinished',
      args: { runId: 'run-1', sessionId: 'session-1', status: 'success', toolCalls: 2 }
    }
  });
  const finishedEvent = await waitFor(
    (message) => message.method === 'plugin.event' &&
      message.params && message.params.name === 'chat.run.finished',
    'run finished event'
  );
  assert.equal(finishedEvent.params.payload.sessionId, 'session-1');
  assert.equal(finishedEvent.params.payload.status, 'success');

  console.log('chat-bridge runtime review flow ok');
  child.kill();
}

main().catch((error) => {
  console.error(error);
  child.kill();
  process.exitCode = 1;
});
