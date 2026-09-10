package com.termux.terminal;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Message;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A terminal session, consisting of a process (or remote stream) coupled to a terminal interface.
 *
 * <p>The actual I/O transport is abstracted behind a {@link SessionBackend}: a local pty subprocess
 * ({@link SubprocessBackend}, via JNI fork — used by the local PRoot container) or a remote byte stream
 * (an SSH shell channel — used by the remote execution mode). When the size is made known by a call to
 * {@link #updateSize(int, int)} terminal emulation begins and threads are spawned to pump bytes between
 * the backend and the emulator. All terminal emulation and callback methods run on the main thread.
 *
 * <p>The backend may be exited forcefully via {@link #finishIfRunning()}.
 */
public final class TerminalSession extends TerminalOutput {

    private static final int MSG_NEW_INPUT = 1;
    private static final int MSG_PROCESS_EXITED = 4;

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;

    /**
     * A queue written to from a separate thread when the backend outputs, and read by main thread to process by
     * terminal emulator.
     */
    final ByteQueue mProcessToTerminalIOQueue = new ByteQueue(4096);
    /**
     * A queue written to from the main thread due to user interaction, and read by another thread which forwards by
     * writing to the backend's output stream.
     */
    final ByteQueue mTerminalToProcessIOQueue = new ByteQueue(4096);
    /** Buffer to write translate code points into utf8 before writing to mTerminalToProcessIOQueue */
    private final byte[] mUtf8InputBuffer = new byte[5];

    /** Callback which gets notified when a session finishes or changes title. */
    TerminalSessionClient mClient;

    /** The pid of the shell process. 0 if not started and -1 if finished running. -2 for non-subprocess backends. */
    int mShellPid;

    /** The exit status of the shell process. Only valid if ${@link #mShellPid} is -1. */
    int mShellExitStatus;

    /** Set by the application for user identification of session, not by terminal. */
    public String mSessionName;

    final Handler mMainThreadHandler = new MainThreadHandler();

    private final Integer mTranscriptRows;

    private final SessionBackend mBackend;

    private static final String LOG_TAG = "TerminalSession";

    /**
     * Construct a session backed by a local pty subprocess (the upstream Termux behavior).
     * The subprocess is spawned lazily when {@link #updateSize} is first called.
     */
    public TerminalSession(String shellPath, String cwd, String[] args, String[] env, Integer transcriptRows, TerminalSessionClient client) {
        this(transcriptRows, client, new LocalBackendHolder(shellPath, cwd, args, env));
    }

    /**
     * Construct a session backed by a caller-supplied {@link SessionBackend} (e.g. an SSH shell
     * channel). The backend must already be open; emulation begins when {@link #updateSize} is called.
     */
    public TerminalSession(Integer transcriptRows, TerminalSessionClient client, SessionBackend backend) {
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
        this.mBackend = backend;
        this.mShellPid = 0;
    }

    /** Holder that defers {@link SubprocessBackend} creation until {@link #initializeEmulator} knows the size. */
    private static final class LocalBackendHolder implements SessionBackend {
        private final String mShellPath;
        private final String mCwd;
        private final String[] mArgs;
        private final String[] mEnv;
        private SubprocessBackend mDelegate;

        LocalBackendHolder(String shellPath, String cwd, String[] args, String[] env) {
            this.mShellPath = shellPath;
            this.mCwd = cwd;
            this.mArgs = args;
            this.mEnv = env;
        }

        @Override
        public InputStream getInputStream() {
            return mDelegate.getInputStream();
        }

        @Override
        public OutputStream getOutputStream() {
            return mDelegate.getOutputStream();
        }

        @Override
        public void resize(int columns, int rows) {
            mDelegate.resize(columns, rows);
        }

        @Override
        public int waitForExit() {
            return mDelegate.waitForExit();
        }

        @Override
        public void close() {
            mDelegate.close();
        }

        SubprocessBackend open(int columns, int rows) {
            mDelegate = new SubprocessBackend(mShellPath, mCwd, mArgs, mEnv, rows, columns);
            return mDelegate;
        }
    }

    /**
     * @param client The {@link TerminalSessionClient} interface implementation to allow
     *               for communication between {@link TerminalSession} and its client.
     */
    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;

        if (mEmulator != null)
            mEmulator.updateTerminalSessionClient(client);
    }

    /** Inform the attached backend of the new size and reflow or initialize the emulator. */
    public void updateSize(int columns, int rows) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows);
        } else {
            mBackend.resize(columns, rows);
            mEmulator.resize(columns, rows);
        }
    }

    /** The terminal title as set through escape sequences or null if none set. */
    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /**
     * Set the terminal emulator's window size and start terminal emulation.
     *
     * @param columns The number of columns in the terminal window.
     * @param rows    The number of rows in the terminal window.
     */
    public void initializeEmulator(int columns, int rows) {
        mEmulator = new TerminalEmulator(this, columns, rows, mTranscriptRows, mClient);

        if (mBackend instanceof LocalBackendHolder) {
            SubprocessBackend sub;
            try {
                sub = ((LocalBackendHolder) mBackend).open(columns, rows);
            } catch (RuntimeException e) {
                mClient.logError(LOG_TAG, "PTY 子进程创建失败: " + e.getMessage());
                throw e;
            }
            mShellPid = sub.getPid();
            mClient.logInfo(LOG_TAG, "PTY 子进程已启动: pid=" + mShellPid + " size=" + columns + "x" + rows);
            startPumpThreads("pid=" + mShellPid);
            new Thread("TermSessionWaiter[pid=" + mShellPid + "]") {
                @Override
                public void run() {
                    int processExitCode = mBackend.waitForExit();
                    mMainThreadHandler.sendMessage(mMainThreadHandler.obtainMessage(MSG_PROCESS_EXITED, processExitCode));
                }
            }.start();
        } else {
            // Remote stream backend: no pid; waiter just blocks on the backend's stream end.
            mShellPid = -2;
            mClient.logInfo(LOG_TAG, "远程会话已启动: size=" + columns + "x" + rows);
            startPumpThreads("remote");
            new Thread("TermSessionWaiter[remote]") {
                @Override
                public void run() {
                    int processExitCode = mBackend.waitForExit();
                    mMainThreadHandler.sendMessage(mMainThreadHandler.obtainMessage(MSG_PROCESS_EXITED, processExitCode));
                }
            }.start();
        }
    }

    private void startPumpThreads(String label) {
        final InputStream termIn = mBackend.getInputStream();
        final OutputStream termOut = mBackend.getOutputStream();

        new Thread("TermSessionInputReader[" + label + "]") {
            @Override
            public void run() {
                final byte[] buffer = new byte[4096];
                try {
                    while (true) {
                        int read = termIn.read(buffer);
                        if (read == -1) {
                            // 子进程一启动就退出（如 execve 失败）时这里会立刻拿到 EOF。
                            // 记一行，免得终端空白时无法判断 proot 到底有没有跑起来。
                            mClient.logInfo(LOG_TAG, "PTY 输入流结束(EOF)，子进程已退出 pid=" + mShellPid);
                            return;
                        }
                        if (!mProcessToTerminalIOQueue.write(buffer, 0, read)) return;
                        mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
                    }
                } catch (Exception e) {
                    mClient.logWarn(LOG_TAG, "PTY 读取异常(pid=" + mShellPid + "): " + e);
                }
            }
        }.start();

        new Thread("TermSessionOutputWriter[" + label + "]") {
            @Override
            public void run() {
                final byte[] buffer = new byte[4096];
                try {
                    while (true) {
                        int bytesToWrite = mTerminalToProcessIOQueue.read(buffer, true);
                        if (bytesToWrite == -1) return;
                        termOut.write(buffer, 0, bytesToWrite);
                        termOut.flush();
                    }
                } catch (Exception e) {
                    mClient.logWarn(LOG_TAG, "PTY 写入异常(pid=" + mShellPid + "): " + e);
                }
            }
        }.start();
    }

    /** Write data to the shell process / remote shell. */
    @Override
    public void write(byte[] data, int offset, int count) {
        if (mShellPid != -1) mTerminalToProcessIOQueue.write(data, offset, count);
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point, [0xD800,0xDFFF] is the surrogate range.
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            /* 110xxxxx leading byte with leading 5 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            /* 1110xxxx leading byte with leading 4 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else { /* We have checked codePoint <= 1114111 above, so we have max 21 bits = 0b111111111111111111111 */
            /* 11110xxx leading byte with leading 3 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    /** Notify the {@link #mClient} that the screen has changed. */
    protected void notifyScreenUpdate() {
        mClient.onTextChanged(this);
    }

    /** Reset state for terminal emulator state. */
    public void reset() {
        mEmulator.reset();
        notifyScreenUpdate();
    }

    /** Finish this terminal session by terminating the backend (SIGKILL for local, close for remote). */
    public void finishIfRunning() {
        if (isRunning()) {
            mBackend.close();
        }
    }

    /** Cleanup resources when the process/stream exits. */
    void cleanupResources(int exitStatus) {
        synchronized (this) {
            mShellPid = -1;
            mShellExitStatus = exitStatus;
        }

        // Stop the reader and writer threads, and close the I/O streams
        mTerminalToProcessIOQueue.close();
        mProcessToTerminalIOQueue.close();
        mBackend.close();
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    public synchronized boolean isRunning() {
        return mShellPid != -1;
    }

    /** Only valid if not {@link #isRunning()}. */
    public synchronized int getExitStatus() {
        return mShellExitStatus;
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        mClient.onColorsChanged(this);
    }

    public int getPid() {
        return mShellPid;
    }

    /** Returns the shell's working directory or null if it was unavailable. */
    public String getCwd() {
        if (mShellPid < 1) {
            return null;
        }
        try {
            final String cwdSymlink = String.format("/proc/%s/cwd/", mShellPid);
            String outputPath = new java.io.File(cwdSymlink).getCanonicalPath();
            String outputPathWithTrailingSlash = outputPath;
            if (!outputPath.endsWith("/")) {
                outputPathWithTrailingSlash += '/';
            }
            if (!cwdSymlink.equals(outputPathWithTrailingSlash)) {
                return outputPath;
            }
        } catch (Exception e) {
            mClient.logStackTraceWithMessage(LOG_TAG, "Error getting current directory", e);
        }
        return null;
    }

    @SuppressLint("HandlerLeak")
    class MainThreadHandler extends Handler {

        final byte[] mReceiveBuffer = new byte[4 * 1024];

        @Override
        public void handleMessage(Message msg) {
            int bytesRead = mProcessToTerminalIOQueue.read(mReceiveBuffer, false);
            if (bytesRead > 0) {
                mEmulator.append(mReceiveBuffer, bytesRead);
                notifyScreenUpdate();
            }

            if (msg.what == MSG_PROCESS_EXITED) {
                int exitCode = (Integer) msg.obj;
                mClient.logInfo(LOG_TAG, "PTY 子进程退出: pid=" + mShellPid + " exit=" + exitCode);
                cleanupResources(exitCode);

                String exitDescription = "\r\n[Process completed";
                if (exitCode > 0) {
                    // Non-zero process exit.
                    exitDescription += " (code " + exitCode + ")";
                } else if (exitCode < 0) {
                    // Negated signal.
                    exitDescription += " (signal " + (-exitCode) + ")";
                }
                exitDescription += " - press Enter]";

                byte[] bytesToWrite = exitDescription.getBytes(StandardCharsets.UTF_8);
                mEmulator.append(bytesToWrite, bytesToWrite.length);
                notifyScreenUpdate();

                mClient.onSessionFinished(TerminalSession.this);
            }
        }

    }

}
