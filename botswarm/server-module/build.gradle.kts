plugins {
    id("base-conventions")
}

dependencies {
    implementation(libs.fastutil)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.coroutines.core)
    implementation(libs.netty.buffer)
    implementation(libs.rsprot.api)
    implementation(projects.api.net)
    implementation(projects.api.parsers.json)
    implementation(projects.api.pluginCommons)
    implementation(projects.api.realm)
    implementation(projects.api.registry)
    implementation(projects.engine.utilsBits)
}
