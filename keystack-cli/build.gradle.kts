val cliktVersion: String by project
val ktorVersion: String by project

dependencies {
    implementation(project(":keystack-gateway"))
    implementation(project(":keystack-provider"))
    implementation("com.github.ajalt.clikt:clikt-jvm:$cliktVersion")
    implementation("io.ktor:ktor-server-cio-jvm:$ktorVersion")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "keystack.cli.MainKt"
    }
}
