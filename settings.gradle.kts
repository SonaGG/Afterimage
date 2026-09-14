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
    "afterimage-net",
    "afterimage-protocol",
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
    "afterimage-mc-adapter",
    "afterimage-loader",
)
