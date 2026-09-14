plugins {
    id("afterimage.kotlin-module")
}

dependencies {
    api(project(":afterimage-core"))
    api(libs.joml)
}
