plugins {
    java
}

group = "io.github.raidevent"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.119-stable")

    // テスト側でも Bukkit の型 (YamlConfiguration など) を触るのでクラスパスに要る。
    testImplementation("io.papermc.paper:paper-api:26.2.build.119-stable")
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.4")
    testImplementation("org.mockito:mockito-core:5.23.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // Paper 26.x は Java 25 で動作するため、それに合わせる
    options.release = 25
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.processResources {
    filesMatching("paper-plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    archiveBaseName = "RaidEvent"
}
