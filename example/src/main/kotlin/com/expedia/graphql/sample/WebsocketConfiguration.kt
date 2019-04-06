package com.expedia.graphql.sample

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.HandlerMapping
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter

@Configuration
class WebsocketConfiguration(
    @Autowired @Qualifier("SubscriptionHandler") val webSocketHandler: WebSocketHandler
) {
    @Bean
    fun websocketHandlerMapping(): HandlerMapping {
        val map = mutableMapOf<String, WebSocketHandler>()
        map["/event"] = webSocketHandler

        val  handlerMapping = SimpleUrlHandlerMapping()
        handlerMapping.order = 1
        handlerMapping.urlMap = map
        return handlerMapping
    }

    @Bean
    fun handlerAdapter(): WebSocketHandlerAdapter {
        return WebSocketHandlerAdapter()
    }
}
