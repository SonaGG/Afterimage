plugins {
    id("afterimage.kotlin-module")
}

dependencies {
    api(project(":afterimage-net"))
    api(project(":afterimage-protocol"))
    implementation(libs.zstd.jni)
}
