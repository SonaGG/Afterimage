pluginManagement {
    repositories {
        maven(url = "https://maven.fabricmc.net") { name = "Fabric" }
        maven(url = "https://maven.ornithemc.net/releases") { name = "Ornithe Releases" }
        maven(url = "https://maven.ornithemc.net/snapshots") { name = "Ornithe Snapshots" }
        mavenLocal()
        gradlePluginPortal()
    }
}

rootProject.name = "recast"

include(
    "recast-core",
    "recast-net",
    "recast-protocol",
    "recast-format",
    "recast-capture",
    "recast-replay",
    "recast-camera",
    "recast-clip",
    "recast-flashback",
    "recast-index",
    "recast-editor-core",
    "recast-editor-imgui",
    "recast-render",
    "recast-mc-adapter",
    "recast-loader",
)
