import java.time.Duration

plugins {
    java
}

group = "io.github.raidevent"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
}

val botDir = layout.projectDirectory.dir("bot")

/** ヘッドレスクライアントの依存を入れる。入っていればスキップする。 */
val installBot = tasks.register<Exec>("installBot") {
    description = "e2e 用ヘッドレスクライアントの npm 依存を入れる"
    workingDir = botDir.asFile
    commandLine("npm", "install", "--no-audit", "--no-fund")
    onlyIf { !botDir.dir("node_modules/mineflayer").asFile.isDirectory }
    // npm が無い環境ではテスト側が assumeTrue で飛ばすので、ここでは失敗させない
    isIgnoreExitValue = true
}

// 実サーバーを起動するので数分かかる。gradle build には載せない。
tasks.test {
    enabled = false
}

tasks.register<Test>("e2eTest") {
    group = "verification"
    description = "本番の jar を 26.1 のサーバーに載せ、ヘッドレスクライアントで通し検証する (数分かかる)"

    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    dependsOn(installBot, ":plugin:jar")

    useJUnitPlatform()
    // 1つのサーバーの一生を順番に検証するので、並列実行はしない。
    maxParallelForks = 1

    // 外部プロセス (サーバー / ボット) と実ファイルを相手にするので、
    // Gradle の入力だけでは結果の鮮度を判断できない。呼ばれたら必ず走らせる。
    outputs.upToDateWhen { false }
    // それとは別に、プラグインを直せば再実行すべきなのを入力として明示しておく。
    inputs.file(project(":plugin").tasks.named<Jar>("jar").flatMap { it.archiveFile })
        .withPropertyName("pluginJar")
    timeout = Duration.ofMinutes(30)

    systemProperty("raidevent.buildDir", layout.buildDirectory.get().asFile.absolutePath)
    systemProperty("raidevent.botDir", botDir.asFile.absolutePath)
    doFirst {
        val jar = project(":plugin").tasks.named<Jar>("jar").get().archiveFile.get().asFile
        systemProperty("raidevent.pluginJar", jar.absolutePath)
    }

    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
