plugins {
    id("afterimage.kotlin-module")
}

dependencies {
    api(project(":afterimage-gfx"))
    compileOnly(libs.lwjgl.core)
    compileOnly(libs.lwjgl.opengl)
}
