package com.expedia.graphql.sample.subscription

import com.expedia.graphql.annotations.GraphQLDescription
import org.springframework.stereotype.Component

@Component
class SimpleSubscription: Subscription {

    @GraphQLDescription("send data back with delay")
    fun sendData(data: String, sleepFor: String): String {
        Thread.sleep(sleepFor.toLong())
        return "After sleeping for $sleepFor seconds. Here is the data: $data"
    }
}
