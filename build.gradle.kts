plugins {
    java
    id("com.gradleup.shadow") version "8.3.5"
}

group = "com.github.cinnaio.essentialengine"
version = property("pluginVersion") as String

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    // HuskTowns（可选前置，仅编译期需要）
    maven("https://repo.william278.net/releases")
    // PlaceholderAPI（可选前置，仅编译期需要）
    maven("https://repo.extendedclip.com/releases/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(property("javaVersion") as String))
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${property("paperVersion")}")
    compileOnly("net.william278.husktowns:husktowns-bukkit:${property("husktownsVersion")}")
    compileOnly("me.clip:placeholderapi:${property("placeholderApiVersion")}")

    // REST API 模块用到，会被 shadow 重定位后打进 jar
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.code.gson:gson:2.11.0")

    // 说明：Vault 通过运行时动态代理对接，无需编译依赖；
    //       SQLite / MySQL 驱动在首次使用 SQL 存储时自动下载，同样不打进 jar。

    // 测试只覆盖不依赖服务端运行时的纯逻辑（时长解析、余额并发、JWT 校验等），
    // 需要 Bukkit 实例的部分不在这里测，那属于上服验证的范畴。
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testCompileOnly("io.papermc.paper:paper-api:${property("paperVersion")}")
    testRuntimeOnly("io.papermc.paper:paper-api:${property("paperVersion")}")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:-deprecation")
}

tasks.withType<ProcessResources> {
    filteringCharset = "UTF-8"
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand(mapOf("version" to project.version))
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveBaseName.set("EssentialEngine")
    relocate("fi.iki.elonen", "com.github.cinnaio.essentialengine.libs.nanohttpd")
    relocate("com.google.gson", "com.github.cinnaio.essentialengine.libs.gson")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
