plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.fabric.loom.plain)
}

base {
    archivesName.set("afterimage-mc-26.3-loader")
}

repositories {
    mavenCentral()
    maven(url = "https://maven.cloverclient.com/releases")
    maven(url = "https://maven.terraformersmc.com/releases")
}

val bundledModules = listOf(
    ":afterimage-core",
    ":afterimage-world",
    ":afterimage-gfx",
    ":afterimage-net",
    ":afterimage-packets",
    ":afterimage-format",
    ":afterimage-capture",
    ":afterimage-replay",
    ":afterimage-camera",
    ":afterimage-clip",
    ":afterimage-flashback",
    ":afterimage-index",
    ":afterimage-editor-core",
    ":afterimage-editor-imgui",
    ":afterimage-render",
    ":afterimage-mc:common",
)

fun DependencyHandlerScope.bundled(dependency: Any) {
    add("implementation", dependency)
    add("include", dependency)
}

dependencies {
    minecraft(libs.minecraft26)
    implementation(libs.fabric.loader)
    implementation(libs.devauth.fabric)

    implementation(project(":afterimage-mc:version-26.3:adapter"))
    include(project(":afterimage-mc:version-26.3:adapter"))

    for (module in bundledModules) {
        bundled(project(module))
    }

    bundled(kotlin("stdlib"))
    bundled(libs.joml.get().toString())
    bundled(libs.zstd.jni.get().toString())
    bundled(libs.javacpp.core.get().toString())
    bundled(libs.javacpp.ffmpeg.get().toString())
    bundled(libs.imgui.binding.get().toString())
    bundled(libs.imgui.natives.windows.get().toString())
    bundled(libs.imgui.natives.linux.get().toString())
    bundled(libs.imgui.natives.macos.get().toString())
    bundled("net.java.dev.jna:jna:5.14.0")
    bundled("net.java.dev.jna:jna-platform:5.14.0")
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

loom {
    runConfigs.named("client") {
        programArgs("--width", "1920", "--height", "1080")
        jvmArguments.add("-Ddevauth.enabled=true")
        jvmArguments.add("-Ddevauth.account=alt")
        environmentVariable("AFTERIMAGE_DEV", "1")
    }
}
