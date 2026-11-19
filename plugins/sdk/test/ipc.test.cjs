'use strict';

const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const crypto = require('node:crypto');
const fs = require('node:fs');
const net = require('node:net');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');

const sdk = require('../dist/index.js');
const {
  CoalescedEventSender,
  DEFAULT_CHUNK_BYTES,
  FrameDecoder,
  FrameType,
  IPC_HEADER_BYTES,
  IPC_MAGIC,
  IPC_VERSION,
  IpcError,
  MAX_META_BYTES,
  MAX_PAYLOAD_BYTES,
  PluginIpcServer,
  binaryResult,
  encodeFrame
} = sdk;

function makeTempDir(prefix) {
  return fs.mkdtempSync(path.join(os.tmpdir(), prefix));
}

function removeDir(dir) {
  fs.rmSync(dir, { recursive: true, force: true });
}

function connectUdsClient(socketPath) {
  return new Promise((resolve, reject) => {
    const decoder = new FrameDecoder();
    const queue = [];
    const waiters = [];
    let closed = false;

    const socket = net.connect({ path: socketPath }, () => {
      resolve(client);
    });

    socket.once('error', (err) => {
      if (!closed) {
        for (const w of waiters.splice(0)) w.reject(err);
      }
      reject(err);
    });

    socket.on('data', (chunk) => {
      const frames = decoder.push(chunk);
      for (const frame of frames) {
        if (waiters.length > 0) {
          waiters.shift().resolve(frame);
        } else {
          queue.push(frame);
        }
      }
    });

    socket.on('close', () => {
      closed = true;
      for (const w of waiters.splice(0)) {
        w.reject(new Error('Client socket closed'));
      }
    });

    const client = {
      socket,
      send(frame) {
        socket.write(encodeFrame(frame));
      },
      nextFrame(timeoutMs = 5000) {
        if (queue.length > 0) return Promise.resolve(queue.shift());
        if (closed) return Promise.reject(new Error('Client socket closed'));
        return new Promise((res, rej) => {
          const timer = setTimeout(() => {
            const idx = waiters.indexOf(entry);
            if (idx >= 0) waiters.splice(idx, 1);
            rej(new Error('Timed out waiting for IPC frame'));
          }, timeoutMs);
          const entry = {
            resolve: (f) => {
              clearTimeout(timer);
              res(f);
            },
            reject: (e) => {
              clearTimeout(timer);
              rej(e);
            }
          };
          waiters.push(entry);
        });
      },
      waitForClose(timeoutMs = 3000) {
        if (closed) return Promise.resolve();
        return new Promise((res, rej) => {
          const timer = setTimeout(() => rej(new Error('Timed out waiting for socket close')), timeoutMs);
          socket.once('close', () => {
            clearTimeout(timer);
            res();
          });
        });
      },
      close() {
        socket.destroy();
      }
    };
  });
}

test('encodeFrame and FrameDecoder round-trip header, meta, and payload across all frame types', () => {
  const types = [
    FrameType.REQ,
    FrameType.RESP,
    FrameType.ERR,
    FrameType.STREAM_DATA,
    FrameType.STREAM_END,
    FrameType.EVENT,
    FrameType.PING,
    FrameType.PONG
  ];

  for (const type of types) {
    const payload = Buffer.from(`payload-for-type-${type}`);
    const meta = { method: 'fs.read', type, nested: { ok: true } };
    const encoded = encodeFrame({ type, streamId: 0x80000000 + type, meta, payload });

    assert.equal(encoded.readUInt16BE(0), IPC_MAGIC);
    assert.equal(encoded.readUInt8(2), IPC_VERSION);
    assert.equal(encoded.readUInt8(3), type);
    assert.equal(encoded.readUInt32BE(4), (0x80000000 + type) >>> 0);
    const metaLen = encoded.readUInt32BE(8);
    const payloadLen = encoded.readUInt32BE(12);
    assert.equal(encoded.length, IPC_HEADER_BYTES + metaLen + payloadLen);

    const decoder = new FrameDecoder();
    const decoded = decoder.push(encoded);
    assert.equal(decoded.length, 1);
    assert.equal(decoded[0].type, type);
    assert.equal(decoded[0].streamId, 0x80000000 + type);
    assert.deepEqual(decoded[0].meta, meta);
    assert.deepEqual(decoded[0].payload, payload);
  }

  const bare = encodeFrame({ type: FrameType.PING, streamId: 0 });
  assert.equal(bare.length, IPC_HEADER_BYTES);
  const bareDecoded = new FrameDecoder().push(bare);
  assert.equal(bareDecoded.length, 1);
  assert.equal(bareDecoded[0].meta, undefined);
  assert.equal(bareDecoded[0].payload.length, 0);
});

test('FrameDecoder handles sticky packets and arbitrary split chunks', () => {
  const frames = [
    { type: FrameType.PING, streamId: 1, meta: { token: 'abc' } },
    { type: FrameType.REQ, streamId: 3, meta: { method: 'fs.write', params: { path: '/a' } }, payload: Buffer.from('hello world') },
    { type: FrameType.STREAM_DATA, streamId: 5, payload: crypto.randomBytes(1024) },
    { type: FrameType.STREAM_END, streamId: 5, meta: { ok: true } }
  ];

  const combined = Buffer.concat(frames.map((f) => encodeFrame(f)));

  // Sticky: all frames in one chunk
  const stickyDecoder = new FrameDecoder();
  const stickyOut = stickyDecoder.push(combined);
  assert.equal(stickyOut.length, frames.length);
  for (let i = 0; i < frames.length; i++) {
    assert.equal(stickyOut[i].type, frames[i].type);
    assert.equal(stickyOut[i].streamId, frames[i].streamId);
    assert.deepEqual(stickyOut[i].meta, frames[i].meta);
    assert.deepEqual(stickyOut[i].payload, frames[i].payload ?? Buffer.alloc(0));
  }

  // Split: 1-byte and 7-byte irregular slices across boundaries
  const splitDecoder = new FrameDecoder();
  const splitOut = [];
  let offset = 0;
  let step = 1;
  while (offset < combined.length) {
    const end = Math.min(offset + step, combined.length);
    splitOut.push(...splitDecoder.push(combined.subarray(offset, end)));
    offset = end;
    step = step === 1 ? 7 : step === 7 ? 3 : 1;
  }
  assert.equal(splitOut.length, frames.length);
  for (let i = 0; i < frames.length; i++) {
    assert.equal(splitOut[i].type, frames[i].type);
    assert.equal(splitOut[i].streamId, frames[i].streamId);
    assert.deepEqual(splitOut[i].meta, frames[i].meta);
    assert.deepEqual(splitOut[i].payload, frames[i].payload ?? Buffer.alloc(0));
  }
});

test('FrameDecoder and encodeFrame reject invalid magic, version, and oversized lengths', () => {
  const badMagic = encodeFrame({ type: FrameType.PING, streamId: 1 });
  badMagic.writeUInt16BE(0x1234, 0);
  assert.throws(() => new FrameDecoder().push(badMagic), (err) => err instanceof IpcError && err.code === 'INVALID_MAGIC');

  const badVersion = encodeFrame({ type: FrameType.PING, streamId: 1 });
  badVersion.writeUInt8(9, 2);
  assert.throws(() => new FrameDecoder().push(badVersion), (err) => err instanceof IpcError && err.code === 'UNSUPPORTED_VERSION');

  const badType = encodeFrame({ type: FrameType.PING, streamId: 1 });
  badType.writeUInt8(99, 3);
  assert.throws(() => new FrameDecoder().push(badType), (err) => err instanceof IpcError && err.code === 'INVALID_FRAME_TYPE');

  const overMetaHeader = Buffer.alloc(IPC_HEADER_BYTES);
  overMetaHeader.writeUInt16BE(IPC_MAGIC, 0);
  overMetaHeader.writeUInt8(IPC_VERSION, 2);
  overMetaHeader.writeUInt8(FrameType.REQ, 3);
  overMetaHeader.writeUInt32BE(1, 4);
  overMetaHeader.writeUInt32BE(MAX_META_BYTES + 1, 8);
  overMetaHeader.writeUInt32BE(0, 12);
  assert.throws(() => new FrameDecoder().push(overMetaHeader), (err) => err instanceof IpcError && err.code === 'META_TOO_LARGE');

  const overPayloadHeader = Buffer.alloc(IPC_HEADER_BYTES);
  overPayloadHeader.writeUInt16BE(IPC_MAGIC, 0);
  overPayloadHeader.writeUInt8(IPC_VERSION, 2);
  overPayloadHeader.writeUInt8(FrameType.REQ, 3);
  overPayloadHeader.writeUInt32BE(1, 4);
  overPayloadHeader.writeUInt32BE(0, 8);
  overPayloadHeader.writeUInt32BE(MAX_PAYLOAD_BYTES + 1, 12);
  assert.throws(() => new FrameDecoder().push(overPayloadHeader), (err) => err instanceof IpcError && err.code === 'PAYLOAD_TOO_LARGE');

  assert.throws(
    () => encodeFrame({ type: FrameType.STREAM_DATA, streamId: 1, payload: Buffer.alloc(MAX_PAYLOAD_BYTES + 1) }),
    (err) => err instanceof IpcError && err.code === 'PAYLOAD_TOO_LARGE'
  );
  assert.throws(
    () => encodeFrame({ type: FrameType.REQ, streamId: 1, meta: { big: 'x'.repeat(MAX_META_BYTES + 1) } }),
    (err) => err instanceof IpcError && err.code === 'META_TOO_LARGE'
  );
});

test('PluginIpcServer enforces token auth, cleans stale sock, sets 0600 mode, and handles bidirectional RPC/events', async () => {
  const tmpDir = makeTempDir('aicode-ipc-auth-');
  const sockPath = path.join(tmpDir, 'nested', 'plugin.sock');
  fs.mkdirSync(path.dirname(sockPath), { recursive: true });
  fs.writeFileSync(sockPath, 'stale');

  const receivedEvents = [];
  let readyCount = 0;

  const server = new PluginIpcServer({
    socketPath: sockPath,
    token: 'secret-token-123',
    onReady: () => {
      readyCount++;
    },
    onEvent: (name, payload, rawPayload) => {
      receivedEvents.push({ name, payload, rawPayload });
    },
    onRequest: async (method, params, ctx) => {
      if (method === 'echo.json') return { ok: true, params };
      if (method === 'echo.bin') {
        return binaryResult(Buffer.concat([Buffer.from('rx:'), ctx.payload]), { len: ctx.payload.length });
      }
      if (method === 'fail.boom') {
        const err = new Error('boom');
        err.code = 'CUSTOM_FAIL';
        throw err;
      }
      throw new Error('unknown');
    }
  });

  try {
    await server.start();
    const stat = fs.statSync(sockPath);
    assert.equal(stat.mode & 0o777, 0o600);

    // 1) Non-PING frame before auth is rejected
    const unauthReq = await connectUdsClient(sockPath);
    unauthReq.send({ type: FrameType.REQ, streamId: 1, meta: { method: 'echo.json', params: {} } });
    await unauthReq.waitForClose();
    assert.equal(server.isReady, false);

    // 2) Wrong token PING is rejected
    const badToken = await connectUdsClient(sockPath);
    badToken.send({ type: FrameType.PING, streamId: 1, meta: { token: 'wrong-token' } });
    await badToken.waitForClose();
    assert.equal(server.isReady, false);
    assert.equal(readyCount, 0);

    // 3) Valid token PING succeeds
    const client = await connectUdsClient(sockPath);
    client.send({ type: FrameType.PING, streamId: 7, meta: { token: 'secret-token-123' } });
    const pong = await client.nextFrame();
    assert.equal(pong.type, FrameType.PONG);
    assert.equal(pong.streamId, 7);
    assert.equal(server.isReady, true);
    assert.equal(readyCount, 1);

    // Host -> Plugin JSON REQ
    client.send({ type: FrameType.REQ, streamId: 1, meta: { method: 'echo.json', params: { x: 42 } } });
    const jsonResp = await client.nextFrame();
    assert.equal(jsonResp.type, FrameType.RESP);
    assert.equal(jsonResp.streamId, 1);
    assert.deepEqual(jsonResp.meta, { result: { ok: true, params: { x: 42 } } });

    // Host -> Plugin Binary REQ & RESP
    client.send({ type: FrameType.REQ, streamId: 3, meta: { method: 'echo.bin', params: {} }, payload: Buffer.from('abc') });
    const binResp = await client.nextFrame();
    assert.equal(binResp.type, FrameType.RESP);
    assert.equal(binResp.streamId, 3);
    assert.deepEqual(binResp.meta, { len: 3 });
    assert.equal(binResp.payload.toString('utf8'), 'rx:abc');

    // Host -> Plugin ERR
    client.send({ type: FrameType.REQ, streamId: 5, meta: { method: 'fail.boom', params: {} } });
    const errResp = await client.nextFrame();
    assert.equal(errResp.type, FrameType.ERR);
    assert.equal(errResp.meta.code, 'CUSTOM_FAIL');
    assert.equal(errResp.meta.message, 'boom');

    // Plugin -> Host sendRequest (JSON + ERR + timeout)
    const hostCallPromise = server.sendRequest('host.app.info', { verbose: true });
    const hostReqFrame = await client.nextFrame();
    assert.equal(hostReqFrame.type, FrameType.REQ);
    assert.equal(hostReqFrame.meta.method, 'host.app.info');
    assert.deepEqual(hostReqFrame.meta.params, { verbose: true });
    client.send({ type: FrameType.RESP, streamId: hostReqFrame.streamId, meta: { result: { versionName: '1.0.0' } } });
    assert.deepEqual(await hostCallPromise, { versionName: '1.0.0' });

    const hostErrPromise = server.sendRequest('host.fail', {});
    const hostErrReq = await client.nextFrame();
    client.send({ type: FrameType.ERR, streamId: hostErrReq.streamId, meta: { code: 'DENIED', message: 'nope' } });
    await assert.rejects(hostErrPromise, (e) => e instanceof IpcError && e.code === 'DENIED' && e.message === 'nope');

    const timeoutPromise = server.sendRequest('host.slow', {}, undefined, 40);
    await client.nextFrame(); // consume REQ
    await assert.rejects(timeoutPromise, (e) => e instanceof IpcError && e.code === 'IPC_TIMEOUT');

    // Bidirectional EVENT
    assert.equal(server.sendEvent('plugin.tick', { count: 1 }, Buffer.from('raw')), true);
    const evtFrame = await client.nextFrame();
    assert.equal(evtFrame.type, FrameType.EVENT);
    assert.deepEqual(evtFrame.meta, { name: 'plugin.tick', payload: { count: 1 } });
    assert.equal(evtFrame.payload.toString('utf8'), 'raw');

    client.send({ type: FrameType.EVENT, streamId: 0, meta: { name: 'host.evt', payload: { ok: 1 } }, payload: Buffer.from('ev') });
    await new Promise((r) => setTimeout(r, 20));
    assert.equal(receivedEvents.length, 1);
    assert.equal(receivedEvents[0].name, 'host.evt');
    assert.deepEqual(receivedEvents[0].payload, { ok: 1 });
    assert.equal(receivedEvents[0].rawPayload.toString('utf8'), 'ev');

    client.close();
  } finally {
    await server.close();
    removeDir(tmpDir);
  }
});

test('PluginIpcServer streams >4 MiB binary and AsyncIterable payloads in 64 KiB chunks without head-of-line blocking, and supports STREAM_END cancellation', async () => {
  const tmpDir = makeTempDir('aicode-ipc-stream-');
  const sockPath = path.join(tmpDir, 'stream.sock');
  const largeBuffer = crypto.randomBytes(5 * 1024 * 1024 + 123); // > 5 MiB

  let cancelledCleanup = false;
  let cancelledSignal = false;

  const server = new PluginIpcServer({
    socketPath: sockPath,
    token: 'tok-stream',
    onRequest: async (method, params, ctx) => {
      if (method === 'fs.readLarge') {
        return binaryResult(largeBuffer, { size: largeBuffer.length, path: params.path });
      }
      if (method === 'fs.echoLargeReq') {
        return { size: ctx.payload.length, head: ctx.payload.subarray(0, 4).toString('hex') };
      }
      if (method === 'fs.uploadStream') {
        let bytes = 0;
        for await (const chunk of ctx.payloadStream ?? []) bytes += chunk.byteLength;
        return { ok: true, bytesWritten: bytes };
      }
      if (method === 'json.large') {
        return { text: 'x'.repeat(150 * 1024) };
      }
      if (method === 'stream.async') {
        return (async function* () {
          yield { meta: { part: 1 }, payload: largeBuffer.subarray(0, 200 * 1024) };
          yield largeBuffer.subarray(200 * 1024, 4.5 * 1024 * 1024);
          yield { meta: { part: 3 } };
          return { doneMeta: true };
        })();
      }
      if (method === 'stream.cancellable') {
        return (async function* () {
          try {
            let i = 0;
            while (!ctx.signal.aborted) {
              yield Buffer.from(`chunk-${i++}`);
              await new Promise((r) => setTimeout(r, 15));
            }
          } finally {
            cancelledSignal = ctx.signal.aborted;
            cancelledCleanup = true;
          }
        })();
      }
      if (method === 'ping.fast') {
        return { fast: params.seq };
      }
      return null;
    }
  });

  try {
    await server.start();
    const client = await connectUdsClient(sockPath);
    client.send({ type: FrameType.PING, streamId: 0, meta: { token: 'tok-stream' } });
    assert.equal((await client.nextFrame()).type, FrameType.PONG);

    // 1) Start >5 MiB binary transfer AND interleave fast requests concurrently
    client.send({ type: FrameType.REQ, streamId: 1, meta: { method: 'fs.readLarge', args: { path: '/big.bin' } } });
    client.send({ type: FrameType.REQ, streamId: 3, meta: { method: 'ping.fast', args: { seq: 1 } } });
    client.send({ type: FrameType.REQ, streamId: 5, meta: { method: 'ping.fast', args: { seq: 2 } } });

    const stream1Chunks = [];
    let stream1Meta = null;
    let stream1Ended = false;
    let fastRespBeforeEnd = 0;
    const fastSeen = new Map();

    while (!stream1Ended || fastSeen.size < 2) {
      const f = await client.nextFrame(10000);
      if (f.streamId === 1) {
        if (f.type === FrameType.RESP) {
          assert.equal(f.meta.streamed, true);
          stream1Meta = f.meta;
        } else if (f.type === FrameType.STREAM_DATA) {
          assert.ok(f.payload.length <= DEFAULT_CHUNK_BYTES, `Chunk exceeded 64 KiB: ${f.payload.length}`);
          stream1Chunks.push(f.payload);
        } else if (f.type === FrameType.STREAM_END) {
          stream1Ended = true;
        }
      } else if (f.streamId === 3 || f.streamId === 5) {
        assert.equal(f.type, FrameType.RESP);
        fastSeen.set(f.streamId, f.meta.result);
        if (!stream1Ended) fastRespBeforeEnd++;
      }
    }

    const reassembled = Buffer.concat(stream1Chunks);
    assert.equal(reassembled.length, largeBuffer.length);
    assert.equal(Buffer.compare(reassembled, largeBuffer), 0);
    assert.deepEqual(stream1Meta, { size: largeBuffer.length, path: '/big.bin', streamed: true });
    assert.ok(stream1Chunks.length > 80);
    assert.equal(fastSeen.get(3).fast, 1);
    assert.equal(fastSeen.get(5).fast, 2);
    assert.ok(fastRespBeforeEnd >= 1, 'Expected fast responses to interleave before 5 MiB stream completed');

    // 1b) Host -> Plugin chunked REQ (streamed: true) & large JSON RESP (encoding: json, streamed: true)
    client.send({ type: FrameType.REQ, streamId: 11, meta: { method: 'fs.echoLargeReq', args: {}, streamed: true } });
    client.send({ type: FrameType.STREAM_DATA, streamId: 11, payload: largeBuffer.subarray(0, DEFAULT_CHUNK_BYTES) });
    client.send({ type: FrameType.STREAM_DATA, streamId: 11, payload: largeBuffer.subarray(DEFAULT_CHUNK_BYTES, 150 * 1024) });
    client.send({ type: FrameType.STREAM_END, streamId: 11 });
    const echoLargeResp = await client.nextFrame();
    assert.equal(echoLargeResp.type, FrameType.RESP);
    assert.deepEqual(echoLargeResp.meta.result, {
      size: 150 * 1024,
      head: largeBuffer.subarray(0, 4).toString('hex')
    });

    client.send({ type: FrameType.REQ, streamId: 13, meta: { method: 'json.large', args: {} } });
    const jsonHeader = await client.nextFrame();
    assert.equal(jsonHeader.type, FrameType.RESP);
    assert.equal(jsonHeader.meta.encoding, 'json');
    assert.equal(jsonHeader.meta.streamed, true);
    const jsonChunks = [];
    while (true) {
      const f = await client.nextFrame();
      if (f.type === FrameType.STREAM_DATA) jsonChunks.push(f.payload);
      else if (f.type === FrameType.STREAM_END) break;
    }
    assert.deepEqual(JSON.parse(Buffer.concat(jsonChunks).toString('utf8')), { text: 'x'.repeat(150 * 1024) });

    // 1c) Plugin -> Host chunked sendRequest (>64 KiB) + streamed JSON response
    const outLarge = Buffer.alloc(140 * 1024, 0xab);
    const outReqPromise = server.sendRequest('host.large', { ok: true }, outLarge);
    const outReqHdr = await client.nextFrame();
    assert.equal(outReqHdr.type, FrameType.REQ);
    assert.equal(outReqHdr.meta.streamed, true);
    const outReqBytes = [];
    while (true) {
      const f = await client.nextFrame();
      assert.equal(f.streamId, outReqHdr.streamId);
      if (f.type === FrameType.STREAM_DATA) outReqBytes.push(f.payload);
      else if (f.type === FrameType.STREAM_END) break;
    }
    assert.equal(Buffer.compare(Buffer.concat(outReqBytes), outLarge), 0);

    const bigJsonResp = Buffer.from(JSON.stringify({ big: 'y'.repeat(100 * 1024) }), 'utf8');
    client.send({ type: FrameType.RESP, streamId: outReqHdr.streamId, meta: { encoding: 'json', streamed: true } });
    client.send({ type: FrameType.STREAM_DATA, streamId: outReqHdr.streamId, payload: bigJsonResp.subarray(0, DEFAULT_CHUNK_BYTES) });
    client.send({ type: FrameType.STREAM_DATA, streamId: outReqHdr.streamId, payload: bigJsonResp.subarray(DEFAULT_CHUNK_BYTES) });
    client.send({ type: FrameType.STREAM_END, streamId: outReqHdr.streamId });
    assert.deepEqual(await outReqPromise, { big: 'y'.repeat(100 * 1024) });

    // 2) AsyncIterable stream >4 MiB
    client.send({ type: FrameType.REQ, streamId: 7, meta: { method: 'stream.async', params: {} } });
    const asyncBytes = [];
    const asyncMetas = [];
    let asyncEndMeta = null;
    while (true) {
      const f = await client.nextFrame(10000);
      if (f.streamId !== 7) continue;
      if (f.type === FrameType.STREAM_DATA) {
        assert.ok(f.payload.length <= DEFAULT_CHUNK_BYTES);
        if (f.meta) asyncMetas.push(f.meta);
        if (f.payload.length > 0) asyncBytes.push(f.payload);
      } else if (f.type === FrameType.STREAM_END) {
        asyncEndMeta = f.meta;
        break;
      }
    }
    const asyncJoined = Buffer.concat(asyncBytes);
    assert.equal(Buffer.compare(asyncJoined, largeBuffer.subarray(0, 4.5 * 1024 * 1024)), 0);
    assert.deepEqual(asyncMetas, [{ part: 1 }, { part: 3 }]);
    assert.deepEqual(asyncEndMeta, { ok: true, result: { doneMeta: true } });

    // 3) STREAM_END cancellation
    client.send({ type: FrameType.REQ, streamId: 9, meta: { method: 'stream.cancellable', params: {} } });
    const firstChunk = await client.nextFrame();
    assert.equal(firstChunk.streamId, 9);
    assert.equal(firstChunk.type, FrameType.STREAM_DATA);
    client.send({ type: FrameType.STREAM_END, streamId: 9, meta: { cancel: true } });

    // Drain until STREAM_END ack arrives
    while (true) {
      const f = await client.nextFrame();
      if (f.streamId === 9 && f.type === FrameType.STREAM_END) {
        assert.equal(f.meta.cancelled, true);
        break;
      }
    }
    await new Promise((r) => setTimeout(r, 40));
    assert.equal(cancelledSignal, true);
    assert.equal(cancelledCleanup, true);

    client.close();
  } finally {
    await server.close();
    removeDir(tmpDir);
  }
});

test('PluginIpcServer keeps a 20 MiB stream bounded and preserves terminal metadata', async () => {
  const tmpDir = makeTempDir('aicode-ipc-20m-');
  const socketPath = path.join(tmpDir, 'stream.sock');
  const total = 20 * 1024 * 1024;
  const source = Buffer.alloc(total, 0x5a);
  const server = new PluginIpcServer({
    socketPath,
    token: 'tok-20m',
    onRequest: async (method) => {
      if (method !== 'stream.20m') return null;
      return (async function* () {
        for (let offset = 0; offset < source.length; offset += DEFAULT_CHUNK_BYTES) {
          yield source.subarray(offset, Math.min(offset + DEFAULT_CHUNK_BYTES, source.length));
        }
        return { status: 'completed', bytes: total };
      })();
    }
  });
  try {
    await server.start();
    const client = await connectUdsClient(socketPath);
    client.send({ type: FrameType.PING, streamId: 17, meta: { token: 'tok-20m' } });
    assert.equal((await client.nextFrame()).type, FrameType.PONG);
    client.send({ type: FrameType.REQ, streamId: 1, meta: { method: 'stream.20m', stream: true } });
    const chunks = [];
    let end;
    while (!end) {
      const frame = await client.nextFrame(10000);
      assert.equal(frame.streamId, 1);
      if (frame.type === FrameType.STREAM_DATA) {
        assert.ok(frame.payload.length <= DEFAULT_CHUNK_BYTES);
        chunks.push(frame.payload);
      } else if (frame.type === FrameType.STREAM_END) {
        end = frame;
      }
    }
    const payload = Buffer.concat(chunks);
    assert.equal(payload.length, total);
    assert.equal(payload[0], 0x5a);
    assert.equal(payload[payload.length - 1], 0x5a);
    assert.deepEqual(end.meta, { ok: true, result: { status: 'completed', bytes: total } });
    client.close();
  } finally {
    await server.close();
    removeDir(tmpDir);
  }
});

test('PluginIpcServer exposes upload payloadStream and returns bytesWritten after EOF', async () => {
  const tmpDir = makeTempDir('aicode-ipc-upload-');
  const socketPath = path.join(tmpDir, 'upload.sock');
  const server = new PluginIpcServer({
    socketPath,
    token: 'tok-upload',
    onRequest: async (method, params, ctx) => {
      if (method !== 'fs.uploadStream') return null;
      let bytes = 0;
      for await (const chunk of ctx.payloadStream ?? []) bytes += chunk.byteLength;
      return { ok: true, bytesWritten: bytes };
    }
  });
  try {
    await server.start();
    const client = await connectUdsClient(socketPath);
    client.send({ type: FrameType.PING, streamId: 3, meta: { token: 'tok-upload', window: 256 * 1024 } });
    assert.equal((await client.nextFrame()).type, FrameType.PONG);
    client.send({ type: FrameType.REQ, streamId: 1, meta: { method: 'fs.uploadStream', upload: true } });
    client.send({ type: FrameType.STREAM_DATA, streamId: 1, payload: Buffer.alloc(120 * 1024, 0x31) });
    client.send({ type: FrameType.STREAM_END, streamId: 1, meta: { ok: true } });
    let response;
    while (!response) {
      const frame = await client.nextFrame();
      if (frame.type === FrameType.RESP) response = frame;
      else assert.equal(frame.type, FrameType.WINDOW_UPDATE);
    }
    assert.deepEqual(response.meta.result, { ok: true, bytesWritten: 120 * 1024 });
    client.close();
  } finally {
    await server.close();
    removeDir(tmpDir);
  }
});

test('CoalescedEventSender merges same-key deltas within window and flushes before non-coalesced events', async () => {
  const sent = [];
  const coalescer = new CoalescedEventSender({
    windowMs: 25,
    send: (name, payload, rawPayload) => {
      sent.push({ name, payload, rawPayload });
    }
  });

  // Same key string & object deltas merge
  coalescer.push('assistant.delta', { turnId: 't1', text: 'Hel' }, 'turn-1');
  coalescer.push('assistant.delta', { turnId: 't1', text: 'lo ' }, 'turn-1');
  coalescer.push('assistant.delta', { turnId: 't1', text: 'world' }, 'turn-1');
  coalescer.push('exec.output', 'line1\n', 'exec-1');
  coalescer.push('exec.output', 'line2\n', 'exec-1');
  assert.equal(sent.length, 0);
  assert.equal(coalescer.pendingSize, 2);

  // Non-coalesced event flushes pending first, then sends immediately
  coalescer.push('turn.completed', { ok: true });
  assert.equal(coalescer.pendingSize, 0);
  assert.deepEqual(sent, [
    { name: 'assistant.delta', payload: { turnId: 't1', text: 'Hello world' }, rawPayload: undefined },
    { name: 'exec.output', payload: 'line1\nline2\n', rawPayload: undefined },
    { name: 'turn.completed', payload: { ok: true }, rawPayload: undefined }
  ]);

  // Timer auto-flush after windowMs
  sent.length = 0;
  coalescer.push('reasoning.delta', { delta: 'step1 ' }, 'r-1');
  coalescer.push('reasoning.delta', { delta: 'step2' }, 'r-1');
  await new Promise((r) => setTimeout(r, 60));
  assert.deepEqual(sent, [
    { name: 'reasoning.delta', payload: { delta: 'step1 step2' }, rawPayload: undefined }
  ]);

  // Explicit flush(key)
  sent.length = 0;
  coalescer.push('a', { text: '1' }, 'k1');
  coalescer.push('b', { text: '2' }, 'k2');
  coalescer.flush('k1');
  assert.deepEqual(sent, [{ name: 'a', payload: { text: '1' }, rawPayload: undefined }]);
  coalescer.flush();
  assert.equal(sent.length, 2);
  coalescer.dispose(false);
});

test('definePlugin activates UDS transport when env vars exist and falls back to NDJSON otherwise', async () => {
  const sdkIndexPath = path.join(__dirname, '..', 'dist', 'index.js');
  const tmpDir = makeTempDir('aicode-ipc-e2e-');
  const sockPath = path.join(tmpDir, 'e2e.sock');
  const token = 'e2e-token-xyz';

  // 1) Child process with AICODE_IPC_SOCKET & AICODE_IPC_TOKEN
  const pluginScript = `
    const sdk = require(${JSON.stringify(sdkIndexPath)});
    sdk.definePlugin({
      handlers: {
        pingTool: async (args) => ({ echo: args.msg }),
        'fs.read': async (args) => sdk.binaryResult(Buffer.from('file:' + args.path), { size: 5 + args.path.length }),
        triggerHostAndEvents: async () => {
          const info = await sdk.hostCall('host.app.info');
          sdk.emit('plugin.custom', { from: 'uds' });
          const c = sdk.createEventCoalescer({ windowMs: 10 });
          c.push('delta', { text: 'A' }, 'k');
          c.push('delta', { text: 'B' }, 'k');
          c.flush();
          return { info };
        }
      }
    });
  `;

  const child = spawn(process.execPath, ['-e', pluginScript], {
    env: { ...process.env, AICODE_IPC_SOCKET: sockPath, AICODE_IPC_TOKEN: token },
    stdio: ['pipe', 'pipe', 'pipe']
  });

  try {
    const readyLine = await new Promise((resolve, reject) => {
      let buf = '';
      const timer = setTimeout(() => reject(new Error('Timed out waiting for ipc.ready')), 5000);
      child.stdout.on('data', (d) => {
        buf += d.toString('utf8');
        const idx = buf.indexOf('\n');
        if (idx >= 0) {
          clearTimeout(timer);
          resolve(buf.slice(0, idx));
        }
      });
      child.once('exit', (code) => {
        clearTimeout(timer);
        reject(new Error(`Plugin exited early with code ${code}`));
      });
    });

    const readyMsg = JSON.parse(readyLine);
    assert.equal(readyMsg.jsonrpc, '2.0');
    assert.equal(readyMsg.method, 'ipc.ready');
    assert.deepEqual(readyMsg.params, { socket: sockPath });

    // Record any unexpected stdout lines after ipc.ready (hostCall & emit should NOT hit stdout!)
    const extraStdout = [];
    child.stdout.on('data', (d) => {
      const text = d.toString('utf8').trim();
      if (text) extraStdout.push(text);
    });

    const client = await connectUdsClient(sockPath);
    client.send({ type: FrameType.PING, streamId: 0, meta: { token } });
    assert.equal((await client.nextFrame()).type, FrameType.PONG);

    // tool.invoke
    client.send({ type: FrameType.REQ, streamId: 1, meta: { method: 'tool.invoke', params: { handler: 'pingTool', args: { msg: 'hi' } } } });
    const toolResp = await client.nextFrame();
    assert.equal(toolResp.type, FrameType.RESP);
    assert.deepEqual(toolResp.meta.result, { echo: 'hi' });

    // Direct method dispatch (fs.read)
    client.send({ type: FrameType.REQ, streamId: 3, meta: { method: 'fs.read', params: { path: '/etc/hosts' } } });
    const readResp = await client.nextFrame();
    assert.equal(readResp.type, FrameType.RESP);
    assert.equal(readResp.payload.toString('utf8'), 'file:/etc/hosts');
    assert.deepEqual(readResp.meta, { size: 15 });

    // hostCall + emit + coalescer routed over UDS
    client.send({ type: FrameType.REQ, streamId: 5, meta: { method: 'tool.invoke', params: { handler: 'triggerHostAndEvents', args: {} } } });
    const hostReq = await client.nextFrame();
    assert.equal(hostReq.type, FrameType.REQ);
    assert.equal(hostReq.meta.method, 'host.app.info');
    client.send({ type: FrameType.RESP, streamId: hostReq.streamId, meta: { result: { versionName: '2.0.0' } } });

    const collected = [];
    while (collected.length < 3) {
      collected.push(await client.nextFrame());
    }
    const events = collected.filter((f) => f.type === FrameType.EVENT);
    const finalResp = collected.find((f) => f.type === FrameType.RESP && f.streamId === 5);
    assert.equal(events.length, 2);
    assert.deepEqual(events[0].meta, { name: 'plugin.custom', payload: { from: 'uds' } });
    assert.deepEqual(events[1].meta, { name: 'delta', payload: { text: 'AB' } });
    assert.deepEqual(finalResp.meta.result, { info: { versionName: '2.0.0' } });
    assert.deepEqual(extraStdout, []);

    client.close();
    child.stdin.end();
    const exitCode = await new Promise((resolve) => child.once('exit', resolve));
    assert.equal(exitCode, 0);
  } finally {
    child.kill();
    removeDir(tmpDir);
  }

  // 2) Child process WITHOUT UDS env vars -> NDJSON fallback
  const ndjsonScript = `
    const sdk = require(${JSON.stringify(sdkIndexPath)});
    sdk.definePlugin({
      handlers: {
        hello: async (args) => {
          const res = await sdk.hostCall('host.app.isForeground');
          sdk.emit('plain.evt', { ok: res });
          return { greeted: args.name, fg: res };
        }
      }
    });
  `;
  const envNoUds = { ...process.env };
  delete envNoUds.AICODE_IPC_SOCKET;
  delete envNoUds.AICODE_IPC_TOKEN;
  const fallbackChild = spawn(process.execPath, ['-e', ndjsonScript], {
    env: envNoUds,
    stdio: ['pipe', 'pipe', 'pipe']
  });

  try {
    const lines = [];
    const lineWaiters = [];
    let buf = '';
    fallbackChild.stdout.on('data', (d) => {
      buf += d.toString('utf8');
      while (true) {
        const idx = buf.indexOf('\n');
        if (idx < 0) break;
        const line = buf.slice(0, idx);
        buf = buf.slice(idx + 1);
        if (lineWaiters.length > 0) lineWaiters.shift()(JSON.parse(line));
        else lines.push(JSON.parse(line));
      }
    });
    function nextJson() {
      if (lines.length > 0) return Promise.resolve(lines.shift());
      return new Promise((r) => lineWaiters.push(r));
    }

    fallbackChild.stdin.write(JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tool.invoke', params: { handler: 'hello', args: { name: 'AiCode' } } }) + '\n');
    const hostCallMsg = await nextJson();
    assert.equal(hostCallMsg.method, 'host.app.isForeground');
    fallbackChild.stdin.write(JSON.stringify({ jsonrpc: '2.0', id: hostCallMsg.id, result: true }) + '\n');

    const evtMsg = await nextJson();
    assert.equal(evtMsg.method, 'plugin.event');
    assert.deepEqual(evtMsg.params, { name: 'plain.evt', payload: { ok: true } });

    const respMsg = await nextJson();
    assert.equal(respMsg.id, 1);
    assert.deepEqual(respMsg.result, { greeted: 'AiCode', fg: true });

    fallbackChild.stdin.end();
    const code = await new Promise((r) => fallbackChild.once('exit', r));
    assert.equal(code, 0);
  } finally {
    fallbackChild.kill();
  }
});

test('definePlugin supports backend.invoke dispatch and sdk.workspaces host calls', async () => {
  const sdkIndexPath = path.join(__dirname, '..', 'dist', 'index.js');
  const tmpDir = makeTempDir('aicode-ipc-backend-');
  const sockPath = path.join(tmpDir, 'backend.sock');
  const token = 'backend-token-123';

  const pluginScript = `
    const sdk = require(${JSON.stringify(sdkIndexPath)});
    sdk.definePlugin({
      handlers: {
        'backend.fs': async (args) => ({ op: args.op, path: args.path }),
        syncWorkspaces: async (args) => {
          const items = [{ id: 'ws-1', name: 'Demo', path: '/srv/demo', group: 'remote', available: true, capabilities: { createChild: true, delete: false, git: true } }, { id: 'ws-default', name: 'Defaults', path: '/srv/defaults' }];
          const r1 = await sdk.workspaces.upsert(args.backendId, items);
          const r2 = await sdk.workspaces.remove(args.backendId, ['ws-old']);
          const r3 = await sdk.workspaces.set(args.backendId, items);
          return { r1, r2, r3 };
        }
      }
    });
  `;

  const child = spawn(process.execPath, ['-e', pluginScript], {
    env: { ...process.env, AICODE_IPC_SOCKET: sockPath, AICODE_IPC_TOKEN: token },
    stdio: ['pipe', 'pipe', 'pipe']
  });

  try {
    await new Promise((resolve, reject) => {
      let buf = '';
      const timer = setTimeout(() => reject(new Error('Timed out waiting for ipc.ready')), 5000);
      child.stdout.on('data', (d) => {
        buf += d.toString('utf8');
        if (buf.includes('\n')) {
          clearTimeout(timer);
          resolve();
        }
      });
      child.once('exit', (code) => {
        clearTimeout(timer);
        reject(new Error(`Plugin exited early with code ${code}`));
      });
    });

    const client = await connectUdsClient(sockPath);
    client.send({ type: FrameType.PING, streamId: 0, meta: { token } });
    assert.equal((await client.nextFrame()).type, FrameType.PONG);

    // 1) backend.invoke with declared handler
    client.send({
      type: FrameType.REQ,
      streamId: 1,
      meta: { method: 'backend.invoke', params: { handler: 'backend.fs', args: { op: 'stat', path: '/srv/a' } } }
    });
    const okResp = await client.nextFrame();
    assert.equal(okResp.type, FrameType.RESP);
    assert.deepEqual(okResp.meta.result, { op: 'stat', path: '/srv/a' });

    // 2) backend.invoke without handler returns HANDLER_NOT_FOUND
    client.send({
      type: FrameType.REQ,
      streamId: 3,
      meta: { method: 'backend.invoke', params: { args: {} } }
    });
    const missingErr = await client.nextFrame();
    assert.equal(missingErr.type, FrameType.ERR);
    assert.equal(missingErr.meta.code, 'HANDLER_NOT_FOUND');

    // 3) backend.invoke with unknown handler returns HANDLER_NOT_FOUND
    client.send({
      type: FrameType.REQ,
      streamId: 5,
      meta: { method: 'backend.invoke', params: { handler: 'backend.unknown', args: {} } }
    });
    const unknownErr = await client.nextFrame();
    assert.equal(unknownErr.type, FrameType.ERR);
    assert.equal(unknownErr.meta.code, 'HANDLER_NOT_FOUND');

    // 4) sdk.workspaces.{upsert,remove,set} invoke host.workspaces.*
    client.send({
      type: FrameType.REQ,
      streamId: 7,
      meta: { method: 'backend.invoke', params: { handler: 'syncWorkspaces', args: { backendId: 'ssh-dev' } } }
    });

    const expectedHostCalls = [
      {
        method: 'host.workspaces.upsert',
        params: {
          backendId: 'ssh-dev',
          workspaces: [{ id: 'ws-1', name: 'Demo', path: '/srv/demo', group: 'remote', available: true, capabilities: { createChild: true, delete: false, git: true } }, { id: 'ws-default', name: 'Defaults', path: '/srv/defaults' }]
        }
      },
      {
        method: 'host.workspaces.remove',
        params: { backendId: 'ssh-dev', workspaceIds: ['ws-old'] }
      },
      {
        method: 'host.workspaces.set',
        params: {
          backendId: 'ssh-dev',
          workspaces: [{ id: 'ws-1', name: 'Demo', path: '/srv/demo', group: 'remote', available: true, capabilities: { createChild: true, delete: false, git: true } }, { id: 'ws-default', name: 'Defaults', path: '/srv/defaults' }]
        }
      }
    ];

    for (const expected of expectedHostCalls) {
      const hostReq = await client.nextFrame();
      assert.equal(hostReq.type, FrameType.REQ);
      assert.equal(hostReq.meta.method, expected.method);
      assert.deepEqual(hostReq.meta.params, expected.params);
      client.send({ type: FrameType.RESP, streamId: hostReq.streamId, meta: { result: { ok: true } } });
    }

    const syncResp = await client.nextFrame();
    assert.equal(syncResp.type, FrameType.RESP);
    assert.equal(syncResp.streamId, 7);
    assert.deepEqual(syncResp.meta.result, {
      r1: { ok: true },
      r2: { ok: true },
      r3: { ok: true }
    });

    client.close();
    child.stdin.end();
    const exitCode = await new Promise((resolve) => child.once('exit', resolve));
    assert.equal(exitCode, 0);
  } finally {
    child.kill();
    removeDir(tmpDir);
  }
});

test('shared complete manifest fixtures validate against the real schema and API version', () => {
  const { checkFixtures, validateManifest } = require('../../schema/check-manifests.cjs');
  const { fixtures, errors } = checkFixtures();
  assert.deepEqual(errors, []);
  const base = fixtures.validManifests[0];
  for (const range of ['^1.0.0', '~1.0.0', '>=1.0.0 <2.0.0', '1.0.0', '=1.0.0']) {
    assert.deepEqual(validateManifest({ ...base, engines: { pluginApi: range } }), []);
  }
  for (const range of ['^2.0.0', '^0.1.1', '^0.2.0', '~2.0.0', '>=2.0.0', '2.x', '*']) {
    assert.notEqual(validateManifest({ ...base, engines: { pluginApi: range } }).length, 0, range);
  }
  const backend = base.contributes.executionBackends[0];
  const withBackends = (count) => ({ ...base, contributes: { executionBackends: Array.from({ length: count }, (_, i) => ({ ...backend, id: `backend${i}` })) } });
  assert.deepEqual(validateManifest(withBackends(32)), []);
  assert.notEqual(validateManifest(withBackends(33)).length, 0);
  for (const isRemoteEnvironment of [undefined, true]) {
    const remoteWorkspaceConfig = { ...backend, capabilities: { projectConfigLocation: 'workspaceDir', ...(isRemoteEnvironment === undefined ? {} : { isRemoteEnvironment }) } };
    assert.notEqual(validateManifest({ ...base, contributes: { executionBackends: [remoteWorkspaceConfig] } }).length, 0);
  }
});

test('plugin.schema.json defines contributes.executionBackends with expected fields and defaults', () => {
  const schemaPath = path.join(__dirname, '..', '..', 'schema', 'plugin.schema.json');
  const schema = JSON.parse(fs.readFileSync(schemaPath, 'utf8'));

  assert.match(schema.properties.runtime.description, /executionBackends/);

  const eb = schema.properties.contributes.properties.executionBackends;
  assert.equal(eb.type, 'array');
  assert.equal(eb.maxItems, 32);

  const item = eb.items;
  assert.equal(item.additionalProperties, false);
  assert.deepEqual(item.required, ['id', 'title', 'handlers']);
  assert.equal(item.properties.id.$ref, '#/$defs/localId');
  assert.equal(item.properties.title.$ref, '#/$defs/localizedText80');
  assert.equal(item.properties.description.$ref, '#/$defs/localizedText600');

  const caps = item.properties.capabilities;
  assert.equal(caps.additionalProperties, false);
  assert.equal(caps.properties.filesystem.default, true);
  assert.equal(caps.properties.command.default, true);
  assert.equal(caps.properties.terminal.default, false);
  assert.equal(caps.properties.fileWatch.default, false);
  assert.equal(caps.properties.fileWatch.const, false);
  assert.equal(caps.properties.portForward.default, false);
  assert.equal(caps.properties.portForward.const, false);
  assert.equal(item.allOf.length, 4);
  assert.equal(caps.properties.isRemoteEnvironment.default, true);
  assert.deepEqual(caps.properties.workspaceDiscovery.enum, ['scan', 'register', 'hybrid']);
  assert.equal(caps.properties.workspaceDiscovery.default, 'scan');
  assert.deepEqual(caps.properties.projectConfigLocation.enum, ['workspaceDir', 'appPrivateDir']);
  assert.equal(caps.properties.projectConfigLocation.default, 'appPrivateDir');

  const handlers = item.properties.handlers;
  assert.equal(handlers.additionalProperties, false);
  for (const key of [
    'fs',
    'exec',
    'execStream',
    'execHealth',
    'terminal',
    'workspaceList',
    'workspaceCreate',
    'workspaceDelete',
    'workspaceSelect'
  ]) {
    assert.equal(handlers.properties[key].$ref, '#/$defs/handlerName');
  }
});

test('definePlugin supports workflow.invoke async generator streaming over UDS, emitWorkflowEvent, and NDJSON fallback', async () => {
  const sdkIndexPath = path.join(__dirname, '..', 'dist', 'index.js');
  const tmpDir = makeTempDir('aicode-ipc-workflow-');
  const sockPath = path.join(tmpDir, 'workflow.sock');
  const token = 'workflow-token-123';

  const pluginScript = `
    const sdk = require(${JSON.stringify(sdkIndexPath)});
    sdk.definePlugin({
      handlers: {
        'wf.startTurn': async function* (args, ctx) {
          yield { type: 'reasoning.delta', text: 'thinking...' };
          yield { type: 'assistant.delta', text: 'Hello ' };
          yield { type: 'assistant.delta', text: args.userRequest };
          if (ctx && ctx.signal && ctx.signal.aborted) {
            yield { type: 'failed', message: 'aborted' };
            return { aborted: true };
          }
          yield { type: 'completed', content: 'Hello ' + args.userRequest };
          return { turnId: args.turnId, status: 'completed' };
        },
        'wf.startTurnSlow': async function* (args, ctx) {
          yield { type: 'assistant.delta', text: 'part-1' };
          await new Promise((resolve) => {
            const t = setTimeout(resolve, 2000);
            ctx.signal.addEventListener('abort', () => {
              clearTimeout(t);
              resolve();
            }, { once: true });
          });
          return { cancelled: ctx.signal.aborted };
        },
        'wf.emitOnly': async (args) => {
          sdk.emitWorkflowEvent(args.turnId, { type: 'assistant.delta', text: 'via-event' });
          sdk.emitWorkflowEvent(args.turnId, { type: 'completed', content: 'via-event' });
          return { ok: true };
        },
        'wf.cancelTurn': async (args) => ({ cancelled: args.turnId })
      }
    });
  `;

  // 1) UDS mode
  const child = spawn(process.execPath, ['-e', pluginScript], {
    env: { ...process.env, AICODE_IPC_SOCKET: sockPath, AICODE_IPC_TOKEN: token },
    stdio: ['pipe', 'pipe', 'pipe']
  });

  try {
    await new Promise((resolve, reject) => {
      let buf = '';
      const timer = setTimeout(() => reject(new Error('Timed out waiting for ipc.ready')), 5000);
      child.stdout.on('data', (d) => {
        buf += d.toString('utf8');
        if (buf.includes('\n')) {
          clearTimeout(timer);
          resolve();
        }
      });
      child.once('exit', (code) => {
        clearTimeout(timer);
        reject(new Error(`Plugin exited early with code ${code}`));
      });
    });

    const client = await connectUdsClient(sockPath);
    client.send({ type: FrameType.PING, streamId: 0, meta: { token } });
    assert.equal((await client.nextFrame()).type, FrameType.PONG);

    // 1a) async function* startTurn streams STREAM_DATA frames and closes with STREAM_END
    client.send({
      type: FrameType.REQ,
      streamId: 1,
      meta: {
        method: 'workflow.invoke',
        params: {
          handler: 'wf.startTurn',
          args: {
            sessionId: 's1',
            turnId: 't1',
            userRequest: 'AiCode',
            mode: 'build',
            workspacePath: '/srv/app'
          }
        }
      }
    });

    const streamedEvents = [];
    let endFrame = null;
    while (true) {
      const f = await client.nextFrame();
      assert.equal(f.streamId, 1);
      if (f.type === FrameType.STREAM_DATA) {
        assert.equal(f.payload.length, 0);
        streamedEvents.push(f.meta);
      } else if (f.type === FrameType.STREAM_END) {
        endFrame = f;
        break;
      } else {
        assert.fail(`Unexpected frame type ${f.type}`);
      }
    }

    assert.deepEqual(streamedEvents, [
      { type: 'reasoning.delta', text: 'thinking...' },
      { type: 'assistant.delta', text: 'Hello ' },
      { type: 'assistant.delta', text: 'AiCode' },
      { type: 'completed', content: 'Hello AiCode' }
    ]);
    assert.deepEqual(endFrame.meta, {
      ok: true,
      result: { turnId: 't1', status: 'completed' }
    });

    // 1b) emitWorkflowEvent sends EVENT frame { name: 'workflow.event', payload: { turnId, ...event } }
    client.send({
      type: FrameType.REQ,
      streamId: 3,
      meta: {
        method: 'workflow.invoke',
        params: { handler: 'wf.emitOnly', args: { turnId: 't2' } }
      }
    });
    const ev1 = await client.nextFrame();
    const ev2 = await client.nextFrame();
    const emitResp = await client.nextFrame();
    assert.equal(ev1.type, FrameType.EVENT);
    assert.deepEqual(ev1.meta, {
      name: 'workflow.event',
      payload: { turnId: 't2', type: 'assistant.delta', text: 'via-event' }
    });
    assert.equal(ev2.type, FrameType.EVENT);
    assert.deepEqual(ev2.meta, {
      name: 'workflow.event',
      payload: { turnId: 't2', type: 'completed', content: 'via-event' }
    });
    assert.equal(emitResp.type, FrameType.RESP);
    assert.deepEqual(emitResp.meta.result, { ok: true });

    // 1c) workflow.invoke missing/unknown handler returns HANDLER_NOT_FOUND
    client.send({
      type: FrameType.REQ,
      streamId: 5,
      meta: { method: 'workflow.invoke', params: { args: {} } }
    });
    const missingErr = await client.nextFrame();
    assert.equal(missingErr.type, FrameType.ERR);
    assert.equal(missingErr.meta.code, 'HANDLER_NOT_FOUND');

    client.send({
      type: FrameType.REQ,
      streamId: 7,
      meta: { method: 'workflow.invoke', params: { handler: 'wf.unknown', args: {} } }
    });
    const unknownErr = await client.nextFrame();
    assert.equal(unknownErr.type, FrameType.ERR);
    assert.equal(unknownErr.meta.code, 'HANDLER_NOT_FOUND');

    // 1d) STREAM_END cancels slow generator
    client.send({
      type: FrameType.REQ,
      streamId: 9,
      meta: {
        method: 'workflow.invoke',
        params: { handler: 'wf.startTurnSlow', args: { turnId: 't3' } }
      }
    });
    const slowFirst = await client.nextFrame();
    assert.equal(slowFirst.type, FrameType.STREAM_DATA);
    assert.deepEqual(slowFirst.meta, { type: 'assistant.delta', text: 'part-1' });
    client.send({ type: FrameType.STREAM_END, streamId: 9, meta: { cancel: true } });
    const cancelAck = await client.nextFrame();
    assert.equal(cancelAck.type, FrameType.STREAM_END);
    assert.equal(cancelAck.streamId, 9);
    assert.deepEqual(cancelAck.meta, { cancelled: true });

    client.close();
    child.stdin.end();
    const exitCode = await new Promise((resolve) => child.once('exit', resolve));
    assert.equal(exitCode, 0);
  } finally {
    child.kill();
    removeDir(tmpDir);
  }

  // 2) NDJSON fallback mode (both async generator and emitWorkflowEvent + missing handler)
  const envNoUds = { ...process.env };
  delete envNoUds.AICODE_IPC_SOCKET;
  delete envNoUds.AICODE_IPC_TOKEN;
  const fallbackChild = spawn(process.execPath, ['-e', pluginScript], {
    env: envNoUds,
    stdio: ['pipe', 'pipe', 'pipe']
  });

  try {
    const lines = [];
    const lineWaiters = [];
    let buf = '';
    fallbackChild.stdout.on('data', (d) => {
      buf += d.toString('utf8');
      while (true) {
        const idx = buf.indexOf('\n');
        if (idx < 0) break;
        const line = buf.slice(0, idx);
        buf = buf.slice(idx + 1);
        if (lineWaiters.length > 0) lineWaiters.shift()(JSON.parse(line));
        else lines.push(JSON.parse(line));
      }
    });
    function nextJson() {
      if (lines.length > 0) return Promise.resolve(lines.shift());
      return new Promise((r) => lineWaiters.push(r));
    }

    // 2a) emitWorkflowEvent over NDJSON
    fallbackChild.stdin.write(
      JSON.stringify({
        jsonrpc: '2.0',
        id: 1,
        method: 'workflow.invoke',
        params: { handler: 'wf.emitOnly', args: { turnId: 'nd-1' } }
      }) + '\n'
    );
    const ndEv1 = await nextJson();
    const ndEv2 = await nextJson();
    const ndResp1 = await nextJson();
    assert.equal(ndEv1.method, 'plugin.event');
    assert.deepEqual(ndEv1.params, {
      name: 'workflow.event',
      payload: { turnId: 'nd-1', type: 'assistant.delta', text: 'via-event' }
    });
    assert.equal(ndEv2.method, 'plugin.event');
    assert.deepEqual(ndEv2.params, {
      name: 'workflow.event',
      payload: { turnId: 'nd-1', type: 'completed', content: 'via-event' }
    });
    assert.equal(ndResp1.id, 1);
    assert.deepEqual(ndResp1.result, { ok: true });

    // 2b) async function* startTurn falls back to plugin.event over NDJSON
    fallbackChild.stdin.write(
      JSON.stringify({
        jsonrpc: '2.0',
        id: 2,
        method: 'workflow.invoke',
        params: {
          handler: 'wf.startTurn',
          args: {
            sessionId: 's2',
            turnId: 'nd-2',
            userRequest: 'World',
            mode: 'plan',
            workspacePath: '/tmp'
          }
        }
      }) + '\n'
    );
    const genMsgs = [await nextJson(), await nextJson(), await nextJson(), await nextJson()];
    for (const m of genMsgs) {
      assert.equal(m.method, 'plugin.event');
      assert.equal(m.params.name, 'workflow.event');
      assert.equal(m.params.payload.turnId, 'nd-2');
    }
    assert.deepEqual(
      genMsgs.map((m) => m.params.payload.type),
      ['reasoning.delta', 'assistant.delta', 'assistant.delta', 'completed']
    );
    const ndResp2 = await nextJson();
    assert.equal(ndResp2.id, 2);
    assert.deepEqual(ndResp2.result, { turnId: 'nd-2', status: 'completed' });

    // 2c) missing handler over NDJSON returns HANDLER_NOT_FOUND
    fallbackChild.stdin.write(
      JSON.stringify({
        jsonrpc: '2.0',
        id: 3,
        method: 'workflow.invoke',
        params: { args: {} }
      }) + '\n'
    );
    const ndErr = await nextJson();
    assert.equal(ndErr.id, 3);
    assert.equal(ndErr.error.code, 'HANDLER_NOT_FOUND');

    fallbackChild.stdin.end();
    const code = await new Promise((r) => fallbackChild.once('exit', r));
    assert.equal(code, 0);
  } finally {
    fallbackChild.kill();
  }
});

test('plugin.schema.json defines contributes.workflows with expected fields and constraints', () => {
  const schemaPath = path.join(__dirname, '..', '..', 'schema', 'plugin.schema.json');
  const schema = JSON.parse(fs.readFileSync(schemaPath, 'utf8'));

  assert.match(schema.properties.runtime.description, /workflows/);

  const wf = schema.properties.contributes.properties.workflows;
  assert.equal(wf.type, 'array');
  assert.equal(wf.maxItems, 32);

  const item = wf.items;
  assert.equal(item.type, 'object');
  assert.equal(item.additionalProperties, false);
  assert.deepEqual(item.required, ['id', 'title', 'handlers']);
  assert.equal(item.properties.id.$ref, '#/$defs/localId');
  assert.equal(item.properties.title.$ref, '#/$defs/localizedText80');
  assert.equal(item.properties.description.$ref, '#/$defs/localizedText600');

  const caps = item.properties.capabilities;
  assert.equal(caps.type, 'object');
  assert.equal(caps.additionalProperties, false);
  for (const key of ['streaming', 'tools', 'compaction', 'titleGeneration']) {
    assert.deepEqual(caps.properties[key], { type: 'boolean' });
  }

  const handlers = item.properties.handlers;
  assert.equal(handlers.type, 'object');
  assert.equal(handlers.additionalProperties, false);
  assert.deepEqual(handlers.required, ['startTurn', 'cancelTurn']);
  for (const key of ['startTurn', 'cancelTurn', 'compact', 'generateTitle']) {
    assert.equal(handlers.properties[key].$ref, '#/$defs/handlerName');
  }
});

test('plugin.schema.json defines contributes.sessions with expected fields and constraints', () => {
  const schemaPath = path.join(__dirname, '..', '..', 'schema', 'plugin.schema.json');
  const schema = JSON.parse(fs.readFileSync(schemaPath, 'utf8'));

  assert.match(schema.properties.runtime.description, /sessions/);

  const sessionsSchema = schema.properties.contributes.properties.sessions;
  assert.equal(sessionsSchema.type, 'array');
  assert.equal(sessionsSchema.maxItems, 32);

  const item = sessionsSchema.items;
  assert.equal(item.type, 'object');
  assert.equal(item.additionalProperties, false);
  assert.deepEqual(item.required, ['id', 'title', 'handlers']);
  assert.equal(item.properties.id.$ref, '#/$defs/localId');
  assert.equal(item.properties.title.$ref, '#/$defs/localizedText80');
  assert.equal(item.properties.description.$ref, '#/$defs/localizedText600');

  const handlers = item.properties.handlers;
  assert.equal(handlers.type, 'object');
  assert.equal(handlers.additionalProperties, false);
  assert.deepEqual(handlers.required, ['readMessages']);
  for (const key of [
    'refresh',
    'readMessages',
    'createSession',
    'deleteSession',
    'renameSession',
    'pinSession'
  ]) {
    assert.equal(handlers.properties[key].$ref, '#/$defs/handlerName');
  }
});

test('definePlugin supports sessions.invoke dispatch and sdk.sessions host calls', async () => {
  const sdkIndexPath = path.join(__dirname, '..', 'dist', 'index.js');
  const tmpDir = makeTempDir('aicode-ipc-sessions-');
  const sockPath = path.join(tmpDir, 'sessions.sock');
  const token = 'sessions-token-123';

  const pluginScript = `
    const sdk = require(${JSON.stringify(sdkIndexPath)});
    sdk.definePlugin({
      handlers: {
        'sessions.readMessages': async (args) => ({
          sessionId: args.sessionId,
          messages: [{ id: 'm1', role: 'assistant', createdAt: 1700000000000, blocks: [{ type: 'text', text: 'hi' }] }],
          hasMore: true,
          nextCursor: args.cursor
        }),
        'sessions.sync': async (args) => {
          const summary = [{ sessionId: 's-1', title: 'Task 1', running: true }];
          const r1 = await sdk.sessions.upsert(args.sourceId, summary);
          const r2 = await sdk.sessions.remove(args.sourceId, ['s-old']);
          const r3 = await sdk.sessions.replaceAll(args.sourceId, summary);
          const r4 = await sdk.sessions.setLoading(args.sourceId, true);
          const r5 = await sdk.sessions.setError(args.sourceId, 'sync failed');
          const r6 = await sdk.sessions.setError(args.sourceId, null);
          const r7 = await sdk.sessions.setError(args.sourceId);
          const r8 = await sdk.sessions.setThinking(args.sourceId, 's-1', true, 'working');
          const r9 = await sdk.sessions.setThinking(args.sourceId, 's-1', false);
          return { r1, r2, r3, r4, r5, r6, r7, r8, r9 };
        }
      }
    });
  `;

  const child = spawn(process.execPath, ['-e', pluginScript], {
    env: { ...process.env, AICODE_IPC_SOCKET: sockPath, AICODE_IPC_TOKEN: token },
    stdio: ['pipe', 'pipe', 'pipe']
  });

  try {
    await new Promise((resolve, reject) => {
      let buf = '';
      const timer = setTimeout(() => reject(new Error('Timed out waiting for ipc.ready')), 5000);
      child.stdout.on('data', (d) => {
        buf += d.toString('utf8');
        if (buf.includes('\n')) {
          clearTimeout(timer);
          resolve();
        }
      });
      child.once('exit', (code) => {
        clearTimeout(timer);
        reject(new Error(`Plugin exited early with code ${code}`));
      });
    });

    const client = await connectUdsClient(sockPath);
    client.send({ type: FrameType.PING, streamId: 0, meta: { token } });
    assert.equal((await client.nextFrame()).type, FrameType.PONG);

    // 1) sessions.invoke with declared handler
    client.send({
      type: FrameType.REQ,
      streamId: 1,
      meta: {
        method: 'sessions.invoke',
        params: { handler: 'sessions.readMessages', args: { sessionId: 's-1', cursor: { page: 2, boundary: ['m1', 123, null, true] } } }
      }
    });
    const okResp = await client.nextFrame();
    assert.equal(okResp.type, FrameType.RESP);
    assert.deepEqual(okResp.meta.result, {
      sessionId: 's-1',
      messages: [{ id: 'm1', role: 'assistant', createdAt: 1700000000000, blocks: [{ type: 'text', text: 'hi' }] }],
      hasMore: true,
      nextCursor: { page: 2, boundary: ['m1', 123, null, true] }
    });

    for (const cursor of [null, false, 42, 'opaque', [1, { offset: 2 }]]) {
      client.send({ type: FrameType.REQ, streamId: 9, meta: {
        method: 'sessions.invoke', params: { handler: 'sessions.readMessages', args: { sessionId: 's-1', cursor } }
      } });
      const response = await client.nextFrame();
      assert.equal(response.type, FrameType.RESP);
      assert.deepEqual(response.meta.result.nextCursor, cursor);
    }

    // 2) sessions.invoke without handler returns HANDLER_NOT_FOUND
    client.send({
      type: FrameType.REQ,
      streamId: 3,
      meta: { method: 'sessions.invoke', params: { args: {} } }
    });
    const missingErr = await client.nextFrame();
    assert.equal(missingErr.type, FrameType.ERR);
    assert.equal(missingErr.meta.code, 'HANDLER_NOT_FOUND');

    // 3) sessions.invoke with unknown handler returns HANDLER_NOT_FOUND
    client.send({
      type: FrameType.REQ,
      streamId: 5,
      meta: { method: 'sessions.invoke', params: { handler: 'sessions.unknown', args: {} } }
    });
    const unknownErr = await client.nextFrame();
    assert.equal(unknownErr.type, FrameType.ERR);
    assert.equal(unknownErr.meta.code, 'HANDLER_NOT_FOUND');

    // 4) sdk.sessions.* host calls
    client.send({
      type: FrameType.REQ,
      streamId: 7,
      meta: { method: 'sessions.invoke', params: { handler: 'sessions.sync', args: { sourceId: 'src-1' } } }
    });

    const expectedHostCalls = [
      {
        method: 'host.sessions.upsert',
        params: { sourceId: 'src-1', sessions: [{ sessionId: 's-1', title: 'Task 1', running: true }] }
      },
      {
        method: 'host.sessions.remove',
        params: { sourceId: 'src-1', sessionIds: ['s-old'] }
      },
      {
        method: 'host.sessions.replaceAll',
        params: { sourceId: 'src-1', sessions: [{ sessionId: 's-1', title: 'Task 1', running: true }] }
      },
      {
        method: 'host.sessions.setLoading',
        params: { sourceId: 'src-1', loading: true }
      },
      {
        method: 'host.sessions.setError',
        params: { sourceId: 'src-1', error: 'sync failed' }
      },
      {
        method: 'host.sessions.setError',
        params: { sourceId: 'src-1', error: null }
      },
      {
        method: 'host.sessions.setError',
        params: { sourceId: 'src-1' }
      },
      {
        method: 'host.sessions.setThinking',
        params: { sourceId: 'src-1', sessionId: 's-1', visible: true, text: 'working' }
      },
      {
        method: 'host.sessions.setThinking',
        params: { sourceId: 'src-1', sessionId: 's-1', visible: false }
      }
    ];

    for (const expected of expectedHostCalls) {
      const hostReq = await client.nextFrame();
      assert.equal(hostReq.type, FrameType.REQ);
      assert.equal(hostReq.meta.method, expected.method);
      assert.deepEqual(hostReq.meta.params, expected.params);
      client.send({ type: FrameType.RESP, streamId: hostReq.streamId, meta: { result: { ok: true } } });
    }

    const syncResp = await client.nextFrame();
    assert.equal(syncResp.type, FrameType.RESP);
    assert.equal(syncResp.streamId, 7);
    assert.deepEqual(syncResp.meta.result, {
      r1: { ok: true },
      r2: { ok: true },
      r3: { ok: true },
      r4: { ok: true },
      r5: { ok: true },
      r6: { ok: true },
      r7: { ok: true },
      r8: { ok: true },
      r9: { ok: true }
    });

    client.close();
    child.stdin.end();
    const exitCode = await new Promise((resolve) => child.once('exit', resolve));
    assert.equal(exitCode, 0);
  } finally {
    child.kill();
    removeDir(tmpDir);
  }
});
