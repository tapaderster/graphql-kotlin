/*
 * Copyright 2025 Expedia, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.expediagroup.graphql.server.execution

import com.expediagroup.graphql.dataloader.KotlinDataLoader
import com.expediagroup.graphql.dataloader.KotlinDataLoaderRegistryFactory
import com.expediagroup.graphql.dataloader.instrumentation.syncexhaustion.GraphQLSyncExecutionExhaustedDataLoaderDispatcher
import com.expediagroup.graphql.generator.SchemaGeneratorConfig
import com.expediagroup.graphql.generator.TopLevelObject
import com.expediagroup.graphql.generator.execution.FlowSubscriptionExecutionStrategy
import com.expediagroup.graphql.generator.extensions.toGraphQLContext
import com.expediagroup.graphql.generator.hooks.FlowSubscriptionSchemaGeneratorHooks
import com.expediagroup.graphql.generator.toSchema
import com.expediagroup.graphql.server.extensions.getValueFromDataLoader
import com.expediagroup.graphql.server.types.GraphQLBatchRequest
import com.expediagroup.graphql.server.types.GraphQLBatchResponse
import com.expediagroup.graphql.server.types.GraphQLRequest
import com.expediagroup.graphql.server.types.GraphQLResponse
import com.expediagroup.graphql.server.types.GraphQLServerError
import graphql.ExecutionInput
import graphql.GraphQL
import graphql.GraphQLContext
import graphql.execution.AbortExecutionException
import graphql.execution.instrumentation.ChainedInstrumentation
import graphql.execution.instrumentation.Instrumentation
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLSchema
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CompletableFuture
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.dataloader.DataLoader
import org.dataloader.DataLoaderFactory
import org.junit.jupiter.api.Test

class GraphQLRequestHandlerTest {

    private val testSchema: GraphQLSchema = toSchema(
        config = SchemaGeneratorConfig(
            supportedPackages = listOf("com.expediagroup.graphql.server.execution"),
            hooks = FlowSubscriptionSchemaGeneratorHooks(),
        ),
        queries = listOf(TopLevelObject(BasicQuery())),
        mutations = listOf(TopLevelObject(BasicMutation())),
        subscriptions = listOf(TopLevelObject(BasicSubscription())),
    )
    private val testGraphQL: GraphQL = GraphQL.newGraphQL(testSchema).subscriptionExecutionStrategy(FlowSubscriptionExecutionStrategy()).build()
    private val graphQLRequestHandler = GraphQLRequestHandler(testGraphQL)

    private fun getBatchingRequestHandler(instrumentation: Instrumentation): GraphQLRequestHandler =
        GraphQLRequestHandler(
            GraphQL.newGraphQL(testSchema).doNotAutomaticallyDispatchDataLoader().instrumentation(instrumentation).build(),
            KotlinDataLoaderRegistryFactory(
                object : KotlinDataLoader<Int, User> {
                    override val dataLoaderName: String = "UserDataLoader"
                    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, User> =
                        DataLoaderFactory.newDataLoader { _ ->
                            CompletableFuture.completedFuture(
                                listOf(
                                    User(1, "John Doe"),
                                    User(2, "Jane Doe")
                                )
                            )
                        }
                }
            )
        )

    @Test
    fun `execute graphQL query`() {
        val response = runBlocking {
            val request = GraphQLRequest(query = "query { random }")
            graphQLRequestHandler.executeRequest(request) as GraphQLResponse<*>
        }
        assertNotNull(response.data as? Map<*, *>) { data ->
            assertNotNull(data["random"] as? Int)
        }
        assertNull(response.errors)
        assertNull(response.extensions)
    }

    @Test
    fun `execute graphQL query with arguments`() {
        val response = runBlocking {
            val request = GraphQLRequest(query = """query { hello(name: "JUNIT") }""")
            graphQLRequestHandler.executeRequest(request) as GraphQLResponse<*>
        }
        assertNotNull(response.data as? Map<*, *>) { data ->
            assertNotNull(data["hello"] as? String) { msg ->
                assertEquals("Hello JUNIT!", msg)
            }
        }
        assertNull(response.errors)
        assertNull(response.extensions)
    }

    @Test
    fun `execute graphQL query with variables`() {
        val response = runBlocking {
            val request = GraphQLRequest(
                query = "query helloWorldQuery(\$name: String!) { hello(name: \$name) }",
                variables = mapOf("name" to "JUNIT with variables"),
                operationName = "helloWorldQuery"
            )
            graphQLRequestHandler.executeRequest(request) as GraphQLResponse<*>
        }

        assertNotNull(response.data as? Map<*, *>) { data ->
            assertNotNull(data["hello"] as? String) { msg ->
                assertEquals("Hello JUNIT with variables!", msg)
            }
        }
        assertNull(response.errors)
        assertNull(response.extensions)
    }

    @Test
    fun `execute failing graphQL query`() {
        val response = runBlocking {
            val request = GraphQLRequest(query = "query { alwaysThrows }")
            graphQLRequestHandler.executeRequest(request) as GraphQLResponse<*>
        }

        assertNull(response.data)
        assertNotNull(response.errors) { errors ->
            assertEquals(1, errors.size)
            val error = errors.first()
            assertEquals("Exception while fetching data (/alwaysThrows) : JUNIT Failure", error.message)
        }
        assertNull(response.extensions)
    }

    @Test
    fun `execute graphQL query with graphql context map`() {
        val response = runBlocking {
            val context = mapOf("foo" to "JUNIT context value").toGraphQLContext()
            val request = GraphQLRequest(query = "query { graphQLContextualValue }")
            graphQLRequestHandler.executeRequest(
                request,
                context
            ) as GraphQLResponse<*>
        }

        assertNotNull(response.data as? Map<*, *>) { data ->
            assertNotNull(data["graphQLContextualValue"] as? String) { msg ->
                assertEquals("JUNIT context value", msg)
            }
        }
        assertNull(response.errors)
        assertNull(response.extensions)
    }

    @Test
    fun `execute graphQL query throwing uncaught exception`() {
        val response = runBlocking {
            val mockGraphQL = mockk<GraphQL> {
                every { executeAsync(any<ExecutionInput>()) } throws RuntimeException("Uncaught JUNIT")
                every { instrumentation } returns ChainedInstrumentation()
            }
            val mockQueryHandler = GraphQLRequestHandler(mockGraphQL)
            mockQueryHandler.executeRequest(
                GraphQLRequest(query = "query { whatever }")
            ) as GraphQLResponse<*>
        }

        assertNull(response.data)
        assertNotNull(response.errors) { errors ->
            assertEquals(1, errors.size)
            assertEquals("Uncaught JUNIT", errors.first().message)
        }
        assertNull(response.extensions)
    }

    @Test
    fun `execute graphQL query throwing uncaught graphql exception`() {
        val response = runBlocking {
            val mockGraphQL = mockk<GraphQL> {
                every { executeAsync(any<ExecutionInput>()) } throws AbortExecutionException("Uncaught abort exception")
                every { instrumentation } returns ChainedInstrumentation()
            }
            val mockQueryHandler = GraphQLRequestHandler(mockGraphQL)
            mockQueryHandler.executeRequest(
                GraphQLRequest(query = "query { whatever }")
            ) as GraphQLResponse<*>
        }

        assertNull(response.data)
        assertNotNull(response.errors) { errors ->
            assertEquals(1, errors.size)
            val error = errors.first()
            assertEquals("Uncaught abort exception", error.message)
        }
        assertNull(response.extensions)
    }

    @Test
    fun `executes graphQL batch with a mutation`() {
        val response = runBlocking {
            val request = GraphQLBatchRequest(
                GraphQLRequest(query = """query { random }"""),
                GraphQLRequest(query = """mutation { addUser(name: "John Doe") { id name } }"""),
            )
            graphQLRequestHandler.executeRequest(request) as GraphQLBatchResponse
        }
        assertEquals(2, response.responses.size)
        assertNotNull(response.responses[0].data as? Map<*, *>) { data ->
            assertNotNull(data["random"] as? Int)
            assertNull(response.responses[0].errors)
            assertNull(response.responses[0].extensions)
        }
        assertNotNull(response.responses[1].data as? Map<*, *>) { data ->
            assertNotNull(data["addUser"] as? Map<*, *>) { addUser ->
                assertEquals("John Doe", addUser["name"])
                assertNotNull(addUser["id"] as? Int)
                assertNull(response.responses[1].errors)
                assertNull(response.responses[1].extensions)
            }
        }
    }

    @Test
    fun `executes graphQL batch with queries`() {
        val response = runBlocking {
            val request = GraphQLBatchRequest(
                GraphQLRequest(query = """query { random }"""),
                GraphQLRequest(query = """query { hello(name: "JUNIT") }"""),
            )
            graphQLRequestHandler.executeRequest(request) as GraphQLBatchResponse
        }
        assertEquals(2, response.responses.size)
        assertNotNull(response.responses[0].data as? Map<*, *>) { data ->
            assertNotNull(data["random"] as? Int)
            assertNull(response.responses[0].errors)
            assertNull(response.responses[0].extensions)
        }
        assertNotNull(response.responses[1].data as? Map<*, *>) { data ->
            assertNotNull(data["hello"] as? String) { msg ->
                assertEquals("Hello JUNIT!", msg)
                assertNull(response.responses[1].errors)
                assertNull(response.responses[1].extensions)
            }
        }
    }

    @Test
    fun `executes graphQL batch with queries and batching with DataLoaderSyncExecutionExhaustedInstrumentation`() {
        val response = runBlocking {
            val request = GraphQLBatchRequest(
                GraphQLRequest(query = """query { user(id: 1) { id name } }"""),
                GraphQLRequest(query = """query { user(id: 2) { id name } }"""),
            )
            val batchingRequestHandler = getBatchingRequestHandler(GraphQLSyncExecutionExhaustedDataLoaderDispatcher())
            batchingRequestHandler.executeRequest(request) as GraphQLBatchResponse
        }

        assertEquals(2, response.responses.size)
        assertNotNull(response.responses[0].data as? Map<*, *>) { data ->
            assertNotNull(data["user"] as? Map<*, *>) { user ->
                assertEquals(1, user["id"])
                assertEquals("John Doe", user["name"])
                assertNull(response.responses[0].errors)
                assertNull(response.responses[0].extensions)
            }
        }
        assertNotNull(response.responses[1].data as? Map<*, *>) { data ->
            assertNotNull(data["user"] as? Map<*, *>) { user ->
                assertEquals(2, user["id"])
                assertEquals("Jane Doe", user["name"])
                assertNull(response.responses[0].errors)
                assertNull(response.responses[0].extensions)
            }
        }
    }

    @Test
    fun `executes graphQL batch with queries and batching with ChainedInstrumentation`() {
        val response = runBlocking {
            val request = GraphQLBatchRequest(
                GraphQLRequest(query = """query { user(id: 1) { id name } }"""),
                GraphQLRequest(query = """query { user(id: 2) { id name } }"""),
            )
            val batchingRequestHandler = getBatchingRequestHandler(
                ChainedInstrumentation(
                    GraphQLSyncExecutionExhaustedDataLoaderDispatcher()
                )
            )
            batchingRequestHandler.executeRequest(request) as GraphQLBatchResponse
        }

        assertEquals(2, response.responses.size)
        assertNotNull(response.responses[0].data as? Map<*, *>) { data ->
            assertNotNull(data["user"] as? Map<*, *>) { user ->
                assertEquals(1, user["id"])
                assertEquals("John Doe", user["name"])
                assertNull(response.responses[0].errors)
                assertNull(response.responses[0].extensions)
            }
        }
        assertNotNull(response.responses[1].data as? Map<*, *>) { data ->
            assertNotNull(data["user"] as? Map<*, *>) { user ->
                assertEquals(2, user["id"])
                assertEquals("Jane Doe", user["name"])
                assertNull(response.responses[0].errors)
                assertNull(response.responses[0].extensions)
            }
        }
    }

    @Test
    fun `execute graphQL subscription`() {
        val response = runBlocking {
            val context = emptyMap<String, Any>().toGraphQLContext()
            val request = GraphQLRequest(
                query = """subscription { users(name: "Jane Doe") { name } }""",
            )
            graphQLRequestHandler.executeSubscription(request, context).first()
        }

        assertNotNull(response.data as? Map<*, *>) { data ->
            assertNotNull(data["users"] as? Map<*, *>) { user ->
                assertEquals("Jane Doe", user["name"])
            }
        }
        assertNull(response.errors)
        assertNull(response.extensions)
    }

    @Test
    fun `execute graphQL subscription with error`() {
        val response = runBlocking {
            val context = emptyMap<String, Any>().toGraphQLContext()
            val request = GraphQLRequest(
                query = "subscription { withFlowError { name } }",
            )
            graphQLRequestHandler.executeSubscription(request, context).first()
        }

        assertNotNull(response.errors as List<GraphQLServerError>) { errors ->
            assertNotNull(errors[0]) { error ->
                assertEquals("Subscription failure", error.message)
            }
        }
    }

    @Test
    fun `execute graphQL subscription with error on init`() {
        val response = runBlocking {
            val context = emptyMap<String, Any>().toGraphQLContext()
            val request = GraphQLRequest(
                query = "subscription { withInitError { name } }",
            )
            graphQLRequestHandler.executeSubscription(request, context).first()
        }

        assertNotNull(response.errors as List<GraphQLServerError>) { errors ->
            assertNotNull(errors[0]) { error ->
                assertEquals("Exception while fetching data (/withInitError) : Subscription failure", error.message)
            }
        }
    }

    data class User(val id: Int, val name: String)

    class BasicQuery {
        fun random(): Int = Random.nextInt()

        fun user(
            id: Int,
            dataFetchingEnvironment: DataFetchingEnvironment
        ): CompletableFuture<User> =
            dataFetchingEnvironment.getValueFromDataLoader("UserDataLoader", id)

        fun hello(name: String): String = "Hello $name!"

        fun alwaysThrows(): String = throw Exception("JUNIT Failure")

        fun graphQLContextualValue(dataFetchingEnvironment: DataFetchingEnvironment): String = dataFetchingEnvironment.graphQlContext.get("foo") ?: "default"
    }

    class BasicMutation {
        fun addUser(name: String): User = User(Random.nextInt(), name)
    }

    class BasicSubscription {
        fun users(name: String): Flow<User> = flowOf(User(Random.nextInt(), name))

        fun withFlowError(): Flow<User> = flow {
            throw Exception("Subscription failure")
        }

        fun withInitError(): Flow<User> {
            throw Exception("Subscription failure")
        }
    }
}
