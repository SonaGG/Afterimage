plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.fabric.loom)
    alias(libs.plugins.ploceus)
}

ploceus {
    setIntermediaryGeneration(2)
}

repositories {
    maven("https://jitpack.io") {
        content {
            includeGroupByRegex("com[.]github[.].*")
        }
        name = "JitPack"
    }
    maven(url = "https://maven.cloverclient.com/releases")
    maven(url = "https://maven.legacyfabric.net/") { name = "legacy-fabric" }
    mavenCentral()
    exclusiveContent {
        forRepository { mavenCentral() }
        filter { includeGroup("org.lwjgl") }
    }
}

val bundledModules = listOf(
    ":recast-core",
    ":recast-net",
    ":recast-protocol",
    ":recast-format",
    ":recast-capture",
    ":recast-replay",
    ":recast-camera",
    ":recast-clip",
    ":recast-flashback",
    ":recast-index",
    ":recast-editor-core",
    ":recast-editor-imgui",
    ":recast-render",
)

fun DependencyHandlerScope.bundled(dependency: Any) {
    add("implementation", dependency)
    add("include", dependency)
}

dependencies {
    minecraft(libs.minecraft)
    mappings(loom.layered {
        mappings(ploceus.featherMappings(property("feather.build") as String))
        mappings(rootProject.file("mappings/feather-overrides.tiny"))
    })

    modImplementation(libs.lenis)
    modImplementation(libs.fabric.loader)
    modImplementation(libs.devauth.fabric)
    ploceus.dependOsl(libs.versions.osl.get())

    implementation(project(path = ":recast-mc-adapter", configuration = "namedElements"))
    include(project(":recast-mc-adapter"))

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

configurations.all {
    exclude(group = "org.lwjgl.lwjgl")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjvm-default=all")
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.processResources {
    inputs.property("version", project.version)
    filteringCharset = "UTF-8"
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

loom {
    runConfigs.named("client") {
        programArgs("--width", "1920", "--height", "1080")
        jvmArguments.add("-Ddevauth.enabled=true")
        jvmArguments.add("-Ddevauth.account=alt")
        environmentVariable("RECAST_DEV", "1")
    }
}
