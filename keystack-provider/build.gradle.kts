val ktorVersion: String by project
val koinVersion: String by project
val coroutinesVersion: String by project
val slf4jVersion: String by project

dependencies {
    implementation(project(":keystack-protocol"))
    implementation(project(":keystack-services:keystack-sqs"))
    implementation(project(":keystack-services:keystack-s3"))
    implementation(project(":keystack-services:keystack-dynamodb"))
    implementation(project(":keystack-services:keystack-sns"))
    implementation(project(":keystack-services:keystack-lambda"))
    implementation(project(":keystack-services:keystack-iam"))
    implementation(project(":keystack-services:keystack-sts"))
    implementation(project(":keystack-services:keystack-cloudwatch"))
    implementation(project(":keystack-services:keystack-cloudformation"))
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.insert-koin:koin-core:$koinVersion")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("org.slf4j:slf4j-api:$slf4jVersion")
    
    testImplementation("org.jetbrains.kotlin:kotlin-test")
}
