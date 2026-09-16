plugins {
    id("afterimage.kotlin-module")
}

dependencies {
    api(project(":afterimage-gfx"))
    api(project(":afterimage-editor-core"))
    api(libs.javacpp.core)
    api(libs.javacpp.ffmpeg)
}
