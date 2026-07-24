plugins {
    `java-library`
}

dependencies {
    // Use api so NanoHTTPD/Gson types are visible to dependent modules
    api("org.nanohttpd:nanohttpd:2.3.1")
    api("com.google.code.gson:gson:2.11.0")
}
