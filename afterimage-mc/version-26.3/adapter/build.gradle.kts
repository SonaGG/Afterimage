plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.fabric.loom.plain)
}

base {
    archivesName.set("afterimage-mc-26.3-adapter")
}

repositories {
    mavenCentral()
}

dependencies {
    minecraft(libs.minecraft26)
    implementation(libs.fabric.loader)

    api(project(":afterimage-mc:common"))
    api(project(":afterimage-gfx"))
    api(project(":afterimage-packets"))
    api(project(":afterimage-replay"))
    api(project(":afterimage-camera"))
    api(project(":afterimage-clip"))
    api(project(":afterimage-flashback"))
    api(project(":afterimage-editor-core"))
    api(project(":afterimage-render"))
    api(project(":afterimage-editor-imgui"))
}

loom {
    accessWidenerPath.set(file("src/main/resources/afterimage-adapter.accesswidener"))
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjvm-default=all")
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

val modVersion = project.version.toString().substringBefore("+") + "+mc26.3"

tasks.processResources {
    inputs.property("version", modVersion)
    filteringCharset = "UTF-8"
    filesMatching("fabric.mod.json") {
        expand("version" to modVersion)
    }
}
