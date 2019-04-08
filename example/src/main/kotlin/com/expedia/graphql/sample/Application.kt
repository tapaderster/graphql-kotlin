package com.expedia.graphql.sample

import com.expedia.graphql.DirectiveWiringHelper
import com.expedia.graphql.SchemaGeneratorConfig
import com.expedia.graphql.TopLevelObject
import com.expedia.graphql.execution.KotlinDataFetcherFactoryProvider
import com.expedia.graphql.hooks.SchemaGeneratorHooks
import com.expedia.graphql.sample.datafetchers.CustomDataFetcherFactoryProvider
import com.expedia.graphql.sample.datafetchers.SpringDataFetcherFactory
import com.expedia.graphql.sample.directives.DirectiveWiringFactory
import com.expedia.graphql.sample.directives.LowercaseDirectiveWiring
import com.expedia.graphql.sample.exceptions.CustomDataFetcherExceptionHandler
import com.expedia.graphql.sample.extension.CustomSchemaGeneratorHooks
import com.expedia.graphql.sample.mutation.Mutation
import com.expedia.graphql.sample.query.Query
import com.expedia.graphql.sample.subscription.Subscription
import com.expedia.graphql.toSchema
import graphql.GraphQL
import graphql.execution.AsyncExecutionStrategy
import graphql.execution.AsyncSerialExecutionStrategy
import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.SubscriptionExecutionStrategy
import graphql.schema.GraphQLSchema
import graphql.schema.idl.SchemaPrinter
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import javax.validation.Validator


@SpringBootApplication
class Application {

    private val logger = LoggerFactory.getLogger(Application::class.java)

    @Bean
    fun wiringFactory() = DirectiveWiringFactory()

    @Bean
    fun hooks(validator: Validator, wiringFactory: DirectiveWiringFactory) =
        CustomSchemaGeneratorHooks(validator, DirectiveWiringHelper(wiringFactory, mapOf("lowercase" to LowercaseDirectiveWiring())))

    @Bean
    fun dataFetcherFactoryProvider(springDataFetcherFactory: SpringDataFetcherFactory, hooks: SchemaGeneratorHooks) =
        CustomDataFetcherFactoryProvider(springDataFetcherFactory, hooks)

    @Bean
    fun schemaConfig(hooks: SchemaGeneratorHooks, dataFetcherFactoryProvider: KotlinDataFetcherFactoryProvider): SchemaGeneratorConfig = SchemaGeneratorConfig(
        supportedPackages = listOf("com.expedia"),
        hooks = hooks,
        dataFetcherFactoryProvider = dataFetcherFactoryProvider
    )

    @Bean
    fun schemaPrinter() = SchemaPrinter(
        SchemaPrinter.Options.defaultOptions()
            .includeScalarTypes(true)
            .includeExtendedScalarTypes(true)
            .includeIntrospectionTypes(true)
            .includeSchemaDefintion(true)
    )

    @Bean
    fun schema(
        queries: List<Query>,
        mutations: List<Mutation>,
        subscriptions: List<Subscription>,
        schemaConfig: SchemaGeneratorConfig,
        schemaPrinter: SchemaPrinter
    ): GraphQLSchema {
        fun List<Any>.toTopLevelObjectDefs() = this.map {
            TopLevelObject(it)
        }

        val schema = toSchema(
            config = schemaConfig,
            queries = queries.toTopLevelObjectDefs(),
            mutations = mutations.toTopLevelObjectDefs(),
            subscriptions = subscriptions.toTopLevelObjectDefs()
        )

        logger.info(schemaPrinter.print(schema))
        return schema
    }

    @Bean
    fun dataFetcherExceptionHandler(): DataFetcherExceptionHandler = CustomDataFetcherExceptionHandler()

    @Bean
    fun graphQL(
        schema: GraphQLSchema,
        dataFetcherExceptionHandler: DataFetcherExceptionHandler
    ): GraphQL = GraphQL.newGraphQL(schema)
        .queryExecutionStrategy(AsyncExecutionStrategy(dataFetcherExceptionHandler))
        .mutationExecutionStrategy(AsyncSerialExecutionStrategy(dataFetcherExceptionHandler))
        .subscriptionExecutionStrategy(SubscriptionExecutionStrategy(dataFetcherExceptionHandler))
        .build()
}

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
