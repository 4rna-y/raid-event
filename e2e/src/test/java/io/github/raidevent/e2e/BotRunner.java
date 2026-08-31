package io.github.raidevent.e2e;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ヘッドレスクライアント (mineflayer) を走らせて、観測を持ち帰る。
 *
 * <p>ボット側は「何が起きたか」を1行1件の JSON で流すだけで、成否は判断しない。
 * 判断はテスト側で行う。
 */
final class BotRunner {

    /** 観測1件。 */
    record Observation(String event, String raw) {

        /**
         * 素朴な取り出し。値は文字列・オブジェクト・配列・数値・真偽値を想定。
         *
         * <p>選択肢の並びは順序で当てられると弱いので、順不同で見たい。そのため
         * オブジェクトと配列は丸ごと文字列として返す。区切りだけを見る素朴な作りなので、
         * <b>値の中に {@code }} や {@code ]} を含む場合は扱えない</b>。
         * ボット側が流す観測はその範囲に収めてある。
         */
        Optional<String> field(String name) {
            // 「区切りまで」の一番緩い形を最後に置く。先に置くと入れ子が途中で切れる
            Matcher matcher = Pattern.compile(
                    "\"" + Pattern.quote(name) + "\":"
                            + "(\"([^\"]*)\"|\\{[^}]*\\}|\\[[^\\]]*\\]|[^,}]+)")
                    .matcher(raw);
            if (!matcher.find()) {
                return Optional.empty();
            }
            return Optional.of(matcher.group(2) != null ? matcher.group(2) : matcher.group(1));
        }

        boolean has(String name, String value) {
            return field(name).filter(value::equals).isPresent();
        }
    }

    /** ボットの実行結果。 */
    record Result(int exitCode, List<Observation> observations, String output) {

        List<Observation> of(String event) {
            return observations.stream().filter(o -> o.event().equals(event)).toList();
        }

        Optional<Observation> first(String event) {
            return of(event).stream().findFirst();
        }

        String describe() {
            return "\n--- ボットの出力 ---\n" + output;
        }
    }

    private BotRunner() {
    }

    /** node が使えて、依存も入っているか。 */
    static boolean available(Path botDir) {
        return Files.isDirectory(botDir.resolve("node_modules/mineflayer"));
    }

    static Result run(Path botDir, String scenario, int port, Duration timeout)
            throws IOException, InterruptedException {
        return run(botDir, scenario, port, timeout, observation -> {
        });
    }

    /**
     * 観測が届くたびに {@code onObservation} を呼びながら走らせる。
     *
     * <p>「ボットが構えたらコンソールから殴る」のように、途中で外から手を入れたい場合に使う。
     */
    static Result run(Path botDir, String scenario, int port, Duration timeout,
            java.util.function.Consumer<Observation> onObservation)
            throws IOException, InterruptedException {
        // ボット側の見張りは、こちらの制限より少し手前で鳴らす。そうすると
        // 「時間切れ」がボットの観測として残り、何をしている途中だったか分かる。
        long botTimeoutMs = Math.max(30_000L, timeout.toMillis() - 15_000L);
        String config = ("{\"host\":\"127.0.0.1\",\"port\":%d,\"version\":\"%s\","
                + "\"scenario\":\"%s\",\"timeoutMs\":%d}")
                .formatted(port, botVersionFor(), scenario, botTimeoutMs);

        ProcessBuilder builder = new ProcessBuilder("node", "scenario.cjs", config)
                .directory(botDir.toFile())
                .redirectErrorStream(true);
        Process process = builder.start();

        List<Observation> observations = new ArrayList<>();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
                Matcher event = Pattern.compile("\"event\":\"([^\"]+)\"").matcher(line);
                if (event.find()) {
                    Observation observation = new Observation(event.group(1), line);
                    observations.add(observation);
                    onObservation.accept(observation);
                }
            }
        }
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("ボットが終わらない\n" + output);
        }
        return new Result(process.exitValue(), observations, output.toString());
    }

    /**
     * ボットが名乗るバージョン。
     *
     * <p>サーバーの {@link PaperJar#VERSION} は 26.1.2 だが、プロトコルは 26.1 と同じ (775)
     * で、ヘッドレスクライアント側の対応表には "26.1" しか無い。
     */
    private static String botVersionFor() {
        return "26.1";
    }

}
