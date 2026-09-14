plugins {
    id("afterimage.kotlin-module")
}

dependencies {
    api(project(":afterimage-flashback"))
    implementation(libs.zstd.jni)
}

tasks.register<JavaExec>("indexTool") {
    group = "afterimage"
    description = "Index a recording and run search queries against it: -Precording=<file> -Pquery=<q1>;<q2>"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("gg.sona.afterimage.index.IndexToolKt")
    val recording = project.findProperty("recording")?.toString() ?: ""
    val queries = project.findProperty("query")?.toString()?.split(';')?.filter { it.isNotBlank() } ?: emptyList()
    args(listOf(recording) + queries)
}
