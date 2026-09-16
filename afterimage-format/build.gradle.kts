plugins {
    id("afterimage.kotlin-module")
}

dependencies {
    api(project(":afterimage-net"))
    implementation(libs.zstd.jni)
}
