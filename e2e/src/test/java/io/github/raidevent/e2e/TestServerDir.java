package io.github.raidevent.e2e;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;

/**
 * E2E 用に使い捨てのサーバーディレクトリを組み立てる。
 *
 * <p>本番の {@code run/} には触らない。ポートは本番 (25565) とも Modifier の e2e
 * (25566/25567) ともずらしてあるので、他を起動したままテストを回せる。
 */
final class TestServerDir {

    static final int SERVER_PORT = 25568;

    private final Path root;

    private TestServerDir(Path root) {
        this.root = root;
    }

    Path root() {
        return root;
    }

    /**
     * サーバーディレクトリを作る。既にあれば作り直す。
     *
     * @param pluginJars 載せるプラグイン。本番と同じ成果物を渡すこと
     */
    static TestServerDir create(Path root, Path paperJar, Path... pluginJars) throws IOException {
        deleteRecursively(root);
        Files.createDirectories(root.resolve("plugins/RaidEvent"));

        Files.copy(paperJar, root.resolve(paperJar.getFileName()),
                StandardCopyOption.REPLACE_EXISTING);
        for (Path jar : pluginJars) {
            Files.copy(jar, root.resolve("plugins").resolve(jar.getFileName()),
                    StandardCopyOption.REPLACE_EXISTING);
        }

        // ローカル検証専用なので EULA は自動同意する。
        Files.writeString(root.resolve("eula.txt"), "eula=true\n");

        // online-mode=false はヘッドレスクライアントが参加するために要る。
        // spawn-monsters=false で自然湧きを止める (プラグインの API スポーンは影響を受けない)。
        // 自然湧きのモブがボットを殴ったり kill コマンドに巻き込まれたりしないように。
        // hardcore=false: wiah は載せないし、ボットの事故死でテストを壊さない。
        Files.writeString(root.resolve("server.properties"), """
                server-port=%d
                online-mode=false
                hardcore=false
                difficulty=easy
                spawn-monsters=false
                level-name=world
                level-seed=raidevent-e2e
                max-players=20
                view-distance=6
                simulation-distance=6
                spawn-protection=0
                sync-chunk-writes=false
                """.formatted(SERVER_PORT));

        // テスト用に距離と間隔を縮める。tiers / crates は同梱の既定値をそのまま使う
        // (書かなければ JavaPlugin が埋め込みの config.yml を既定値として引く)。
        // daily-chance: 0 で自然発生を止め、テストは管理コマンドで駆動する。
        Files.writeString(root.resolve("plugins/RaidEvent/config.yml"), """
                enabled: true
                message-prefix: "<gray>[<gold>RaidEvent<gray>]</gray> "
                raid:
                  daily-chance: 0.0
                  expire-ticks: 12000
                  trigger-radius: 16
                  min-distance: 40
                  max-distance: 60
                  participant-radius: 48
                  wave-interval-ticks: 40
                  check-period-ticks: 10
                  abandon-radius: 96
                  abandon-ticks: 1200
                """);

        return new TestServerDir(root);
    }

    static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(entry -> {
                try {
                    Files.delete(entry);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }
}
