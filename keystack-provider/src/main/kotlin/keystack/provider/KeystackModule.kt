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
import org.koin.core.context.startKoin

val keystackModule = module {
    single<ServiceRegistry> { ServiceRegistry(get()) }
    single<ServiceProvider> { SqsProvider() }
    single<ServiceProvider> { S3Provider() }
    single<ServiceProvider> { DynamoDbProvider() }
    single<ServiceProvider> { SnsProvider() }
    single<ServiceProvider> { LambdaProvider() }
    single<ServiceProvider> { IamProvider() }
    single<ServiceProvider> { StsProvider() }
    single<ServiceProvider> { CloudWatchProvider() }
    single<ServiceProvider> { CloudFormationProvider(get()) }
}

fun initKeystack() {
    startKoin {
        modules(keystackModule)
    }
}
