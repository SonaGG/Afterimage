plugins {
    id("recast.kotlin-module")
}

dependencies {
    api(project(":recast-replay"))
    api(project(":recast-camera"))
}
