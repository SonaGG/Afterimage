plugins {
    id("recast.kotlin-module")
}

dependencies {
    api(project(":recast-capture"))
    api(project(":recast-protocol"))
}
