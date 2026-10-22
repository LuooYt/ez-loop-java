package com.inspirationi.loop.tool.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 进程执行器 —— Bash、Notification、LSP 共用的子进程封装。
 * <p>
 * 两个关键点：
 * <ul>
 *   <li><b>输出由独立虚拟线程读取</b>，主线程负责 {@code waitFor(timeout)}。
 *       若在主线程 {@code readAllBytes()}，它会一直阻塞到进程退出，超时形同虚设；
 *       若既不读又等待，子进程写满管道后会死锁。</li>
 *   <li><b>超时杀进程树</b>：只杀直接子进程会留下孙进程（如 {@code cmd /c ping}
 *       里的 ping），它们继续占用管道与 CPU。</li>
 * </ul>
 * stderr 合并进输出（{@code redirectErrorStream(true)}）。
 */
public final class ProcessExecutor {

    /** 输出缓冲上限（字节）。超限部分丢弃，但仍继续读空管道。 */
    public static final int DEFAULT_MAX_OUTPUT_BYTES = 1024 * 1024;

    private ProcessExecutor() {}

    /** 进程执行结果。 */
    public record Result(int exitCode, String output, boolean timedOut) {
        public boolean isSuccess() {
            return exitCode == 0 && !timedOut;
        }
    }

    /** 在指定工作目录执行命令（参数以列表传入，不经过 shell）。 */
    public static Result execute(List<String> command, Path workDir, long timeoutMs) {
        return execute(command, workDir, timeoutMs, null, DEFAULT_MAX_OUTPUT_BYTES);
    }

    /** 在指定工作目录执行命令，可追加环境变量。 */
    public static Result execute(List<String> command, Path workDir, long timeoutMs,
                                 Map<String, String> env) {
        return execute(command, workDir, timeoutMs, env, DEFAULT_MAX_OUTPUT_BYTES);
    }

    /**
     * 在指定工作目录执行命令，可追加环境变量并限制输出缓冲字节数。
     * <p>
     * 缓冲必须有界：一条刷屏命令（如 {@code yes}）会在超时之前就把无界
     * {@link ByteArrayOutputStream} 撑爆堆。超限后丢弃剩余输出，但读线程继续读到
     * EOF —— 停止读取会让子进程写满管道后阻塞，最终只能等超时。
     */
    public static Result execute(List<String> command, Path workDir, long timeoutMs,
                                 Map<String, String> env, int maxOutputBytes) {
        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(workDir != null ? workDir.toFile() : null)
                .redirectErrorStream(true);
        if (env != null && !env.isEmpty()) {
            pb.environment().putAll(env);
        }

        int limit = Math.max(1, maxOutputBytes);
        Process process = null;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            process = pb.start();
            Process started = process;
            Thread reader = Thread.ofVirtual().start(() -> {
                byte[] chunk = new byte[8192];
                try (InputStream in = started.getInputStream()) {
                    int read;
                    while ((read = in.read(chunk)) != -1) {
                        synchronized (buffer) {
                            int room = limit - buffer.size();
                            if (room > 0) {
                                buffer.write(chunk, 0, Math.min(room, read));
                            }
                        }
                    }
                } catch (IOException ignored) {
                    // 进程被杀时管道关闭，属于预期
                }
            });

            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                killTree(process);
                process.waitFor(2, TimeUnit.SECONDS);
                reader.join(1_000);
                return new Result(-1, snapshot(buffer), true);
            }
            reader.join(2_000);
            return new Result(process.exitValue(), snapshot(buffer), false);

        } catch (IOException e) {
            return new Result(-1, "IOException: " + e.getMessage(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "Interrupted", false);
        } finally {
            if (process != null && process.isAlive()) {
                killTree(process);
            }
        }
    }

    /** 读线程可能仍在运行（超时路径），快照必须与写入同步。 */
    private static String snapshot(ByteArrayOutputStream buffer) {
        synchronized (buffer) {
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }

    /** 通过系统 shell 执行 —— 供 Bash 工具使用。 */
    public static Result executeShell(String command, Path workDir, long timeoutMs) {
        return executeShell(command, workDir, timeoutMs, DEFAULT_MAX_OUTPUT_BYTES);
    }

    /** 通过系统 shell 执行，并限制输出缓冲字节数。 */
    public static Result executeShell(String command, Path workDir, long timeoutMs,
                                      int maxOutputBytes) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return execute(windows
                ? List.of("cmd.exe", "/c", command)
                : List.of("/bin/sh", "-c", command), workDir, timeoutMs, null, maxOutputBytes);
    }

    /** 先杀子孙进程再杀进程本身；顺序反了会先失去对孙进程的引用。 */
    private static void killTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}
