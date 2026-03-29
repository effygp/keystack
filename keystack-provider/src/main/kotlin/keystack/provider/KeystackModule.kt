package keystack.provider

import keystack.services.sqs.SqsProvider
import keystack.services.s3.S3Provider
import keystack.services.dynamodb.DynamoDbProvider
import keystack.services.sns.SnsProvider
import keystack.services.lambda.LambdaProvider
import keystack.services.iam.IamProvider
import keystack.services.sts.StsProvider
import keystack.services.cloudwatch.CloudWatchProvider
import keystack.services.cloudformation.CloudFormationProvider
import org.koin.dsl.module
import org.koin.dsl.bind
import org.koin.core.context.startKoin

val keystackModule = module {
    single { ServiceRegistry() }
    
    single { SqsProvider() } bind ServiceProvider::class
    single { S3Provider() } bind ServiceProvider::class
    single { DynamoDbProvider() } bind ServiceProvider::class
    single { SnsProvider() } bind ServiceProvider::class
    single { LambdaProvider() } bind ServiceProvider::class
    single { IamProvider() } bind ServiceProvider::class
    single { StsProvider() } bind ServiceProvider::class
    single { CloudWatchProvider() } bind ServiceProvider::class
    single { CloudFormationProvider(get()) } bind ServiceProvider::class
}

fun initKeystack() {
    try {
        startKoin {
            modules(keystackModule)
        }
    } catch (e: Exception) {
        // Already started
    }
}
