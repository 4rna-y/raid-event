package io.github.raidevent.e2e;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * E2E 用の Paper サーバー jar を用意する。
 *
 * <p>本番は 26.2 だが、ヘッドレスクライアント (mineflayer) が 26.2 のプロトコルに
 * 未対応なので、テストサーバーだけ 26.1 を使う。載せるプラグインは本番と同じ成果物。
 *
 * <p>キャッシュ → 手元の既存 jar → ダウンロード の順に探す。
 */
final class PaperJar {

    /** ヘッドレスクライアントが対応している最新のバージョン。 */
    static final String VERSION = "26.1.2";

    private static final String API = "https://fill.papermc.io/v3/projects/paper";
    private static final String USER_AGENT = "raidevent-e2e/1.0 (local test)";

    private PaperJar() {
    }

    /**
     * jar のパスを返す。無ければ取得する。
     *
     * @param cacheDir ダウンロード先
     */
    static Path resolve(Path cacheDir) throws IOException, InterruptedException {
        Optional<Path> cached = findCached(cacheDir);
        if (cached.isPresent()) {
            return cached.get();
        }
        Files.createDirectories(cacheDir);
        return download(cacheDir);
    }

    private static Optional<Path> findCached(Path cacheDir) throws IOException {
        if (!Files.isDirectory(cacheDir)) {
            return Optional.empty();
        }
        try (var entries = Files.list(cacheDir)) {
            return entries
                    .filter(path -> path.getFileName().toString().startsWith("paper-" + VERSION))
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .findFirst();
        }
    }

    private static Path download(Path cacheDir) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        String builds = get(client, API + "/versions/" + VERSION + "/builds");

        // 一番上の STABLE ビルドの server:default を拾う。JSON は素朴に正規表現で読む
        Matcher build = Pattern.compile(
                "\"server:default\":\\{\"name\":\"([^\"]+)\","
                        + "\"checksums\":\\{\"sha256\":\"([0-9a-f]+)\"\\},"
                        + "\"size\":\\d+,\"url\":\"([^\"]+)\"")
                .matcher(builds);
        if (!build.find()) {
            throw new IOException(VERSION + " の STABLE ビルドが見つからない");
        }
        String name = build.group(1);
        String sha256 = build.group(2);
        String url = build.group(3);

        Path target = cacheDir.resolve(name);
        Path temporary = cacheDir.resolve(name + ".tmp");
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT).build();
        try (InputStream in = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
                .body()) {
            Files.copy(in, temporary, StandardCopyOption.REPLACE_EXISTING);
        }

        String actual = sha256Of(temporary);
        if (!actual.equals(sha256)) {
            Files.deleteIfExists(temporary);
            throw new IOException("ダウンロードした jar の SHA-256 が合わない: " + actual);
        }
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    private static String get(HttpClient client, String url)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT).build();
        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException(url + " が " + response.statusCode() + " を返した");
        }
        return response.body();
    }

    private static String sha256Of(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 が使えない", e);
        }
    }
}
