plugins {
    id("afterimage.kotlin-module")
}

base {
    archivesName.set("afterimage-mc-1.8.9-protocol")
}

dependencies {
    api(project(":afterimage-packets"))
}
