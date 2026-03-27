plugins {
    id("com.github.johnrengelman.shadow")
}

val cliktVersion: String by project
val ktorVersion: String by project

dependencies {
    implementation(project(":keystack-gateway"))
    implementation(project(":keystack-provider"))
    implementation(project(":keystack-protocol"))
    implementation(project(":keystack-state"))
    
    // Core Services
    implementation(project(":keystack-services:keystack-sqs"))
    implementation(project(":keystack-services:keystack-s3"))
    implementation(project(":keystack-services:keystack-dynamodb"))
    implementation(project(":keystack-services:keystack-sns"))
    implementation(project(":keystack-services:keystack-lambda"))
    implementation(project(":keystack-services:keystack-iam"))
    implementation(project(":keystack-services:keystack-sts"))
    implementation(project(":keystack-services:keystack-cloudwatch"))
    implementation(project(":keystack-services:keystack-cloudformation"))

    implementation("com.github.ajalt.clikt:clikt-jvm:$cliktVersion")
    implementation("io.ktor:ktor-server-cio-jvm:$ktorVersion")
    implementation("org.slf4j:slf4j-api:2.0.16")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.16")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "keystack.cli.MainKt"
    }
}

tasks.shadowJar {
    archiveBaseName.set("keystack")
    archiveClassifier.set("all")
    archiveVersion.set("")
}
