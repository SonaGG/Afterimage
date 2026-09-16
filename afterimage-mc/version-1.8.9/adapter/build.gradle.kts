plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.fabric.loom)
    alias(libs.plugins.ploceus)
}

base {
    archivesName.set("afterimage-mc-1.8.9-adapter")
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
    maven("https://maven.legacyfabric.net/") { name = "legacy-fabric" }
    mavenCentral()
    exclusiveContent {
        forRepository { mavenCentral() }
        filter { includeGroup("org.lwjgl") }
    }
}

dependencies {
    minecraft(libs.minecraft)
    mappings(loom.layered {
        mappings(ploceus.featherMappings(property("feather.build") as String))
        mappings(rootProject.file("mappings/feather-overrides.tiny"))
    })

    modImplementation(libs.lenis)
    modImplementation(libs.fabric.loader)
    ploceus.dependOsl(libs.versions.osl.get())

    api(project(":afterimage-gfx-gl"))
    api(project(":afterimage-mc:common"))
    api(project(":afterimage-mc:version-1.8.9:state"))
    api(project(":afterimage-replay"))
    api(project(":afterimage-camera"))
    api(project(":afterimage-clip"))
    api(project(":afterimage-flashback"))
    api(project(":afterimage-editor-core"))
    api(project(":afterimage-render"))
    api(project(":afterimage-editor-imgui"))

    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")
}

loom {
    accessWidenerPath.set(file("src/main/resources/afterimage-adapter.accesswidener"))
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
