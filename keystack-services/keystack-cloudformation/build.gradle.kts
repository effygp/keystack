val ktorVersion: String by project
val kotlinxSerializationVersion: String by project
val jacksonVersion: String by project
val koinVersion: String by project
val coroutinesVersion: String by project
val slf4jVersion: String by project

dependencies {
    implementation(project(":keystack-protocol"))
    implementation(project(":keystack-state"))
    
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-xml:$jacksonVersion")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:$jacksonVersion")
    
    implementation("io.insert-koin:koin-core:$koinVersion")
    
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("org.slf4j:slf4j-api:$slf4jVersion")
    
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("io.mockk:mockk:1.13.13")
}
