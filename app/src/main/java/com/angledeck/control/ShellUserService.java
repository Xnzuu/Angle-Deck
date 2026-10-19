package com.angledeck.control;

import android.os.RemoteException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Runs in the Shizuku UserService process as adb/shell (UID 2000), not as root. */
public final class ShellUserService extends ICommandService.Stub {
    private final Object streamLock = new Object();
    private Process logProcess;
    private Thread logReader;
    private final StringBuilder pending = new StringBuilder();
    private int logGeneration = 0;
    private static final int MAX_PENDING = 180_000; // 180 KB max buffered while game is foreground

    public ShellUserService() {}

    /** UID filter survives app process restarts (PID filters do not). */
    @Override public String startLog(int uid) throws RemoteException {
        if (uid < 10000) return "ERROR: refusing system UID";
        synchronized (streamLock) {
            stopStreamLocked();
            pending.setLength(0);
            final int token = ++logGeneration;
            try {
                // -T 1: start tailing with one previous entry; no destructive logcat -c.
                logProcess = new ProcessBuilder("/system/bin/logcat",
                        "-v", "threadtime", "-b", "main", "-b", "system", "-b", "crash",
                        "--uid=" + uid, "-T", "1")
                        .redirectErrorStream(true).start();
                final Process child = logProcess;
                logReader = new Thread(() -> {
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(
                            child.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            synchronized (streamLock) {
                                if (token != logGeneration) break;
                                pending.append(line).append('\n');
                                if (pending.length() > MAX_PENDING) {
                                    int cut = pending.length() - MAX_PENDING;
                                    int nl = pending.indexOf("\n", cut);
                                    pending.delete(0, nl >= 0 ? nl + 1 : cut);
                                }
                            }
                        }
                        synchronized (streamLock) {
                            if (token == logGeneration) pending.append("[Logcat stream ended]\n");
                        }
                    } catch (Exception e) {
                        synchronized (streamLock) {
                            if (token == logGeneration) pending.append("[Logcat error: " + e.getMessage() + "]\n");
                        }
                    }
                }, "angledeck-live-logcat");
                logReader.setDaemon(true);
                logReader.start();
                return "Capturing UID " + uid + " (main/system/crash buffers)";
            } catch (Exception e) {
                stopStreamLocked();
                return "ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }
    }

    @Override public String pollLog(int maxChars) throws RemoteException {
        synchronized (streamLock) {
            int limit = Math.max(256, Math.min(maxChars, 12000));
            if (pending.length() == 0) return "";
            int len = Math.min(limit, pending.length());
            int end = pending.lastIndexOf("\n", len - 1);
            if (end >= 0 && end < len) len = end + 1;
            String chunk = pending.substring(0, len);
            pending.delete(0, len);
            return chunk;
        }
    }

    @Override public void stopLog() throws RemoteException {
        synchronized (streamLock) { stopStreamLocked(); }
    }

    private void stopStreamLocked() {
        ++logGeneration;
        if (logProcess != null) {
            logProcess.destroy();
            logProcess = null;
        }
        logReader = null;
    }


    @Override
    public String run(String command) throws RemoteException {
        if (command == null || command.length() > 4096) return "ERROR: invalid command";
        Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/sh", "-c", command)
                    .redirectErrorStream(true).start();
            final Process running = process;
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try (InputStream in = running.getInputStream()) {
                    byte[] buffer = new byte[2048];
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        synchronized (output) {
                            if (output.size() < 32768) {
                                output.write(buffer, 0, Math.min(count, 32768 - output.size()));
                            }
                        }
                    }
                } catch (Exception ignored) { }
            }, "angledeck-shell-reader");
            reader.setDaemon(true);
            reader.start();
            boolean done = running.waitFor(12, TimeUnit.SECONDS);
            if (!done) {
                running.destroyForcibly();
                reader.join(500);
                return "ERROR: command timed out (12 s)";
            }
            reader.join(1000);
            String body;
            synchronized (output) {
                body = output.toString("UTF-8").trim();
            }
            if (running.exitValue() != 0) {
                return "ERROR: exit " + running.exitValue() + (body.isEmpty() ? "" : "\n" + body);
            }
            return body;
        } catch (Exception e) {
            if (process != null) process.destroyForcibly();
            return "ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }
}
