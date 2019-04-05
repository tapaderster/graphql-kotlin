package com.expedia.graphql.generator.types

import com.expedia.graphql.TopLevelObject
import com.expedia.graphql.exceptions.InvalidSubscriptionTypeException
import com.expedia.graphql.generator.SchemaGenerator
import com.expedia.graphql.generator.TypeBuilder
import com.expedia.graphql.generator.extensions.getValidFunctions
import com.expedia.graphql.generator.extensions.isPublic
import graphql.schema.GraphQLObjectType

internal class SubscriptionBuilder(generator: SchemaGenerator) : TypeBuilder(generator) {

    fun getSubscriptionObject(subscriptions: List<TopLevelObject>): GraphQLObjectType? {

        if (subscriptions.isEmpty()) {
            return null
        }

        val subscriptionBuilder = GraphQLObjectType.Builder()
        subscriptionBuilder.name(config.topLevelNames.subscription)

        for (subscription in subscriptions) {
            if (subscription.kClass.isPublic().not()) {
                throw InvalidSubscriptionTypeException(subscription.kClass)
            }

            generator.directives(subscription.kClass).forEach {
                subscriptionBuilder.withDirective(it)
            }

            subscription.kClass.getValidFunctions(config.hooks).forEach {
                val function = generator.function(it, subscription.obj)
                val functionFromHook = config.hooks.didGenerateSubscriptionType(it, function)
                subscriptionBuilder.field(functionFromHook)
            }
        }

        return subscriptionBuilder.build()
    }
}
