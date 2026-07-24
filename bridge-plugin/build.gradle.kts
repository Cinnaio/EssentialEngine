plugins {
    java
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":bridge-core"))
    implementation(project(":bridge-servercore"))
    implementation(project(":bridge-husktowns"))
    compileOnly("net.william278.husktowns:husktowns-bukkit:${property("husktownsVersion")}")
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveBaseName.set("LinkEngine")
    relocate("fi.iki.elonen", "com.github.cinnaio.linkengine.libs.nanohttpd")
    relocate("com.google.gson", "com.github.cinnaio.linkengine.libs.gson")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
