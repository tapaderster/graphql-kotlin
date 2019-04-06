package com.expedia.graphql.sample

import graphql.ExecutionResult
import graphql.GraphQL
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.UUID.randomUUID
import java.util.function.BiFunction

@Component("SubscriptionHandler")
class SubscriptionHandler(private val graphql: GraphQL) : WebSocketHandler {

    private val log = LoggerFactory.getLogger(SubscriptionHandler::class.java)

    fun subscribe(request: GraphQLRequest) {

        val executionResult = graphql.execute(request.query)
        val stockPriceStream : Publisher<ExecutionResult> = executionResult.getData()

        stockPriceStream.subscribe(object:  Subscriber<ExecutionResult> {
            override fun onNext(t: ExecutionResult?) {
                TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
            }

            override fun onError(t: Throwable?) {

            }

            override fun onComplete() {

            }

            override fun onSubscribe(s: Subscription) {
                s.request(1)
            }
        })
    }

    private val eventFlux = Flux.generate<String> { sink -> sink.next(randomUUID().toString()) }

    private val intervalFlux = Flux.interval(Duration.ofMillis(1000L))
        .zipWith(eventFlux, BiFunction{ time: Long, event:String -> "$time - $event" })

    override fun handle(webSocketSession: WebSocketSession): Mono<Void> {
        return webSocketSession
            .send(
                intervalFlux.map(webSocketSession :: textMessage)
            )
            .and(
                webSocketSession.receive()
                .map(WebSocketMessage :: getPayloadAsText)
                    .doOnNext { data -> log.info("Data from ws client: $data") }
            )
    }
}
