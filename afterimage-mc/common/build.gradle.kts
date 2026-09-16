plugins {
    id("afterimage.kotlin-module")
}

base {
    archivesName.set("afterimage-mc-common")
}

dependencies {
    api(project(":afterimage-gfx"))
    api(project(":afterimage-replay"))
    api(project(":afterimage-camera"))
    api(project(":afterimage-clip"))
    api(project(":afterimage-flashback"))
    api(project(":afterimage-editor-core"))
    api(project(":afterimage-render"))
    api(project(":afterimage-editor-imgui"))
    compileOnly(libs.lwjgl.core)
    compileOnly(libs.lwjgl.sdl)
    compileOnly("org.apache.logging.log4j:log4j-api:2.17.1")
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")
}
