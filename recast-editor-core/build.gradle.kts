plugins {
    id("recast.kotlin-module")
}

dependencies {
    api(project(":recast-clip"))
    api(project(":recast-flashback"))
    api(project(":recast-index"))
}

tasks.register<JavaExec>("momentTool") {
    group = "recast"
    description = "Detect moments in a recording: -Precording=<file>"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("gg.sona.recast.editor.MomentToolKt")
    args(listOf(project.findProperty("recording")?.toString() ?: ""))
}

