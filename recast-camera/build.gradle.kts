plugins {
    id("recast.kotlin-module")
}

dependencies {
    api(project(":recast-core"))
    api(libs.joml)
}
