plugins {
    id("recast.kotlin-module")
}

dependencies {
    api(project(":recast-editor-core"))
    api(project(":recast-render"))
    api(libs.imgui.binding)
}
