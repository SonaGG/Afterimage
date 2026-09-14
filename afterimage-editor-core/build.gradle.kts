plugins {
    id("afterimage.kotlin-module")
}

dependencies {
    api(project(":afterimage-clip"))
    api(project(":afterimage-flashback"))
    api(project(":afterimage-index"))
}

tasks.register<JavaExec>("momentTool") {
    group = "afterimage"
    description = "Detect moments in a recording: -Precording=<file>"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("gg.sona.afterimage.editor.MomentToolKt")
    args(listOf(project.findProperty("recording")?.toString() ?: ""))
}

