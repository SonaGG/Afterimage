plugins {
    id("afterimage.kotlin-module")
}

dependencies {
    api(project(":afterimage-editor-core"))
    api(project(":afterimage-render"))
    api(libs.imgui.binding)
}
