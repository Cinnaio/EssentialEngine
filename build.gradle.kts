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
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(property("javaVersion") as String))
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${property("paperVersion")}")
    compileOnly("net.william278.husktowns:husktowns-bukkit:${property("husktownsVersion")}")

    // REST API 模块用到，会被 shadow 重定位后打进 jar
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.code.gson:gson:2.11.0")

    // 说明：Vault 通过运行时动态代理对接，无需编译依赖；
    //       SQLite / MySQL 驱动在首次使用 SQL 存储时自动下载，同样不打进 jar。
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
