package io.github.raidevent.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * ヘッドレスクライアントを使った通し検証。
 *
 * <p>本番と同じプラグイン jar (26.2 でコンパイルしたもの) を、26.1 のサーバーに載せて回す。
 * 26.1 なのはヘッドレスクライアントが 26.2 のプロトコルに未対応なため。
 *
 * <p>レイドの駆動はサーバーコンソールから行う: {@code raidevent spawn} で生成し、
 * ボットを tp で地点へ運んで発火させ、モブは共通タグ {@code raidevent} を kill して
 * ウェーブを進める。ボット側は観測 (地図の受領・告知チャット) だけを流す。
 *
 * <p>1つのサーバーの一生を順番に検証するので、テストは順序付きで状態を共有する。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
@DisplayName("レイドの生成から報酬設置・キャンセル・失効までの通し検証")
class RaidE2eTest {

    private static final Duration BOOT = Duration.ofMinutes(5);
    private static final Duration BOT = Duration.ofMinutes(6);

    /** 「レイド生成: ... 地点=(x, y, z)」と「レイド成功: 報酬チェスト (x, y, z)」の座標。 */
    private static final Pattern SITE = Pattern.compile(
            "レイド生成: .*地点=\\((-?\\d+), (-?\\d+), (-?\\d+)\\)");
    private static final Pattern CHEST = Pattern.compile(
            "レイド成功: 報酬チェスト \\((-?\\d+), (-?\\d+), (-?\\d+)\\)");

    private TestServerDir server;
    private ProcessConsole console;
    private Path botDir;

    @BeforeAll
    void bootServer() throws Exception {
        Path buildDir = Path.of(System.getProperty("raidevent.buildDir", "build")).toAbsolutePath();
        botDir = Path.of(System.getProperty("raidevent.botDir", "bot")).toAbsolutePath();
        Path pluginJar = Path.of(System.getProperty("raidevent.pluginJar", ""));

        assumeTrue(Files.isRegularFile(pluginJar), "プラグインの jar が無い: " + pluginJar);
        assumeTrue(BotRunner.available(botDir),
                "ヘッドレスクライアントが未導入。" + botDir + " で npm install すること");

        Path paperJar = PaperJar.resolve(buildDir.resolve("paper"));
        server = TestServerDir.create(buildDir.resolve("server"), paperJar, pluginJar);

        console = ProcessConsole.start(server.root(), buildDir.resolve("server-console.log"),
                List.of("java", "-Xms1G", "-Xmx2G", "-jar",
                        paperJar.getFileName().toString(), "nogui"));
        console.await("Done (", BOOT);
    }

    @AfterAll
    void stopServer() throws Exception {
        if (console != null) {
            console.stopAndWait(Duration.ofMinutes(2));
            console.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("本番の jar が 26.1 のサーバーでも読み込まれ、テーブルが 8 レベル 3 クレートで揃う")
    void pluginLoads() throws Exception {
        console.send("raidevent status");
        console.await("レベル: 8", Duration.ofSeconds(30));
        assertTrue(console.sawLine("クレート: 3"), "クレートが3種登録されていない" + console.tail());
    }

    @Test
    @Order(2)
    @DisplayName("生成 → 地図配布 → 接近で発火 → 全ウェーブ討伐 → 報酬チェスト設置")
    void fullRaidRun() throws Exception {
        // 発火すると地図は回収されるので、ボットが地図を観測してから地点へ運ぶ (2 段階)
        int[] markHolder = {0};
        BotRunner.Result result = BotRunner.run(botDir, "raid_run",
                TestServerDir.SERVER_PORT, BOT, observation -> {
                    try {
                        if (observation.event().equals("spawned")) {
                            // モブに殴られても死なないようにしてから、L5 マジカルを生成する
                            console.send("effect give E2eRaider minecraft:resistance 99999 255 true");
                            markHolder[0] = console.mark();
                            console.send("raidevent spawn 5 magical E2eRaider");
                            console.awaitSince("レイド生成:", markHolder[0], Duration.ofSeconds(30));
                            return;
                        }
                        if (!observation.event().equals("map_received")) {
                            return;
                        }
                        int mark = markHolder[0];
                        int[] site = coords(SITE, mark);

                        // 地点へ運んで発火させる (16m 以内に入ればトリガー)
                        console.send("tp E2eRaider %d %d %d".formatted(
                                site[0], site[1] + 1, site[2]));
                        console.awaitSince("ウェーブ 1/5", mark, Duration.ofSeconds(30));

                        // 共通タグを kill してウェーブを進める。ウェーブ間隔 (40 tick) が
                        // あるので、成功が出るまで殴り続ける
                        Instant deadline = Instant.now().plus(Duration.ofMinutes(4));
                        while (Instant.now().isBefore(deadline)
                                && !console.sawLine("レイド成功")) {
                            console.send("kill @e[tag=raidevent]");
                            Thread.sleep(2000);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("中断された", e);
                    } catch (Exception e) {
                        throw new AssertionError("レイドの駆動に失敗", e);
                    }
                });
        assertEquals(0, result.exitCode(), "ボットが失敗した" + result.describe());

        assertTrue(result.first("map_received").isPresent(),
                "レイドの地図が配られていない" + result.describe());
        var success = result.first("raid_success_seen").orElseThrow(
                () -> new AssertionError("成功の告知を観測できていない" + result.describe()));
        assertTrue(success.has("seen", "true"),
                "全ウェーブを倒したのに成功の告知が来ない" + result.describe() + console.tail());

        assertTrue(console.sawLine("ウェーブ 5/5"),
                "L5 なのに最終ウェーブまで進んでいない" + console.tail());

        // 報酬チェストが実際に置かれているかをサーバー側で確かめる
        int[] chest = coords(CHEST);
        console.send("execute if block %d %d %d chest run say RAIDEVENT_CHEST_OK"
                .formatted(chest[0], chest[1], chest[2]));
        console.await("RAIDEVENT_CHEST_OK", Duration.ofSeconds(15));
    }

    @Test
    @Order(3)
    @DisplayName("発火後にプレイヤーが消えてモンスターがデスポーンすると、レイドはキャンセルされる")
    void cancelsWhenMobsDespawn() throws Exception {
        BotRunner.Result result = BotRunner.run(botDir, "raid_cancel_watch",
                TestServerDir.SERVER_PORT, Duration.ofMinutes(4), observation -> {
                    if (!observation.event().equals("spawned")) {
                        return;
                    }
                    try {
                        console.send("effect give E2eWatcher minecraft:resistance 99999 255 true");
                        int mark = console.mark();
                        console.send("raidevent spawn 1 miner E2eWatcher");
                        console.awaitSince("レイド生成:", mark, Duration.ofSeconds(30));
                        int[] site = coords(SITE, mark);
                        console.send("tp E2eWatcher %d %d %d".formatted(
                                site[0], site[1] + 1, site[2]));
                        console.awaitSince("ウェーブ 1/2", mark, Duration.ofSeconds(30));

                        // 300 ブロック逃がす。唯一のプレイヤーから 128m を超えたモブは
                        // 即デスポーン対象になる。落下は resistance 255 が受け止める。
                        // チャンクが先にアンロードされた場合も UNLOAD で同じ扱いになる
                        console.send("tp E2eWatcher %d 200 %d".formatted(
                                site[0] + 300, site[2]));
                        // 保険の放棄検出 (abandon-ticks=1200) より先に、デスポーン起点の
                        // キャンセルが出るはず
                        console.awaitSince("レイドキャンセル", mark, Duration.ofSeconds(90));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("中断された", e);
                    } catch (Exception e) {
                        throw new AssertionError("キャンセルの駆動に失敗", e);
                    }
                });
        assertEquals(0, result.exitCode(), "ボットが失敗した" + result.describe());
        var cancel = result.first("cancel_seen").orElseThrow(
                () -> new AssertionError("キャンセル告知を観測できていない" + result.describe()));
        assertTrue(cancel.has("seen", "true"),
                "デスポーンしたのにキャンセルの告知が来ない" + result.describe() + console.tail());
    }

    @Test
    @Order(4)
    @DisplayName("誰も近づかないまま半日経つと地図は失効する")
    void expiresAfterHalfDay() throws Exception {
        BotRunner.Result result = BotRunner.run(botDir, "raid_expire_watch",
                TestServerDir.SERVER_PORT, Duration.ofMinutes(3), observation -> {
                    if (!observation.event().equals("spawned")) {
                        return;
                    }
                    try {
                        console.send("raidevent spawn 2 soldier E2eExpirer");
                        console.await("レイド生成:", Duration.ofSeconds(30));
                        // 半日ぶん時間を送る。daily-chance は 0 なので新しい抽選は起きない
                        console.send("time add 12100");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("中断された", e);
                    } catch (Exception e) {
                        throw new AssertionError("失効の駆動に失敗", e);
                    }
                });
        assertEquals(0, result.exitCode(), "ボットが失敗した" + result.describe());
        var expire = result.first("expire_seen").orElseThrow(
                () -> new AssertionError("失効を観測できていない" + result.describe()));
        assertTrue(expire.has("seen", "true"),
                "半日経ったのに失効の告知が来ない" + result.describe() + console.tail());
        assertTrue(console.sawLine("レイド失効"), "サーバー側に失効ログが無い" + console.tail());
    }

    @Test
    @Order(5)
    @DisplayName("一連の検証でサーバー側に例外が出ていない")
    void noServerExceptions() {
        assertFalse(console.sawLine("Caused by:"),
                "サーバーで例外が起きている" + console.tail());
        assertTrue(console.isAlive(), "サーバーが落ちている" + console.tail());
    }

    @Test
    @Order(6)
    @DisplayName("下準備のコンソールコマンドが黙って失敗していない")
    void setupCommandsSucceeded() {
        assertFalse(console.sawLine("Incorrect argument for command"),
                "下準備のコマンドが引数エラーで通っていない" + console.tail());
        assertFalse(console.sawLine("Unknown or incomplete command"),
                "下準備のコマンドが認識されていない" + console.tail());
    }

    /** コンソールログから直近の座標を取り出す。 */
    private int[] coords(Pattern pattern) {
        return coords(pattern, 0);
    }

    /** mark 以降で最新のもの。前のテストのレイドの座標を拾わないため。 */
    private int[] coords(Pattern pattern, int mark) {
        // 最新のものが欲しいので後ろから探す
        List<String> lines = console.lines();
        for (int i = lines.size() - 1; i >= mark; i--) {
            Matcher matcher = pattern.matcher(lines.get(i));
            if (matcher.find()) {
                return new int[] {Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3))};
            }
        }
        throw new AssertionError("座標のログが見つからない: " + pattern + console.tail());
    }
}
