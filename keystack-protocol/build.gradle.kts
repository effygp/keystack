val ktorVersion: String by project
val kotlinxSerializationVersion: String by project
val jacksonVersion: String by project
val slf4jVersion: String by project

dependencies {
    implementation(project(":keystack-gateway"))
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-xml:$jacksonVersion")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-cbor:$jacksonVersion")
    implementation("org.slf4j:slf4j-api:$slf4jVersion")
    
    testImplementation("org.jetbrains.kotlin:kotlin-test")
}
