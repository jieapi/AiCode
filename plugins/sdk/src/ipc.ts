import { timingSafeEqual } from 'node:crypto';
import * as fs from 'node:fs';
import { createServer, Server, Socket } from 'node:net';
import * as path from 'node:path';

export const IPC_MAGIC = 0x4143;
export const IPC_VERSION = 1;
export const IPC_HEADER_BYTES = 16;
export const MAX_META_BYTES = 1024 * 1024;
export const MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;
export const STREAM_WINDOW_BYTES = 256 * 1024;
export const DEFAULT_CHUNK_BYTES = 64 * 1024;

const MIN_STREAM_FRAME_COST = 4 * 1024;
const MAX_CALLS = 8;
const BUFFER_BUDGET = 48 * 1024 * 1024;
const DEFAULT_REQUEST_TIMEOUT_MS = 120_000;
const MAX_REQUEST_TIMEOUT_MS = 1_800_000;
const WRITE_TIMEOUT_MS = 10_000;

export const FrameType = {
  REQ: 1,
  RESP: 2,
  ERR: 3,
  STREAM_DATA: 4,
  STREAM_END: 5,
  EVENT: 6,
  PING: 7,
  PONG: 8,
  WINDOW_UPDATE: 9
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
  payloadStream?: AsyncIterable<Uint8Array>;
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
  return Number.isInteger(type) && type >= FrameType.REQ && type <= FrameType.WINDOW_UPDATE;
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

const frameMetadataSizes = new WeakMap<DecodedFrame, number>();

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
          const decoded: unknown = JSON.parse(metaBuf.toString('utf8'));
          if (!isPlainObject(decoded)) throw new Error('Metadata must be a JSON object');
          meta = decoded;
        } catch (error) {
          this.reset();
          throw new IpcError(
            'INVALID_META_JSON',
            'Invalid frame metadata JSON: ' + errorMessage(error)
          );
        }
      }

      const frame = {
        type: this.header.type,
        streamId: this.header.streamId,
        ...(meta !== undefined ? { meta } : {}),
        payload
      };
      frameMetadataSizes.set(frame, metaBuf.length);
      frames.push(frame);
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
  timer: ReturnType<typeof setTimeout>;
  chunks: Buffer[];
  bytes: number;
  reserved: number;
  meta?: Record<string, unknown>;
  sent: boolean;
}

interface InboundCall {
  method: string;
  params: Record<string, unknown>;
  controller: AbortController;
  timer: ReturnType<typeof setTimeout>;
  responseStream: boolean;
  running: boolean;
  reserved: number;
  fragments?: Buffer[];
  fragmentBytes: number;
  upload?: UploadBody;
  credit: number;
  wakeCredit?: () => void;
}

interface WritePacket {
  bytes: Buffer;
  resolve?: () => void;
  reject?: (error: Error) => void;
  live?: () => boolean;
}

const MAX_WRITE_BYTES = 4 * 1024 * 1024;
const MAX_EVENT_WRITE_BYTES = 2 * 1024 * 1024;

function frameCost(bytes: number): number {
  return Math.max(MIN_STREAM_FRAME_COST, bytes);
}

function limitError(): IpcError {
  return new IpcError('IPC_LIMIT', 'IPC concurrency or buffer budget exceeded');
}

function terminalError(meta?: Record<string, unknown>): IpcError | undefined {
  const result = isPlainObject(meta?.result) ? meta.result : undefined;
  const statuses = [meta?.status, result?.status, result?.type];
  if (meta?.cancelled === true || meta?.cancel === true || result?.cancelled === true ||
      statuses.some((status) => status === 'cancelled' || status === 'canceled' || status === 'aborted')) {
    return new IpcError('IPC_CANCELLED', 'IPC stream cancelled');
  }
  const error = meta?.error ?? result?.error;
  if (error != null || meta?.ok === false || result?.ok === false || statuses.some((status) => status === 'failed' || status === 'error')) {
    const detail = isPlainObject(error) ? error : result ?? meta;
    return new IpcError(typeof detail?.code === 'string' ? detail.code : 'IPC_STREAM_FAILED',
      typeof detail?.message === 'string' ? detail.message : typeof error === 'string' ? error : 'IPC stream failed');
  }
}

class UploadBody implements AsyncIterable<Uint8Array> {
  private queue: Array<{ payload: Buffer; cost: number }> = [];
  private credit = STREAM_WINDOW_BYTES;
  private wake?: () => void;
  private ended = false;
  private error?: Error;
  private claimed = false;
  consumed = false;

  constructor(private readonly update: (cost: number) => void) {}

  push(frame: DecodedFrame): void {
    if (this.ended || frame.meta !== undefined || frame.payload.length === 0) {
      throw new IpcError('IPC_PROTOCOL_ERROR', 'Invalid upload data frame');
    }
    const cost = frameCost(IPC_HEADER_BYTES + frame.payload.length);
    if (cost > this.credit) throw limitError();
    this.credit -= cost;
    this.queue.push({ payload: frame.payload, cost });
    this.wake?.();
  }

  finish(error?: Error): void {
    this.ended = true;
    if (error) {
      this.error = error;
      this.queue = [];
    }
    this.wake?.();
  }

  async *[Symbol.asyncIterator](): AsyncGenerator<Uint8Array> {
    if (this.claimed) throw new IpcError('IPC_PROTOCOL_ERROR', 'Upload body can only be consumed once');
    this.claimed = true;
    while (true) {
      if (this.error) throw this.error;
      const entry = this.queue.shift();
      if (entry) {
        yield entry.payload;
        this.credit += entry.cost;
        if (!this.ended) this.update(entry.cost);
      } else if (this.ended) {
        this.consumed = true;
        return;
      } else {
        await new Promise<void>((resolve) => { this.wake = resolve; });
        this.wake = undefined;
      }
    }
  }
}

class SocketWriter {
  private readonly control: WritePacket[] = [];
  private readonly data: WritePacket[] = [];
  private current?: WritePacket;
  private timer?: ReturnType<typeof setTimeout>;
  private bytes = 0;
  private closed?: Error;

  constructor(private readonly socket: Socket) {}

  send(bytes: Buffer, data: boolean, live?: () => boolean): Promise<void> {
    return new Promise((resolve, reject) => this.enqueue({ bytes, resolve, reject, live }, data));
  }

  immediate(bytes: Buffer, event = false): void {
    if (event && this.bytes + bytes.length > MAX_EVENT_WRITE_BYTES) throw limitError();
    this.enqueue({ bytes }, false);
  }

  private enqueue(packet: WritePacket, data: boolean): void {
    if (this.closed || this.socket.destroyed) throw this.closed ?? new IpcError('IPC_CLOSED', 'IPC socket closed');
    if (this.control.length + this.data.length >= 64 || this.bytes + packet.bytes.length > MAX_WRITE_BYTES) {
      throw limitError();
    }
    this.bytes += packet.bytes.length;
    (data ? this.data : this.control).push(packet);
    this.pump();
  }

  private pump(): void {
    if (this.closed || this.current) return;
    while (true) {
      const packet = this.control.shift() ?? this.data.shift();
      if (!packet) return;
      if (packet.live && !packet.live()) {
        this.bytes -= packet.bytes.length;
        packet.reject?.(new IpcError('IPC_CANCELLED', 'IPC write cancelled'));
        continue;
      }
      this.current = packet;
      this.timer = setTimeout(() => this.socket.destroy(new IpcError('IPC_TIMEOUT', 'IPC socket write timed out')), WRITE_TIMEOUT_MS);
      this.timer.unref?.();
      this.socket.write(packet.bytes, (error) => {
        if (this.current !== packet) return;
        clearTimeout(this.timer);
        this.current = undefined;
        this.bytes -= packet.bytes.length;
        if (error) {
          packet.reject?.(error);
          this.socket.destroy(error);
        } else {
          packet.resolve?.();
          this.pump();
        }
      });
      return;
    }
  }

  close(error: Error): void {
    this.closed = error;
    clearTimeout(this.timer);
    this.current?.reject?.(error);
    this.current = undefined;
    for (const packet of [...this.control.splice(0), ...this.data.splice(0)]) packet.reject?.(error);
    this.bytes = 0;
  }
}

export class PluginIpcServer {
  private readonly chunkBytes: number;
  private server: Server | null = null;
  private activeSocket: Socket | null = null;
  private readonly sockets = new Map<Socket, SocketWriter>();
  private closed = false;
  private windowNegotiated = false;
  private nextStreamId = 0x80000001;
  private bufferedBytes = 0;
  private runningHandlers = 0;
  private readonly outbound = new Map<number, OutboundPending>();
  private readonly inbound = new Map<number, InboundCall>();

  constructor(private readonly options: PluginIpcServerOptions) {
    if (!options.socketPath) throw new IpcError('PARAM_MISSING', 'socketPath is required');
    if (!options.token) throw new IpcError('PARAM_MISSING', 'token is required');
    this.chunkBytes = Math.max(1, Math.min(DEFAULT_CHUNK_BYTES, Math.floor(options.chunkBytes || DEFAULT_CHUNK_BYTES)));
  }

  get isReady(): boolean {
    return this.activeSocket !== null && !this.activeSocket.destroyed;
  }

  get activeRequests(): number {
    return this.outbound.size + this.inbound.size;
  }

  async start(): Promise<void> {
    if (this.server) return;
    this.closed = false;
    const { socketPath } = this.options;
    fs.mkdirSync(path.dirname(socketPath), { recursive: true });
    try {
      fs.unlinkSync(socketPath);
    } catch (error) {
      if (errorCode(error) !== 'ENOENT') throw error;
    }
    const server = createServer((socket) => this.handleConnection(socket));
    this.server = server;
    await new Promise<void>((resolve, reject) => {
      const onError = (error: Error) => { server.off('listening', onListen); reject(error); };
      const onListen = () => {
        server.off('error', onError);
        try { fs.chmodSync(socketPath, 0o600); resolve(); } catch (error) { reject(error); }
      };
      server.once('error', onError);
      server.once('listening', onListen);
      server.listen(socketPath);
    });
    server.on('error', (error) => this.options.onError?.(error));
  }

  async close(): Promise<void> {
    this.closed = true;
    if (this.activeSocket) this.disconnect(this.activeSocket, new IpcError('IPC_CLOSED', 'IPC server closed'));
    for (const socket of this.sockets.keys()) socket.destroy();
    const server = this.server;
    this.server = null;
    if (server) await new Promise<void>((resolve) => server.close(() => resolve()));
  }

  async sendRequest<T = unknown>(
    method: string,
    params?: Record<string, unknown>,
    payload?: Uint8Array | null,
    timeoutMs = DEFAULT_REQUEST_TIMEOUT_MS
  ): Promise<T> {
    const socket = this.activeSocket;
    if (!socket || socket.destroyed) throw new IpcError('IPC_NOT_READY', 'IPC channel is not ready');
    const data = payload == null ? EMPTY_BUFFER : toBuffer(payload);
    if (data.length > MAX_PAYLOAD_BYTES) throw new IpcError('PAYLOAD_TOO_LARGE', 'RPC payload exceeds 16 MiB');
    if (this.outbound.size >= MAX_CALLS) throw limitError();
    const id = this.nextStreamId;
    this.nextStreamId = id === 0xffffffff ? 0x80000001 : id + 1;
    const fragmented = data.length > this.chunkBytes;
    const request = encodeFrame({ type: FrameType.REQ, streamId: id,
      meta: { method, params: params ?? {}, ...(fragmented ? { streamed: true } : {}) },
      payload: fragmented ? undefined : data });
    const reserved = data.length + request.length;
    this.reserve(reserved);
    const timeout = Math.max(1, Math.min(timeoutMs || DEFAULT_REQUEST_TIMEOUT_MS, MAX_REQUEST_TIMEOUT_MS));
    return new Promise<T>((resolve, reject) => {
      const timer = setTimeout(() => this.failOutbound(socket, id, new IpcError('IPC_TIMEOUT', `IPC request ${method} timed out`), true), timeout);
      timer.unref?.();
      const pending: OutboundPending = { resolve: resolve as (value: unknown) => void, reject, timer,
        chunks: [], bytes: 0, reserved, sent: false };
      this.outbound.set(id, pending);
      const live = () => this.outbound.get(id) === pending;
      void (async () => {
        await this.writer(socket).send(request, false, () => {
          if (!live()) return false;
          pending.sent = true;
          return true;
        });
        if (!fragmented) return;
        for (let offset = 0; live() && offset < data.length; offset += this.chunkBytes) {
          await this.send(socket, { type: FrameType.STREAM_DATA, streamId: id,
            payload: data.subarray(offset, offset + this.chunkBytes) }, live);
        }
        if (live()) await this.send(socket, { type: FrameType.STREAM_END, streamId: id }, live);
      })().catch((error: Error) => this.failOutbound(socket, id, error, true));
    });
  }

  sendEvent(name: string, payloadJson: unknown = null, rawPayload?: Uint8Array | null): boolean {
    const socket = this.activeSocket;
    if (!socket || socket.destroyed) return false;
    this.writer(socket).immediate(encodeFrame({ type: FrameType.EVENT, streamId: 0,
      meta: { name, payload: payloadJson }, payload: rawPayload }), true);
    return true;
  }

  private handleConnection(socket: Socket): void {
    if (this.closed || this.sockets.size >= 4) { socket.destroy(); return; }
    const writer = new SocketWriter(socket);
    this.sockets.set(socket, writer);
    const decoder = new FrameDecoder();
    let authenticated = false;
    const handshake = setTimeout(() => socket.destroy(), WRITE_TIMEOUT_MS);
    handshake.unref?.();
    socket.on('data', (chunk) => {
      try {
        for (const frame of decoder.push(chunk)) {
          if (!authenticated) {
            if (frame.type !== FrameType.PING || frame.payload.length ||
                !tokensEqual(frame.meta?.token, this.options.token)) { socket.destroy(); return; }
            clearTimeout(handshake);
            authenticated = true;
            const previous = this.activeSocket;
            if (previous) {
              this.disconnect(previous, new IpcError('IPC_REPLACED', 'IPC connection replaced'));
              previous.destroy();
            }
            this.activeSocket = socket;
            this.windowNegotiated = frame.meta?.window === STREAM_WINDOW_BYTES;
            this.control(socket, { type: FrameType.PONG, streamId: frame.streamId,
              meta: this.windowNegotiated ? { ok: true, window: STREAM_WINDOW_BYTES } : { ok: true } });
            this.options.onReady?.();
          } else if (this.activeSocket === socket && !socket.destroyed) {
            this.dispatch(socket, frame);
          }
        }
      } catch (error) {
        socket.destroy(error instanceof Error ? error : new Error(errorMessage(error)));
      }
    });
    socket.on('error', (error) => this.options.onError?.(error));
    socket.on('close', () => {
      clearTimeout(handshake);
      const error = new IpcError('IPC_CLOSED', 'IPC connection closed');
      writer.close(error);
      this.sockets.delete(socket);
      if (this.activeSocket === socket) {
        this.disconnect(socket, error);
        this.options.onClose?.();
      }
    });
  }

  private disconnect(socket: Socket, error: Error): void {
    this.activeSocket = null;
    for (const id of this.inbound.keys()) this.abortInbound(id, error);
    for (const id of this.outbound.keys()) this.failOutbound(socket, id, error, false);
  }

  private dispatch(socket: Socket, frame: DecodedFrame): void {
    const { type, streamId: id, meta } = frame;
    if (type === FrameType.PING) {
      this.control(socket, { type: FrameType.PONG, streamId: id, meta });
    } else if (type === FrameType.EVENT) {
      const name = typeof meta?.name === 'string' ? meta.name : typeof meta?.method === 'string' ? meta.method : '';
      if (name && this.options.onEvent) this.options.onEvent(name, meta?.payload, frame.payload);
      else if (frame.payload.length) this.options.onError?.(new IpcError('BINARY_UNSUPPORTED', `Unhandled binary event ${name}`));
    } else if (type === FrameType.REQ) {
      this.receiveRequest(socket, frame);
    } else if (this.outbound.has(id)) {
      this.receiveResult(socket, frame);
    } else {
      const call = this.inbound.get(id);
      if (!call || call.controller.signal.aborted) return;
      try {
        if (type === FrameType.ERR || (type === FrameType.STREAM_END && (meta?.cancelled === true || meta?.cancel === true))) {
          this.abortInbound(id, new IpcError('IPC_CANCELLED', 'IPC request cancelled'));
          this.control(socket, { type: FrameType.STREAM_END, streamId: id, meta: { cancelled: true } });
        } else if (type === FrameType.WINDOW_UPDATE) {
          const bytes = Number(meta?.bytes);
          if (!this.windowNegotiated || frame.payload.length || !Number.isInteger(bytes) ||
              typeof meta?.bytes !== 'number' || bytes <= 0 || bytes > STREAM_WINDOW_BYTES) throw limitError();
          if (call.responseStream && call.credit + bytes <= STREAM_WINDOW_BYTES) {
            call.credit += bytes;
            call.wakeCredit?.();
          }
        } else if (type === FrameType.STREAM_DATA || type === FrameType.STREAM_END) {
          this.receiveBody(socket, call, frame);
        }
      } catch (error) {
        this.abortInbound(id, error as Error);
        this.replyError(socket, id, error);
      }
    }
  }

  private receiveRequest(socket: Socket, frame: DecodedFrame): void {
    const { streamId: id, meta, payload } = frame;
    try {
      if (!id || id > 0x7fffffff || this.inbound.has(id)) throw new IpcError('IPC_PROTOCOL_ERROR', 'Invalid or duplicate request ID');
      const method = typeof meta?.method === 'string' ? meta.method : '';
      if (!method || !this.options.onRequest) throw new IpcError('METHOD_UNKNOWN', method || 'Missing method');
      if (this.inbound.size >= MAX_CALLS || this.runningHandlers >= MAX_CALLS) throw limitError();
      const upload = meta?.upload === true;
      const fragmented = meta?.streamed === true;
      if (upload && (!this.windowNegotiated || fragmented || payload.length)) {
        throw new IpcError('BINARY_UNSUPPORTED', 'Invalid or unsupported streaming upload');
      }
      const params = isPlainObject(meta?.args) ? meta.args : isPlainObject(meta?.params) ? meta.params : {};
      const reserved = (frameMetadataSizes.get(frame) ?? 0) + payload.length;
      this.reserve(reserved);
      const requestedTimeout = typeof meta?.timeoutMs === 'number' ? meta.timeoutMs : DEFAULT_REQUEST_TIMEOUT_MS;
      const timeout = Math.max(1, Math.min(requestedTimeout, MAX_REQUEST_TIMEOUT_MS));
      const timer = setTimeout(() => {
        this.abortInbound(id, new IpcError('IPC_TIMEOUT', 'IPC handler timed out'));
        this.replyError(socket, id, new IpcError('IPC_TIMEOUT', 'IPC handler timed out'));
      }, timeout);
      timer.unref?.();
      const call: InboundCall = { method, params, controller: new AbortController(), timer,
        responseStream: true, running: false, reserved, fragmentBytes: payload.length, credit: STREAM_WINDOW_BYTES };
      this.inbound.set(id, call);
      if (upload) {
        call.upload = new UploadBody((bytes) => this.control(socket, { type: FrameType.WINDOW_UPDATE, streamId: id, meta: { bytes } }));
        this.runHandler(socket, id, call, EMPTY_BUFFER);
      } else if (fragmented) {
        call.fragments = payload.length ? [payload] : [];
      } else {
        this.runHandler(socket, id, call, payload);
      }
    } catch (error) {
      this.replyError(socket, id, error);
    }
  }

  private receiveBody(socket: Socket, call: InboundCall, frame: DecodedFrame): void {
    const final = frame.type === FrameType.STREAM_END;
    const error = final ? terminalError(frame.meta) : undefined;
    if (error) throw error;
    if (call.upload) {
      if (final) {
        if (frame.payload.length) throw new IpcError('IPC_PROTOCOL_ERROR', 'Upload end must not contain bytes');
        call.upload.finish();
      } else call.upload.push(frame);
      return;
    }
    if (!call.fragments || (!final && frame.meta !== undefined)) throw new IpcError('IPC_PROTOCOL_ERROR', 'Unexpected request body');
    const bytes = frame.payload.length;
    if (call.fragmentBytes + bytes > MAX_PAYLOAD_BYTES) throw new IpcError('PAYLOAD_TOO_LARGE', 'RPC payload exceeds 16 MiB');
    if (bytes) {
      const cost = frameCost(bytes);
      this.reserve(cost);
      call.reserved += cost;
      call.fragmentBytes += bytes;
      call.fragments.push(frame.payload);
    }
    if (final) {
      const payload = Buffer.concat(call.fragments, call.fragmentBytes);
      call.fragments = undefined;
      this.runHandler(socket, frame.streamId, call, payload);
    }
  }

  private runHandler(socket: Socket, id: number, call: InboundCall, payload: Buffer): void {
    call.running = true;
    this.runningHandlers++;
    const signal = call.controller.signal;
    const live = () => !signal.aborted && this.activeSocket === socket;
    void (async () => {
      try {
        const result = await this.options.onRequest!(call.method, call.params, {
          method: call.method, streamId: id, signal, payload, ...(call.upload ? { payloadStream: call.upload } : {})
        });
        if (!live()) return;
        if (call.upload && !call.upload.consumed) throw new IpcError('UPLOAD_INCOMPLETE', 'Handler returned before consuming the upload');
        if (isAsyncIterable(result)) {
          await this.sendIterable(socket, id, call, result, live);
        } else if (result instanceof Uint8Array || isBinaryResult(result)) {
          const binary = result instanceof Uint8Array ? { payload: result } : result;
          await this.sendBinary(socket, id, call, binary, live);
        } else {
          const normalized = result ?? null;
          const json = JSON.stringify(normalized);
          if (Buffer.byteLength(json) <= this.chunkBytes - 16) {
            await this.send(socket, { type: FrameType.RESP, streamId: id, meta: { result: normalized } }, live);
          } else {
            await this.sendBinary(socket, id, call, { meta: { encoding: 'json' }, payload: Buffer.from(json) }, live);
          }
        }
      } catch (error) {
        if (live()) {
          call.upload?.finish(error as Error);
          this.replyError(socket, id, error);
        }
      } finally {
        this.runningHandlers--;
        this.finishInbound(id, call);
      }
    })();
  }

  private async sendIterable(socket: Socket, id: number, call: InboundCall, iterable: AsyncIterable<unknown>, live: () => boolean): Promise<void> {
    const iterator = iterable[Symbol.asyncIterator]();
    let done = false;
    try {
      while (live()) {
        const step = await raceWithAbort(iterator.next(), call.controller.signal);
        if (step.aborted) return;
        if (step.value.done) {
          done = true;
          const meta = step.value.value === undefined ? { ok: true } : { ok: true, result: step.value.value };
          const bytes = encodeFrame({ type: FrameType.STREAM_END, streamId: id, meta });
          if (bytes.length > STREAM_WINDOW_BYTES) throw limitError();
          await this.send(socket, { type: FrameType.STREAM_END, streamId: id, meta }, live,
            call.responseStream ? call : undefined);
          return;
        }
        const chunk = step.value.value;
        if (chunk instanceof Uint8Array) await this.sendData(socket, id, call, chunk, undefined, live);
        else if (isPlainObject(chunk) && Object.keys(chunk).length > 0 && Object.keys(chunk).every((k) => k === 'meta' || k === 'payload') &&
            (chunk.payload == null || chunk.payload instanceof Uint8Array) && (chunk.meta === undefined || isPlainObject(chunk.meta))) {
          await this.sendData(socket, id, call, chunk.payload as Uint8Array | undefined, chunk.meta as Record<string, unknown> | undefined, live);
        } else if (chunk !== undefined) {
          await this.sendData(socket, id, call, undefined, isPlainObject(chunk) ? chunk : { value: chunk }, live);
        }
      }
    } finally {
      if (!done) await iterator.return?.().catch(() => undefined);
    }
  }

  private async sendBinary(socket: Socket, id: number, call: InboundCall, result: BinaryResult, live: () => boolean): Promise<void> {
    const { payload, meta } = result;
    if (payload.length > MAX_PAYLOAD_BYTES) throw new IpcError('PAYLOAD_TOO_LARGE', 'Use an AsyncIterable for bytes beyond 16 MiB');
    this.reserve(payload.length);
    try {
      if (payload.length <= this.chunkBytes) {
        const bytes = encodeFrame({ type: FrameType.RESP, streamId: id, meta, payload });
        if (call.responseStream && bytes.length > STREAM_WINDOW_BYTES) throw limitError();
        await this.writer(socket).send(bytes, false, live);
      } else {
        await this.send(socket, { type: FrameType.RESP, streamId: id, meta: { ...meta, streamed: true } }, live,
          call.responseStream && meta && Object.keys(meta).length ? call : undefined);
        await this.sendData(socket, id, call, payload, undefined, live);
        await this.send(socket, { type: FrameType.STREAM_END, streamId: id, meta: { ok: true } }, live);
      }
    } finally {
      this.bufferedBytes -= payload.length;
    }
  }

  private async sendData(socket: Socket, id: number, call: InboundCall, bytes: Uint8Array | undefined,
    meta: Record<string, unknown> | undefined, live: () => boolean): Promise<void> {
    const payload = bytes == null ? EMPTY_BUFFER : bytes;
    let offset = 0;
    do {
      const end = Math.min(offset + this.chunkBytes, payload.length);
      await this.send(socket, { type: FrameType.STREAM_DATA, streamId: id,
        meta: offset === 0 ? meta : undefined, payload: payload.subarray(offset, end) }, live,
        call.responseStream ? call : undefined);
      offset = end;
    } while (offset < payload.length && live());
  }

  private receiveResult(socket: Socket, frame: DecodedFrame): void {
    const { streamId: id, type, meta, payload } = frame;
    const pending = this.outbound.get(id)!;
    try {
      if (type === FrameType.ERR) {
        const code = typeof meta?.code === 'string' ? meta.code : 'HOST_ERROR';
        throw new IpcError(['IPC_CLOSED', 'IPC_NOT_READY'].includes(code) ? 'IPC_REMOTE_ERROR' : code,
          typeof meta?.message === 'string' ? meta.message : 'IPC request failed');
      }
      if (type === FrameType.RESP && !pending.meta && meta?.streamed !== true) {
        this.completeOutbound(id, meta, payload);
        return;
      }
      if (type === FrameType.RESP && !pending.meta) {
        const { streamed: _, ...header } = meta!;
        pending.meta = header;
      } else if (type === FrameType.STREAM_END) {
        const error = terminalError(meta);
        if (error) throw error;
        if (!pending.meta) throw new IpcError('STREAM_REQUIRED', 'RPC received a byte stream instead of a finite response');
      } else if (type !== FrameType.STREAM_DATA || !pending.meta || meta !== undefined) {
        throw new IpcError('IPC_PROTOCOL_ERROR', 'Unexpected RPC response frame');
      }
      if (pending.bytes + payload.length > MAX_PAYLOAD_BYTES) throw new IpcError('PAYLOAD_TOO_LARGE', 'RPC response exceeds 16 MiB');
      const cost = (frameMetadataSizes.get(frame) ?? 0) + (payload.length ? frameCost(payload.length) : 0);
      this.reserve(cost);
      pending.reserved += cost;
      pending.bytes += payload.length;
      if (payload.length) pending.chunks.push(payload);
      if (this.windowNegotiated && (type === FrameType.STREAM_DATA || type === FrameType.STREAM_END || (type === FrameType.RESP && meta?.streamed === true))) {
        const cost = frameCost(IPC_HEADER_BYTES + (frameMetadataSizes.get(frame) ?? 0) + payload.length);
        this.control(socket, { type: FrameType.WINDOW_UPDATE, streamId: id, meta: { bytes: cost } });
      }
      if (type === FrameType.STREAM_END) this.completeOutbound(id, { ...pending.meta, ...meta }, Buffer.concat(pending.chunks, pending.bytes));
    } catch (error) {
      this.failOutbound(socket, id, error as Error, type !== FrameType.ERR && type !== FrameType.STREAM_END);
    }
  }

  private completeOutbound(id: number, meta: Record<string, unknown> | undefined, payload: Buffer): void {
    let value: unknown;
    if (meta?.encoding === 'json' && payload.length) value = JSON.parse(payload.toString('utf8'));
    else if (payload.length) value = { meta: meta ?? {}, payload };
    else if (meta && Object.prototype.hasOwnProperty.call(meta, 'result')) value = meta.result;
    else value = meta ?? null;
    this.removeOutbound(id)?.resolve(value);
  }

  private removeOutbound(id: number): OutboundPending | undefined {
    const entry = this.outbound.get(id);
    if (!entry) return;
    this.outbound.delete(id);
    clearTimeout(entry.timer);
    this.bufferedBytes -= entry.reserved;
    return entry;
  }

  private failOutbound(socket: Socket, id: number, error: Error, cancel: boolean): void {
    const entry = this.removeOutbound(id);
    if (!entry) return;
    entry.reject(error);
    if (cancel && entry.sent) {
      setImmediate(() => {
        if (this.activeSocket === socket && !socket.destroyed) {
          this.control(socket, { type: FrameType.STREAM_END, streamId: id, meta: { cancelled: true } });
        }
      });
    }
  }

  private abortInbound(id: number, error: Error): void {
    const call = this.inbound.get(id);
    if (!call) return;
    call.upload?.finish(error);
    call.controller.abort();
    call.wakeCredit?.();
    this.finishInbound(id, call);
  }

  private finishInbound(id: number, call: InboundCall): void {
    if (this.inbound.get(id) !== call) return;
    this.inbound.delete(id);
    clearTimeout(call.timer);
    this.bufferedBytes -= call.reserved;
  }

  private reserve(bytes: number): void {
    if (this.bufferedBytes + bytes > BUFFER_BUDGET) throw limitError();
    this.bufferedBytes += bytes;
  }

  private writer(socket: Socket): SocketWriter {
    const writer = this.sockets.get(socket);
    if (!writer) throw new IpcError('IPC_CLOSED', 'IPC connection closed');
    return writer;
  }

  private async send(socket: Socket, frame: IpcFrame, live: () => boolean, credit?: InboundCall): Promise<void> {
    const bytes = encodeFrame(frame);
    if (credit && this.windowNegotiated) {
      const cost = frameCost(bytes.length);
      if (cost > STREAM_WINDOW_BYTES) throw limitError();
      while (live() && credit.credit < cost) {
        await new Promise<void>((resolve) => { credit.wakeCredit = resolve; });
        credit.wakeCredit = undefined;
      }
      if (!live()) throw new IpcError('IPC_CANCELLED', 'IPC stream cancelled');
      credit.credit -= cost;
    }
    await this.writer(socket).send(bytes, frame.type === FrameType.STREAM_DATA, live);
  }

  private control(socket: Socket, frame: IpcFrame): void {
    if (socket.destroyed || this.activeSocket !== socket) return;
    try { this.writer(socket).immediate(encodeFrame(frame)); }
    catch (error) { socket.destroy(error as Error); }
  }

  private replyError(socket: Socket, id: number, error: unknown): void {
    this.control(socket, { type: FrameType.ERR, streamId: id, meta: {
      code: errorCode(error).slice(0, 128), message: errorMessage(error).slice(0, 2000)
    } });
  }
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
