plugins {
    id("recast.kotlin-module")
}

dependencies {
    api(project(":recast-editor-core"))
    api(libs.javacpp.core)
    api(libs.javacpp.ffmpeg)
}
