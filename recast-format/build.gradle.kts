plugins {
    id("recast.kotlin-module")
}

dependencies {
    api(project(":recast-net"))
    api(project(":recast-protocol"))
    implementation(libs.zstd.jni)
}
