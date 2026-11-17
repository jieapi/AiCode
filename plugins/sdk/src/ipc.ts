import { timingSafeEqual } from 'node:crypto';
import * as fs from 'node:fs';
import { createServer, Server, Socket } from 'node:net';
import * as path from 'node:path';

export const IPC_MAGIC = 0x4143;
export const IPC_VERSION = 1;
export const IPC_HEADER_BYTES = 16;
export const MAX_META_BYTES = 1024 * 1024;
export const MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;
export const DEFAULT_CHUNK_BYTES = 64 * 1024;

export const FrameType = {
  REQ: 1,
  RESP: 2,
  ERR: 3,
  STREAM_DATA: 4,
  STREAM_END: 5,
  EVENT: 6,
  PING: 7,
  PONG: 8
} as const;

export type FrameTypeValue = (typeof FrameType)[keyof typeof FrameType];

export interface IpcFrame<M = Record<string, unknown>> {
  type: FrameTypeValue;
  streamId: number;
  meta?: M;
  payload?: Uint8Array | null;
}

export interface DecodedFrame<M = Record<string, unknown>> {
  type: FrameTypeValue;
  streamId: number;
  meta?: M;
  payload: Buffer;
}

export interface BinaryResult<M = Record<string, unknown>> {
  meta?: M;
  payload: Uint8Array;
}

export type StreamChunk<M = Record<string, unknown>> =
  | Uint8Array
  | {
      meta?: M;
      payload?: Uint8Array | null;
    };

export interface PluginRequestContext {
  signal: AbortSignal;
  payload: Buffer;
  streamId: number;
  method: string;
}

export type IpcRequestHandler = (
  method: string,
  params: Record<string, unknown>,
  context: PluginRequestContext
) => unknown | Promise<unknown>;

export interface PluginIpcServerOptions {
  socketPath: string;
  token: string;
  onRequest?: IpcRequestHandler;
  onEvent?: (name: string, payload: unknown, rawPayload: Buffer) => void;
  onReady?: () => void;
  onClose?: () => void;
  onError?: (error: Error) => void;
  chunkBytes?: number;
}

export interface CoalescedPushOptions {
  key?: string;
  rawPayload?: Uint8Array;
}

export interface CoalescedEventSenderOptions {
  windowMs?: number;
  send: (name: string, payload: unknown, rawPayload?: Uint8Array) => void;
  merge?: (previous: unknown, incoming: unknown, key: string) => unknown;
}

export class IpcError extends Error {
  code: string;

  constructor(code: string, message: string) {
    super(message);
    this.name = 'IpcError';
    this.code = code;
  }
}

const EMPTY_BUFFER = Buffer.alloc(0);
const TEXT_MERGE_FIELDS = ['text', 'delta', 'chunk', 'content', 'data'] as const;

function isValidFrameType(type: number): type is FrameTypeValue {
  return Number.isInteger(type) && type >= FrameType.REQ && type <= FrameType.PONG;
}

function toBuffer(data: Uint8Array): Buffer {
  return Buffer.isBuffer(data) ? data : Buffer.from(data.buffer, data.byteOffset, data.byteLength);
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value) && !(value instanceof Uint8Array);
}

function errorMessage(error: unknown): string {
  return String((error as { message?: unknown } | null)?.message || error);
}

function errorCode(error: unknown): string {
  const code = (error as { code?: unknown } | null)?.code;
  return typeof code === 'string' && code ? code : 'PLUGIN_ERROR';
}

function tokensEqual(a: unknown, b: string): boolean {
  if (typeof a !== 'string') return false;
  const bufA = Buffer.from(a, 'utf8');
  const bufB = Buffer.from(b, 'utf8');
  if (bufA.length !== bufB.length) return false;
  return timingSafeEqual(bufA, bufB);
}

export function isBinaryResult(value: unknown): value is BinaryResult {
  if (value === null || typeof value !== 'object') return false;
  const candidate = value as { payload?: unknown };
  return candidate.payload instanceof Uint8Array;
}

export function isAsyncIterable(value: unknown): value is AsyncIterable<unknown> {
  if (value === null || typeof value !== 'object') return false;
  return typeof (value as { [Symbol.asyncIterator]?: unknown })[Symbol.asyncIterator] === 'function';
}

export function binaryResult<M = Record<string, unknown>>(
  payload: Uint8Array,
  meta?: M
): BinaryResult<M> {
  return meta === undefined ? { payload } : { meta, payload };
}

export function encodeFrame(frame: IpcFrame): Buffer {
  if (!isValidFrameType(frame.type)) {
    throw new IpcError('INVALID_FRAME_TYPE', 'Invalid IPC frame type: ' + String(frame.type));
  }
  if (!Number.isInteger(frame.streamId) || frame.streamId < 0 || frame.streamId > 0xffffffff) {
    throw new IpcError('INVALID_STREAM_ID', 'Invalid IPC streamId: ' + String(frame.streamId));
  }

  const metaBuf =
    frame.meta === undefined ? EMPTY_BUFFER : Buffer.from(JSON.stringify(frame.meta), 'utf8');
  if (metaBuf.length > MAX_META_BYTES) {
    throw new IpcError('META_TOO_LARGE', `Frame metadata exceeds ${MAX_META_BYTES} bytes`);
  }

  const payloadBuf = frame.payload == null ? EMPTY_BUFFER : toBuffer(frame.payload);
  if (payloadBuf.length > MAX_PAYLOAD_BYTES) {
    throw new IpcError('PAYLOAD_TOO_LARGE', `Frame payload exceeds ${MAX_PAYLOAD_BYTES} bytes`);
  }

  const out = Buffer.allocUnsafe(IPC_HEADER_BYTES + metaBuf.length + payloadBuf.length);
  out.writeUInt16BE(IPC_MAGIC, 0);
  out.writeUInt8(IPC_VERSION, 2);
  out.writeUInt8(frame.type, 3);
  out.writeUInt32BE(frame.streamId >>> 0, 4);
  out.writeUInt32BE(metaBuf.length >>> 0, 8);
  out.writeUInt32BE(payloadBuf.length >>> 0, 12);
  if (metaBuf.length > 0) metaBuf.copy(out, IPC_HEADER_BYTES);
  if (payloadBuf.length > 0) payloadBuf.copy(out, IPC_HEADER_BYTES + metaBuf.length);
  return out;
}

interface ParsedHeader {
  type: FrameTypeValue;
  streamId: number;
  metaLen: number;
  payloadLen: number;
}

export class FrameDecoder {
  private readonly maxMetaBytes: number;
  private readonly maxPayloadBytes: number;
  private chunks: Buffer[] = [];
  private headOffset = 0;
  private bufferedBytes = 0;
  private header: ParsedHeader | null = null;

  constructor(options?: { maxMetaBytes?: number; maxPayloadBytes?: number }) {
    this.maxMetaBytes = options?.maxMetaBytes ?? MAX_META_BYTES;
    this.maxPayloadBytes = options?.maxPayloadBytes ?? MAX_PAYLOAD_BYTES;
  }

  reset(): void {
    this.chunks = [];
    this.headOffset = 0;
    this.bufferedBytes = 0;
    this.header = null;
  }

  push(chunk: Uint8Array): DecodedFrame[] {
    const buf = toBuffer(chunk);
    if (buf.length > 0) {
      this.chunks.push(buf);
      this.bufferedBytes += buf.length;
    }

    const frames: DecodedFrame[] = [];
    while (true) {
      if (!this.header) {
        if (this.bufferedBytes < IPC_HEADER_BYTES) break;
        const hdr = this.consume(IPC_HEADER_BYTES);
        const magic = hdr.readUInt16BE(0);
        if (magic !== IPC_MAGIC) {
          this.reset();
          throw new IpcError('INVALID_MAGIC', `Invalid IPC magic: 0x${magic.toString(16)}`);
        }
        const version = hdr.readUInt8(2);
        if (version !== IPC_VERSION) {
          this.reset();
          throw new IpcError('UNSUPPORTED_VERSION', `Unsupported IPC version: ${version}`);
        }
        const type = hdr.readUInt8(3);
        if (!isValidFrameType(type)) {
          this.reset();
          throw new IpcError('INVALID_FRAME_TYPE', `Invalid IPC frame type: ${type}`);
        }
        const streamId = hdr.readUInt32BE(4);
        const metaLen = hdr.readUInt32BE(8);
        if (metaLen > this.maxMetaBytes) {
          this.reset();
          throw new IpcError('META_TOO_LARGE', `Frame metadata exceeds ${this.maxMetaBytes} bytes`);
        }
        const payloadLen = hdr.readUInt32BE(12);
        if (payloadLen > this.maxPayloadBytes) {
          this.reset();
          throw new IpcError(
            'PAYLOAD_TOO_LARGE',
            `Frame payload exceeds ${this.maxPayloadBytes} bytes`
          );
        }
        this.header = { type, streamId, metaLen, payloadLen };
      }

      const needed = this.header.metaLen + this.header.payloadLen;
      if (this.bufferedBytes < needed) break;

      const metaBuf = this.header.metaLen > 0 ? this.consume(this.header.metaLen) : EMPTY_BUFFER;
      const payload =
        this.header.payloadLen > 0 ? this.consume(this.header.payloadLen) : EMPTY_BUFFER;

      let meta: Record<string, unknown> | undefined;
      if (metaBuf.length > 0) {
        try {
          meta = JSON.parse(metaBuf.toString('utf8')) as Record<string, unknown>;
        } catch (error) {
          this.reset();
          throw new IpcError(
            'INVALID_META_JSON',
            'Invalid frame metadata JSON: ' + errorMessage(error)
          );
        }
      }

      frames.push({
        type: this.header.type,
        streamId: this.header.streamId,
        ...(meta !== undefined ? { meta } : {}),
        payload
      });
      this.header = null;
    }

    return frames;
  }

  private consume(n: number): Buffer {
    if (n === 0) return EMPTY_BUFFER;
    const first = this.chunks[0];
    const avail = first.length - this.headOffset;
    if (avail >= n) {
      const slice = first.subarray(this.headOffset, this.headOffset + n);
      this.headOffset += n;
      this.bufferedBytes -= n;
      if (this.headOffset === first.length) {
        this.chunks.shift();
        this.headOffset = 0;
      }
      return slice;
    }

    const out = Buffer.allocUnsafe(n);
    let copied = 0;
    while (copied < n) {
      const head = this.chunks[0];
      const headAvail = head.length - this.headOffset;
      const toCopy = Math.min(n - copied, headAvail);
      head.copy(out, copied, this.headOffset, this.headOffset + toCopy);
      copied += toCopy;
      this.headOffset += toCopy;
      if (this.headOffset === head.length) {
        this.chunks.shift();
        this.headOffset = 0;
      }
    }
    this.bufferedBytes -= n;
    return out;
  }
}

interface OutboundPending {
  resolve: (value: unknown) => void;
  reject: (error: Error) => void;
  timer?: ReturnType<typeof setTimeout>;
  streamChunks: Buffer[];
  streamBytes: number;
  streamMeta?: Record<string, unknown>;
}

interface InboundPendingRequest {
  method: string;
  params: Record<string, unknown>;
  chunks: Buffer[];
  totalBytes: number;
}

interface InboundStreamState {
  controller: AbortController;
  cancelled: boolean;
  ended: boolean;
}

export class PluginIpcServer {
  private readonly socketPath: string;
  private readonly token: string;
  private readonly onRequest?: IpcRequestHandler;
  private readonly onEvent?: (name: string, payload: unknown, rawPayload: Buffer) => void;
  private readonly onReady?: () => void;
  private readonly onClose?: () => void;
  private readonly onError?: (error: Error) => void;
  private readonly chunkBytes: number;

  private server: Server | null = null;
  private activeSocket: Socket | null = null;
  private ready = false;
  private closed = false;
  private nextStreamId = 0x80000001;
  private readonly outboundPending = new Map<number, OutboundPending>();
  private readonly inboundPendingRequests = new Map<number, InboundPendingRequest>();
  private readonly inboundStreams = new Map<number, InboundStreamState>();

  constructor(options: PluginIpcServerOptions) {
    if (!options.socketPath) throw new IpcError('PARAM_MISSING', 'socketPath is required');
    if (!options.token) throw new IpcError('PARAM_MISSING', 'token is required');
    this.socketPath = options.socketPath;
    this.token = options.token;
    this.onRequest = options.onRequest;
    this.onEvent = options.onEvent;
    this.onReady = options.onReady;
    this.onClose = options.onClose;
    this.onError = options.onError;
    this.chunkBytes =
      options.chunkBytes && options.chunkBytes > 0
        ? Math.min(options.chunkBytes, MAX_PAYLOAD_BYTES)
        : DEFAULT_CHUNK_BYTES;
  }

  get isReady(): boolean {
    return this.ready && this.activeSocket !== null && !this.activeSocket.destroyed;
  }

  async start(): Promise<void> {
    if (this.server) return;
    this.closed = false;

    fs.mkdirSync(path.dirname(this.socketPath), { recursive: true });
    try {
      fs.unlinkSync(this.socketPath);
    } catch (error) {
      const code = (error as { code?: unknown } | null)?.code;
      if (code !== 'ENOENT') throw error;
    }

    const server = createServer((socket) => this.handleConnection(socket));
    this.server = server;

    await new Promise<void>((resolve, reject) => {
      const onErr = (err: Error) => {
        server.off('listening', onListen);
        reject(err);
      };
      const onListen = () => {
        server.off('error', onErr);
        try {
          fs.chmodSync(this.socketPath, 0o600);
          resolve();
        } catch (err) {
          reject(err);
        }
      };
      server.once('error', onErr);
      server.once('listening', onListen);
      server.listen(this.socketPath);
    });

    server.on('error', (error) => {
      this.onError?.(error);
    });
  }

  async close(): Promise<void> {
    if (this.closed && !this.server) return;
    this.closed = true;
    this.ready = false;

    for (const entry of this.inboundStreams.values()) {
      entry.cancelled = true;
      entry.controller.abort();
    }
    this.inboundStreams.clear();
    this.rejectAllOutbound(new IpcError('IPC_CLOSED', 'IPC server closed'));

    if (this.activeSocket) {
      const sock = this.activeSocket;
      this.activeSocket = null;
      sock.destroy();
    }

    const server = this.server;
    this.server = null;
    if (server) {
      await new Promise<void>((resolve) => {
        server.close(() => resolve());
      });
    }

    try {
      fs.unlinkSync(this.socketPath);
    } catch (error) {
      const code = (error as { code?: unknown } | null)?.code;
      if (code !== 'ENOENT') {
        // Ignore cleanup errors on already-removed socket paths.
      }
    }
  }

  sendRequest<T = unknown>(
    method: string,
    params?: Record<string, unknown>,
    payload?: Uint8Array | null,
    timeoutMs?: number
  ): Promise<T> {
    const socket = this.activeSocket;
    if (!this.isReady || !socket) {
      return Promise.reject(new IpcError('IPC_NOT_READY', 'IPC channel is not ready'));
    }

    const streamId = this.allocateStreamId();
    const payloadBuf = payload == null ? EMPTY_BUFFER : toBuffer(payload);
    const chunkedPayload = payloadBuf.length > this.chunkBytes;

    let reqFrame: Buffer;
    try {
      reqFrame = encodeFrame({
        type: FrameType.REQ,
        streamId,
        meta: chunkedPayload
          ? { method, params: params ?? {}, streamed: true }
          : { method, params: params ?? {} },
        payload: chunkedPayload ? EMPTY_BUFFER : payloadBuf
      });
    } catch (error) {
      return Promise.reject(error instanceof Error ? error : new Error(errorMessage(error)));
    }

    return new Promise<T>((resolve, reject) => {
      let timer: ReturnType<typeof setTimeout> | undefined;
      if (timeoutMs !== undefined && timeoutMs > 0) {
        timer = setTimeout(() => {
          const pending = this.outboundPending.get(streamId);
          if (!pending) return;
          this.outboundPending.delete(streamId);
          reject(new IpcError('IPC_TIMEOUT', `IPC request ${method} timed out after ${timeoutMs}ms`));
        }, timeoutMs);
        timer.unref?.();
      }

      this.outboundPending.set(streamId, {
        resolve: resolve as (value: unknown) => void,
        reject,
        timer,
        streamChunks: [],
        streamBytes: 0
      });

      try {
        socket.write(reqFrame);
        if (chunkedPayload) {
          void this.sendOutboundPayloadChunks(socket, streamId, payloadBuf);
        }
      } catch (error) {
        const pending = this.outboundPending.get(streamId);
        if (pending) {
          this.outboundPending.delete(streamId);
          if (pending.timer) clearTimeout(pending.timer);
          reject(error instanceof Error ? error : new Error(errorMessage(error)));
        }
      }
    });
  }

  private async sendOutboundPayloadChunks(
    socket: Socket,
    streamId: number,
    payload: Buffer
  ): Promise<void> {
    try {
      let offset = 0;
      while (offset < payload.length) {
        if (socket.destroyed || this.activeSocket !== socket || !this.outboundPending.has(streamId)) {
          return;
        }
        const end = Math.min(offset + this.chunkBytes, payload.length);
        await this.writeFrameWithYield(socket, {
          type: FrameType.STREAM_DATA,
          streamId,
          payload: payload.subarray(offset, end)
        });
        offset = end;
      }
      if (!socket.destroyed && this.activeSocket === socket && this.outboundPending.has(streamId)) {
        this.writeFrameImmediate(socket, {
          type: FrameType.STREAM_END,
          streamId
        });
      }
    } catch (error) {
      const pending = this.outboundPending.get(streamId);
      if (pending) {
        this.outboundPending.delete(streamId);
        if (pending.timer) clearTimeout(pending.timer);
        pending.reject(error instanceof Error ? error : new Error(errorMessage(error)));
      }
    }
  }

  sendEvent(name: string, payloadJson: unknown = null, rawPayload?: Uint8Array | null): boolean {
    const socket = this.activeSocket;
    if (!this.isReady || !socket) return false;

    const encoded = encodeFrame({
      type: FrameType.EVENT,
      streamId: 0,
      meta: { name, payload: payloadJson === undefined ? null : payloadJson },
      payload: rawPayload
    });
    return socket.write(encoded);
  }

  private allocateStreamId(): number {
    // Allocate >= 0x80000001 so outbound IDs never collide with low peer-initiated stream IDs.
    const id = this.nextStreamId;
    this.nextStreamId = this.nextStreamId >= 0xffffffff ? 0x80000001 : this.nextStreamId + 1;
    return id;
  }

  private handleConnection(socket: Socket): void {
    if (this.closed) {
      socket.destroy();
      return;
    }

    const decoder = new FrameDecoder();
    let authenticated = false;

    socket.on('data', (chunk) => {
      let frames: DecodedFrame[];
      try {
        frames = decoder.push(chunk);
      } catch (error) {
        this.onError?.(error instanceof Error ? error : new Error(errorMessage(error)));
        socket.destroy();
        return;
      }

      for (const frame of frames) {
        if (!authenticated) {
          if (frame.type !== FrameType.PING || !tokensEqual(frame.meta?.token, this.token)) {
            socket.destroy();
            return;
          }
          authenticated = true;
          const previous = this.activeSocket;
          if (previous && previous !== socket) {
            this.cleanupActiveSocket(previous, new IpcError('IPC_REPLACED', 'IPC connection replaced'));
            previous.destroy();
          }
          this.activeSocket = socket;
          this.ready = true;
          this.writeFrameImmediate(socket, {
            type: FrameType.PONG,
            streamId: frame.streamId,
            meta: { ok: true }
          });
          this.onReady?.();
          continue;
        }

        this.dispatchFrame(socket, frame);
      }
    });

    socket.on('error', (error) => {
      this.onError?.(error);
    });

    socket.on('close', () => {
      if (this.activeSocket === socket) {
        this.cleanupActiveSocket(socket, new IpcError('IPC_CLOSED', 'IPC connection closed'));
        this.onClose?.();
      }
    });
  }

  private cleanupActiveSocket(socket: Socket, reason: Error): void {
    if (this.activeSocket !== socket) return;
    this.ready = false;
    this.activeSocket = null;
    this.inboundPendingRequests.clear();
    for (const entry of this.inboundStreams.values()) {
      entry.cancelled = true;
      entry.controller.abort();
    }
    this.inboundStreams.clear();
    this.rejectAllOutbound(reason);
  }

  private rejectAllOutbound(reason: Error): void {
    for (const [id, entry] of this.outboundPending) {
      this.outboundPending.delete(id);
      if (entry.timer) clearTimeout(entry.timer);
      entry.reject(reason);
    }
  }

  private dispatchFrame(socket: Socket, frame: DecodedFrame): void {
    switch (frame.type) {
      case FrameType.PING:
        this.writeFrameImmediate(socket, {
          type: FrameType.PONG,
          streamId: frame.streamId,
          meta: frame.meta ?? { ok: true },
          payload: frame.payload.length > 0 ? frame.payload : undefined
        });
        break;
      case FrameType.PONG:
        break;
      case FrameType.REQ:
        this.receiveInboundRequestFrame(socket, frame);
        break;
      case FrameType.RESP:
        this.handleOutboundFrame(frame);
        break;
      case FrameType.ERR:
        if (this.outboundPending.has(frame.streamId)) {
          this.handleOutboundFrame(frame);
        } else {
          this.inboundPendingRequests.delete(frame.streamId);
          this.cancelInboundStream(socket, frame.streamId);
        }
        break;
      case FrameType.STREAM_DATA:
        if (this.outboundPending.has(frame.streamId)) {
          this.handleOutboundFrame(frame);
        } else {
          this.receiveInboundRequestData(socket, frame);
        }
        break;
      case FrameType.STREAM_END:
        if (this.outboundPending.has(frame.streamId)) {
          this.handleOutboundFrame(frame);
        } else if (this.inboundPendingRequests.has(frame.streamId)) {
          this.finishInboundPendingRequest(socket, frame);
        } else {
          this.cancelInboundStream(socket, frame.streamId);
        }
        break;
      case FrameType.EVENT: {
        const name = typeof frame.meta?.name === 'string' ? frame.meta.name : '';
        if (name) {
          this.onEvent?.(name, frame.meta?.payload, frame.payload);
        }
        break;
      }
    }
  }

  private handleOutboundFrame(frame: DecodedFrame): void {
    const pending = this.outboundPending.get(frame.streamId);
    if (!pending) return;

    if (frame.type === FrameType.RESP) {
      if (frame.meta?.streamed === true) {
        pending.streamMeta = frame.meta;
        if (frame.payload.length > 0) {
          pending.streamChunks.push(frame.payload);
          pending.streamBytes += frame.payload.length;
        }
        return;
      }
      this.outboundPending.delete(frame.streamId);
      if (pending.timer) clearTimeout(pending.timer);
      this.resolveOutboundResult(pending, frame.meta, frame.payload);
      return;
    }

    if (frame.type === FrameType.ERR) {
      this.outboundPending.delete(frame.streamId);
      if (pending.timer) clearTimeout(pending.timer);
      const code = typeof frame.meta?.code === 'string' && frame.meta.code ? frame.meta.code : 'HOST_ERROR';
      const message =
        typeof frame.meta?.message === 'string' && frame.meta.message
          ? frame.meta.message
          : 'IPC request failed';
      pending.reject(new IpcError(code, message));
      return;
    }

    if (frame.type === FrameType.STREAM_DATA) {
      if (frame.payload.length > 0) {
        if (pending.streamBytes + frame.payload.length > MAX_PAYLOAD_BYTES) {
          this.outboundPending.delete(frame.streamId);
          if (pending.timer) clearTimeout(pending.timer);
          pending.reject(
            new IpcError('PAYLOAD_TOO_LARGE', `Streamed response exceeds ${MAX_PAYLOAD_BYTES} bytes`)
          );
          return;
        }
        pending.streamChunks.push(frame.payload);
        pending.streamBytes += frame.payload.length;
      }
      if (frame.meta) {
        pending.streamMeta = { ...(pending.streamMeta ?? {}), ...frame.meta };
      }
      return;
    }

    if (frame.type === FrameType.STREAM_END) {
      this.outboundPending.delete(frame.streamId);
      if (pending.timer) clearTimeout(pending.timer);
      if (frame.meta && isPlainObject(frame.meta.error)) {
        const errObj = frame.meta.error;
        const code = typeof errObj.code === 'string' && errObj.code ? errObj.code : 'HOST_ERROR';
        const message = typeof errObj.message === 'string' && errObj.message ? errObj.message : 'Stream failed';
        pending.reject(new IpcError(code, message));
        return;
      }

      if (frame.payload.length > 0) {
        if (pending.streamBytes + frame.payload.length > MAX_PAYLOAD_BYTES) {
          pending.reject(
            new IpcError('PAYLOAD_TOO_LARGE', `Streamed response exceeds ${MAX_PAYLOAD_BYTES} bytes`)
          );
          return;
        }
        pending.streamChunks.push(frame.payload);
      }
      const payload =
        pending.streamChunks.length > 0 ? Buffer.concat(pending.streamChunks) : EMPTY_BUFFER;
      const mergedMeta =
        pending.streamMeta || frame.meta
          ? { ...(pending.streamMeta ?? {}), ...(frame.meta ?? {}) }
          : undefined;
      this.resolveOutboundResult(pending, mergedMeta, payload);
    }
  }

  private resolveOutboundResult(
    pending: OutboundPending,
    meta: Record<string, unknown> | undefined,
    payload: Buffer
  ): void {
    if (meta && Object.prototype.hasOwnProperty.call(meta, 'result')) {
      pending.resolve(meta.result);
      return;
    }
    if (meta?.encoding === 'json' && payload.length > 0) {
      try {
        pending.resolve(JSON.parse(payload.toString('utf8')));
      } catch (error) {
        pending.reject(
          new IpcError('INVALID_RESPONSE_JSON', 'Invalid JSON payload: ' + errorMessage(error))
        );
      }
      return;
    }
    if (payload.length > 0) {
      pending.resolve({ meta: meta ?? {}, payload });
      return;
    }
    pending.resolve(meta ?? null);
  }

  private cancelInboundStream(socket: Socket, streamId: number): void {
    const state = this.inboundStreams.get(streamId);
    if (!state) return;
    state.cancelled = true;
    state.controller.abort();
    this.sendStreamEndOnce(socket, streamId, state, { cancelled: true });
  }

  private sendStreamEndOnce(
    socket: Socket,
    streamId: number,
    state: InboundStreamState,
    meta: Record<string, unknown>
  ): void {
    if (state.ended || socket.destroyed || this.activeSocket !== socket) return;
    state.ended = true;
    this.writeFrameImmediate(socket, {
      type: FrameType.STREAM_END,
      streamId,
      meta
    });
  }

  private receiveInboundRequestFrame(socket: Socket, frame: DecodedFrame): void {
    const method = typeof frame.meta?.method === 'string' ? frame.meta.method : '';
    const params = isPlainObject(frame.meta?.args)
      ? frame.meta!.args
      : isPlainObject(frame.meta?.params)
        ? frame.meta!.params
        : {};

    if (frame.meta?.streamed === true) {
      const chunks = frame.payload.length > 0 ? [frame.payload] : [];
      this.inboundPendingRequests.set(frame.streamId, {
        method,
        params,
        chunks,
        totalBytes: frame.payload.length
      });
      return;
    }

    void this.handleInboundRequest(socket, frame.streamId, method, params, frame.payload);
  }

  private receiveInboundRequestData(socket: Socket, frame: DecodedFrame): void {
    const acc = this.inboundPendingRequests.get(frame.streamId);
    if (!acc) return;
    if (frame.payload.length > 0) {
      if (acc.totalBytes + frame.payload.length > MAX_PAYLOAD_BYTES) {
        this.inboundPendingRequests.delete(frame.streamId);
        this.writeFrameImmediate(socket, {
          type: FrameType.ERR,
          streamId: frame.streamId,
          meta: { code: 'PAYLOAD_TOO_LARGE', message: `Request payload exceeds ${MAX_PAYLOAD_BYTES} bytes` }
        });
        return;
      }
      acc.chunks.push(frame.payload);
      acc.totalBytes += frame.payload.length;
    }
  }

  private finishInboundPendingRequest(socket: Socket, frame: DecodedFrame): void {
    const acc = this.inboundPendingRequests.get(frame.streamId);
    if (!acc) return;
    this.inboundPendingRequests.delete(frame.streamId);
    if (frame.meta?.cancelled === true) return;

    if (frame.payload.length > 0) {
      if (acc.totalBytes + frame.payload.length > MAX_PAYLOAD_BYTES) {
        this.writeFrameImmediate(socket, {
          type: FrameType.ERR,
          streamId: frame.streamId,
          meta: { code: 'PAYLOAD_TOO_LARGE', message: `Request payload exceeds ${MAX_PAYLOAD_BYTES} bytes` }
        });
        return;
      }
      acc.chunks.push(frame.payload);
    }

    const fullPayload = acc.chunks.length > 0 ? Buffer.concat(acc.chunks) : EMPTY_BUFFER;
    void this.handleInboundRequest(socket, frame.streamId, acc.method, acc.params, fullPayload);
  }

  private async handleInboundRequest(
    socket: Socket,
    streamId: number,
    method: string,
    params: Record<string, unknown>,
    payload: Buffer
  ): Promise<void> {
    if (!this.onRequest) {
      this.writeFrameImmediate(socket, {
        type: FrameType.ERR,
        streamId,
        meta: { code: 'METHOD_UNKNOWN', message: method }
      });
      return;
    }

    const controller = new AbortController();
    const state: InboundStreamState = { controller, cancelled: false, ended: false };
    this.inboundStreams.set(streamId, state);

    try {
      const result = await this.onRequest(method, params, {
        signal: controller.signal,
        payload,
        streamId,
        method
      });

      if (state.cancelled || socket.destroyed || this.activeSocket !== socket) {
        if (isAsyncIterable(result)) {
          const iter = result[Symbol.asyncIterator]();
          await iter.return?.(undefined).catch(() => undefined);
        }
        return;
      }

      if (isAsyncIterable(result)) {
        await this.sendAsyncIterable(socket, streamId, state, result);
        return;
      }

      if (result instanceof Uint8Array) {
        await this.sendBinaryResult(socket, streamId, state, { payload: result });
        return;
      }

      if (isBinaryResult(result)) {
        await this.sendBinaryResult(socket, streamId, state, result);
        return;
      }

      const normalized = result === undefined ? null : result;
      const respMeta = { result: normalized };
      const jsonSize = Buffer.byteLength(JSON.stringify(respMeta), 'utf8');
      if (jsonSize <= this.chunkBytes) {
        this.writeFrameImmediate(socket, {
          type: FrameType.RESP,
          streamId,
          meta: respMeta
        });
      } else {
        const jsonPayload = Buffer.from(JSON.stringify(normalized), 'utf8');
        await this.sendBinaryResult(socket, streamId, state, {
          meta: { encoding: 'json' },
          payload: jsonPayload
        });
      }
    } catch (error) {
      if (!state.cancelled && !socket.destroyed && this.activeSocket === socket) {
        this.writeFrameImmediate(socket, {
          type: FrameType.ERR,
          streamId,
          meta: { code: errorCode(error), message: errorMessage(error) }
        });
        state.ended = true;
      }
    } finally {
      this.inboundStreams.delete(streamId);
    }
  }

  private async sendBinaryResult(
    socket: Socket,
    streamId: number,
    state: InboundStreamState,
    result: BinaryResult
  ): Promise<void> {
    const payloadBuf = toBuffer(result.payload);
    if (payloadBuf.length <= this.chunkBytes) {
      this.writeFrameImmediate(socket, {
        type: FrameType.RESP,
        streamId,
        meta: result.meta ?? {},
        payload: payloadBuf
      });
      return;
    }

    this.writeFrameImmediate(socket, {
      type: FrameType.RESP,
      streamId,
      meta: { ...(result.meta ?? {}), streamed: true }
    });
    await this.sendChunkedData(socket, streamId, state, payloadBuf, undefined);
    if (!state.cancelled) {
      this.sendStreamEndOnce(socket, streamId, state, { ok: true });
    }
  }

  private async sendAsyncIterable(
    socket: Socket,
    streamId: number,
    state: InboundStreamState,
    iterable: AsyncIterable<unknown>
  ): Promise<void> {
    const iterator = iterable[Symbol.asyncIterator]();
    const signal = state.controller.signal;

    try {
      while (!state.cancelled && !socket.destroyed && this.activeSocket === socket) {
        const nextStep = await raceWithAbort(iterator.next(), signal);
        if (nextStep.aborted) {
          await iterator.return?.(undefined).catch(() => undefined);
          return;
        }
        const item = nextStep.value;
        if (item.done) {
          const endMeta =
            item.value !== undefined ? { ok: true, result: item.value } : { ok: true };
          this.sendStreamEndOnce(socket, streamId, state, endMeta);
          return;
        }

        const chunk = item.value;
        if (chunk instanceof Uint8Array) {
          await this.sendChunkedData(socket, streamId, state, toBuffer(chunk), undefined);
        } else if (isPlainObject(chunk)) {
          const keys = Object.keys(chunk);
          const isStreamChunkWrapper =
            keys.length > 0 &&
            keys.every((k) => k === 'meta' || k === 'payload') &&
            (chunk.payload === undefined ||
              chunk.payload === null ||
              chunk.payload instanceof Uint8Array) &&
            (chunk.meta === undefined || isPlainObject(chunk.meta));
          if (isStreamChunkWrapper) {
            if (chunk.payload instanceof Uint8Array && chunk.payload.byteLength > 0) {
              await this.sendChunkedData(
                socket,
                streamId,
                state,
                toBuffer(chunk.payload),
                chunk.meta as Record<string, unknown> | undefined
              );
            } else {
              await this.writeFrameWithYield(socket, {
                type: FrameType.STREAM_DATA,
                streamId,
                meta: chunk.meta as Record<string, unknown> | undefined
              });
            }
          } else {
            await this.writeFrameWithYield(socket, {
              type: FrameType.STREAM_DATA,
              streamId,
              meta: chunk
            });
          }
        } else if (chunk !== undefined) {
          await this.writeFrameWithYield(socket, {
            type: FrameType.STREAM_DATA,
            streamId,
            meta: { value: chunk }
          });
        }
      }
    } catch (error) {
      await iterator.return?.(undefined).catch(() => undefined);
      throw error;
    }
  }

  private async sendChunkedData(
    socket: Socket,
    streamId: number,
    state: InboundStreamState,
    payload: Buffer,
    meta: Record<string, unknown> | undefined
  ): Promise<void> {
    if (payload.length === 0) {
      await this.writeFrameWithYield(socket, {
        type: FrameType.STREAM_DATA,
        streamId,
        meta,
        payload: EMPTY_BUFFER
      });
      return;
    }

    let offset = 0;
    let first = true;
    while (offset < payload.length) {
      if (state.cancelled || socket.destroyed || this.activeSocket !== socket) return;
      const end = Math.min(offset + this.chunkBytes, payload.length);
      const slice = payload.subarray(offset, end);
      await this.writeFrameWithYield(socket, {
        type: FrameType.STREAM_DATA,
        streamId,
        meta: first ? meta : undefined,
        payload: slice
      });
      first = false;
      offset = end;
    }
  }

  private writeFrameImmediate(socket: Socket, frame: IpcFrame): void {
    if (socket.destroyed) return;
    socket.write(encodeFrame(frame));
  }

  private async writeFrameWithYield(socket: Socket, frame: IpcFrame): Promise<void> {
    if (socket.destroyed || this.activeSocket !== socket) return;
    const ok = socket.write(encodeFrame(frame));
    if (!ok) {
      await waitForDrainOrClose(socket);
    } else {
      // Yield once so concurrent requests and incoming STREAM_END cancellations are processed promptly.
      await new Promise<void>((resolve) => setImmediate(resolve));
    }
  }
}

function waitForDrainOrClose(socket: Socket): Promise<void> {
  if (socket.destroyed) return Promise.resolve();
  return new Promise<void>((resolve) => {
    const done = () => {
      socket.off('drain', done);
      socket.off('close', done);
      socket.off('error', done);
      resolve();
    };
    socket.once('drain', done);
    socket.once('close', done);
    socket.once('error', done);
  });
}

function raceWithAbort<T>(
  promise: Promise<T>,
  signal: AbortSignal
): Promise<{ aborted: true } | { aborted: false; value: T }> {
  if (signal.aborted) {
    return Promise.resolve({ aborted: true });
  }
  return new Promise((resolve, reject) => {
    const onAbort = () => {
      resolve({ aborted: true });
    };
    signal.addEventListener('abort', onAbort, { once: true });
    promise.then(
      (value) => {
        signal.removeEventListener('abort', onAbort);
        resolve({ aborted: false, value });
      },
      (error) => {
        signal.removeEventListener('abort', onAbort);
        if (signal.aborted) resolve({ aborted: true });
        else reject(error);
      }
    );
  });
}

function defaultMergePayload(previous: unknown, incoming: unknown): unknown {
  if (typeof previous === 'string' && typeof incoming === 'string') {
    return previous + incoming;
  }
  if (isPlainObject(previous) && isPlainObject(incoming)) {
    for (const field of TEXT_MERGE_FIELDS) {
      const prevVal = previous[field];
      const nextVal = incoming[field];
      if (typeof prevVal === 'string' && typeof nextVal === 'string') {
        return { ...previous, ...incoming, [field]: prevVal + nextVal };
      }
    }
    return { ...previous, ...incoming };
  }
  return incoming;
}

export class CoalescedEventSender {
  private readonly windowMs: number;
  private readonly send: (name: string, payload: unknown, rawPayload?: Uint8Array) => void;
  private readonly merge: (previous: unknown, incoming: unknown, key: string) => unknown;
  private readonly buffer = new Map<string, { name: string; payload: unknown }>();
  private timer: ReturnType<typeof setTimeout> | undefined;

  constructor(options: CoalescedEventSenderOptions) {
    this.windowMs = options.windowMs ?? 16;
    this.send = options.send;
    this.merge = options.merge ?? ((prev, next) => defaultMergePayload(prev, next));
  }

  get pendingSize(): number {
    return this.buffer.size;
  }

  push(name: string, payload: unknown, keyOrOptions?: string | CoalescedPushOptions): void {
    const key = typeof keyOrOptions === 'string' ? keyOrOptions : keyOrOptions?.key;
    const rawPayload =
      typeof keyOrOptions === 'object' && keyOrOptions !== null ? keyOrOptions.rawPayload : undefined;

    if (!key || rawPayload !== undefined || this.windowMs <= 0) {
      this.flush();
      this.send(name, payload, rawPayload);
      return;
    }

    const existing = this.buffer.get(key);
    if (existing) {
      if (existing.name !== name) {
        this.flush(key);
        this.buffer.set(key, { name, payload });
      } else {
        existing.payload = this.merge(existing.payload, payload, key);
      }
    } else {
      this.buffer.set(key, { name, payload });
    }

    if (!this.timer) {
      this.timer = setTimeout(() => {
        this.timer = undefined;
        this.flush();
      }, this.windowMs);
      this.timer.unref?.();
    }
  }

  flush(key?: string): void {
    if (key !== undefined) {
      const entry = this.buffer.get(key);
      if (!entry) return;
      this.buffer.delete(key);
      if (this.buffer.size === 0 && this.timer) {
        clearTimeout(this.timer);
        this.timer = undefined;
      }
      this.send(entry.name, entry.payload);
      return;
    }

    if (this.timer) {
      clearTimeout(this.timer);
      this.timer = undefined;
    }
    if (this.buffer.size === 0) return;

    const entries = Array.from(this.buffer.values());
    this.buffer.clear();
    for (const entry of entries) {
      this.send(entry.name, entry.payload);
    }
  }

  dispose(flushRemaining = true): void {
    if (flushRemaining) {
      this.flush();
    } else {
      if (this.timer) {
        clearTimeout(this.timer);
        this.timer = undefined;
      }
      this.buffer.clear();
    }
  }
}
