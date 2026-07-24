plugins {
    java
}

dependencies {
    implementation(project(":bridge-core"))
    compileOnly("net.william278.husktowns:husktowns-bukkit:${property("husktownsVersion")}")
}
