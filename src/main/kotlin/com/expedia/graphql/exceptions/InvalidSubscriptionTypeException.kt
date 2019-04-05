package com.expedia.graphql.exceptions

import kotlin.reflect.KClass

/**
 * Exception thrown on schema creation if any subscription class is not public.
 */
class InvalidSubscriptionTypeException(klazz: KClass<*>) : GraphQLKotlinException("Schema requires all subscription to be public, " +
    "${klazz.simpleName} subscription has ${klazz.visibility} visibility modifier")
