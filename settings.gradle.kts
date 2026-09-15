pluginManagement {
    repositories {
        maven(url = "https://maven.fabricmc.net") { name = "Fabric" }
        maven(url = "https://maven.ornithemc.net/releases") { name = "Ornithe Releases" }
        maven(url = "https://maven.ornithemc.net/snapshots") { name = "Ornithe Snapshots" }
        mavenLocal()
        gradlePluginPortal()
    }
}

rootProject.name = "afterimage"

include(
    "afterimage-core",
    "afterimage-world",
    "afterimage-gfx",
    "afterimage-gfx-gl",
    "afterimage-net",
    "afterimage-packets",
    "afterimage-format",
    "afterimage-capture",
    "afterimage-replay",
    "afterimage-camera",
    "afterimage-clip",
    "afterimage-flashback",
    "afterimage-index",
    "afterimage-editor-core",
    "afterimage-editor-imgui",
    "afterimage-render",
    "afterimage-mc:common",
    "afterimage-mc:version-1.8.9:protocol",
    "afterimage-mc:version-1.8.9:state",
    "afterimage-mc:version-1.8.9:adapter",
    "afterimage-mc:version-1.8.9:loader",
    "afterimage-mc:version-26.3:adapter",
    "afterimage-mc:version-26.3:loader",
)
