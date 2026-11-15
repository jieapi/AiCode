'use strict';

const assert = require('node:assert/strict');
const path = require('node:path');
const { spawn } = require('node:child_process');

const runtime = path.join(__dirname, '..', 'plugin-official', 'remote-ports', 'dist', 'main.js');
const child = spawn(process.execPath, [runtime], { stdio: ['pipe', 'pipe', 'pipe'] });
const messages = [];
const hostRequests = [];
let buffer = '';
let broken = '';

child.stdout.on('data', (chunk) => {
  buffer += chunk.toString('utf8');
  let index;
  while ((index = buffer.indexOf('\n')) >= 0) {
    const line = buffer.slice(0, index).trim();
    buffer = buffer.slice(index + 1);
    if (!line) continue;
    let message;
    try {
      message = JSON.parse(line);
    } catch (error) {
      broken = `invalid JSON: ${line}`;
      continue;
    }
    messages.push(message);
    if (message.method && message.id !== undefined) hostRequests.push(message);
  }
});

child.stderr.on('data', (chunk) => {
  broken = broken || `plugin stderr: ${chunk.toString('utf8').trim()}`;
});

function send(message) {
  child.stdin.write(JSON.stringify(message) + '\n');
}

function waitFor(predicate, label, timeoutMs = 3000) {
  return new Promise((resolve, reject) => {
    const started = Date.now();
    const timer = setInterval(() => {
      if (broken) {
        clearInterval(timer);
        reject(new Error(broken));
        return;
      }
      const found = messages.find(predicate) || hostRequests.find(predicate);
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

const mapping = {
  id: 'mapping-1',
  localPort: 5173,
  remoteHost: '127.0.0.1',
  remotePort: 5173,
  url: 'http://127.0.0.1:5173',
  direct: false
};

async function main() {
  send({
    jsonrpc: '2.0',
    id: 1,
    method: 'tool.invoke',
    params: { handler: 'tools.expose', args: { port: 5173 } }
  });

  const forward = await waitFor(
    (message) => message.method === 'host.ports.forward',
    'host.ports.forward request'
  );
  assert.equal(forward.params.port, 5173);
  assert.equal(forward.params.host, '127.0.0.1');
  assert.equal(forward.params.localPort, undefined);

  send({ jsonrpc: '2.0', id: forward.id, result: mapping });

  const changed = await waitFor(
    (message) => message.method === 'plugin.event' &&
      message.params && message.params.name === 'ports.changed',
    'ports.changed event'
  );
  assert.equal(changed.params.payload.id, 'mapping-1');

  const exposed = await waitFor((message) => message.id === 1, 'expose tool result');
  assert.equal(exposed.result.url, 'http://127.0.0.1:5173');
  assert.equal(exposed.result.localPort, 5173);
  assert.match(exposed.result.hint, /127\.0\.0\.1:5173/);

  send({ jsonrpc: '2.0', id: 2, method: 'ui.invoke', params: { handler: 'ui.list', args: {} } });
  const list = await waitFor((message) => message.method === 'host.ports.list', 'host.ports.list request');
  send({ jsonrpc: '2.0', id: list.id, result: [mapping] });
  const listed = await waitFor((message) => message.id === 2, 'list result');
  assert.equal(listed.result.mappings.length, 1);
  assert.equal(listed.result.mappings[0].localPort, 5173);

  send({
    jsonrpc: '2.0',
    id: 3,
    method: 'ui.invoke',
    params: { handler: 'ui.close', args: { id: 'mapping-1' } }
  });
  const close = await waitFor((message) => message.method === 'host.ports.close', 'host.ports.close request');
  assert.equal(close.params.id, 'mapping-1');
  send({ jsonrpc: '2.0', id: close.id, result: { closed: true } });
  const closed = await waitFor((message) => message.id === 3, 'close result');
  assert.equal(closed.result.ok, true);

  send({
    jsonrpc: '2.0',
    id: 4,
    method: 'tool.invoke',
    params: { handler: 'tools.expose', args: { port: 70000 } }
  });
  const rejected = await waitFor((message) => message.id === 4, 'invalid port response');
  assert.equal(rejected.error.code, 'PLUGIN_ERROR');
  assert.match(rejected.error.message, /1-65535/);
  assert.equal(hostRequests.filter((message) => message.method === 'host.ports.forward').length, 1);

  console.log('remote-ports runtime ok');
  child.kill();
}

main().catch((error) => {
  console.error(error);
  child.kill();
  process.exitCode = 1;
});
