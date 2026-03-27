val kotlinxSerializationVersion: String by project
val koinVersion: String by project
val coroutinesVersion: String by project
val slf4jVersion: String by project
val jacksonVersion: String by project

plugins {
    kotlin("plugin.serialization")
}

dependencies {
    implementation(project(":keystack-gateway"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")
    implementation("io.insert-koin:koin-core:$koinVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("org.slf4j:slf4j-api:$slf4jVersion")
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")

    testImplementation("org.jetbrains.kotlin:kotlin-test")
}
