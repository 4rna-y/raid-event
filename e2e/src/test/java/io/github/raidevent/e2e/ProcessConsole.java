package io.github.raidevent.e2e;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * サーバープロセスのコンソール。
 *
 * <p>出力は別スレッドで読み続けて溜める。テストからは「この文字列が出るまで待つ」の形で使う。
 * 失敗時に何が起きていたか分かるよう、全出力をファイルにも残す。
 */
final class ProcessConsole implements AutoCloseable {

    private final Process process;
    private final Writer input;
    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final Path logFile;

    private ProcessConsole(Process process, Path logFile) {
        this.process = process;
        this.logFile = logFile;
        this.input = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);

        Thread pump = new Thread(this::pump, "server-console");
        pump.setDaemon(true);
        pump.start();
    }

    static ProcessConsole start(Path workingDir, Path logFile, List<String> command)
            throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workingDir.toFile())
                .redirectErrorStream(true);
        return new ProcessConsole(builder.start(), logFile);
    }

    private void pump() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                Writer out = Files.newBufferedWriter(logFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
                out.write(line);
                out.write('\n');
                out.flush();
            }
        } catch (IOException e) {
            // プロセス終了時に閉じられるのは想定内
        }
    }

    /** コンソールへ1行送る。 */
    void send(String command) throws IOException {
        input.write(command);
        input.write('\n');
        input.flush();
    }

    /** これまでの出力に fragment を含む行があるか。 */
    boolean sawLine(String fragment) {
        return lines.stream().anyMatch(line -> line.contains(fragment));
    }

    /** 今までに読んだ行数。{@link #awaitSince} と組み合わせて「この後に出た行」だけを待つ。 */
    int mark() {
        return lines.size();
    }

    /** mark 以降に fragment を含む行が出るまで待つ。前のテストが出した同じ文言に釣られない。 */
    void awaitSince(String fragment, int mark, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (sawLineSince(fragment, mark)) {
                return;
            }
            if (!process.isAlive()) {
                throw new AssertionError("サーバーが終了した。待っていたもの: " + fragment + tail());
            }
            Thread.sleep(200);
        }
        throw new AssertionError("待ち時間を超えた: " + fragment + tail());
    }

    boolean sawLineSince(String fragment, int mark) {
        List<String> snapshot = List.copyOf(lines);
        for (int i = Math.min(mark, snapshot.size()); i < snapshot.size(); i++) {
            if (snapshot.get(i).contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /** fragment を含む行が出るまで待つ。出なければ例外。 */
    void await(String fragment, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (sawLine(fragment)) {
                return;
            }
            if (!process.isAlive() && !sawLine(fragment)) {
                throw new AssertionError(
                        "サーバーが終了した。待っていたもの: " + fragment + tail());
            }
            Thread.sleep(200);
        }
        throw new AssertionError("待ち時間を超えた: " + fragment + tail());
    }

    /** 失敗時に何が起きていたか分かるよう、直近の出力を添える。 */
    /** これまでの出力の写し。座標の取り出しなど、行を自分で探したいテスト用。 */
    List<String> lines() {
        return List.copyOf(lines);
    }

    String tail() {
        int from = Math.max(0, lines.size() - 40);
        return "\n--- コンソールの直近 ---\n" + String.join("\n", lines.subList(from, lines.size()));
    }

    boolean isAlive() {
        return process.isAlive();
    }

    /** 停止を要求し、終わるまで待つ。 */
    void stopAndWait(Duration timeout) throws IOException, InterruptedException {
        if (process.isAlive()) {
            send("stop");
            if (!process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        }
    }

    @Override
    public void close() {
        process.destroyForcibly();
    }
}
