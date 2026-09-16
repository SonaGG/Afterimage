plugins {
    alias(libs.plugins.fabric.loom) apply false
    alias(libs.plugins.fabric.loom.plain) apply false
    alias(libs.plugins.ploceus) apply false
}

allprojects {
    group = property("project.group") as String
    version = property("project.version") as String
}
