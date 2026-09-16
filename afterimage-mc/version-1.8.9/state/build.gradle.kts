plugins {
    id("afterimage.kotlin-module")
}

base {
    archivesName.set("afterimage-mc-1.8.9-state")
}

dependencies {
    api(project(":afterimage-mc:version-1.8.9:protocol"))
    api(project(":afterimage-replay"))
}
