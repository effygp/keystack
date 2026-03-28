val ktorVersion: String by project
val koinVersion: String by project
val slf4jVersion: String by project

dependencies {
    implementation(project(":keystack-protocol"))
    implementation(project(":keystack-state"))
    
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("org.slf4j:slf4j-api:$slf4jVersion")
    
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("io.mockk:mockk:1.13.13")
}
