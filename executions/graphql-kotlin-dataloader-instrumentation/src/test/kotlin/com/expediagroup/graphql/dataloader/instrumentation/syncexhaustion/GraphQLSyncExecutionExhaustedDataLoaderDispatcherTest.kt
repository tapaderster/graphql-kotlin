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

package com.expediagroup.graphql.dataloader.instrumentation.syncexhaustion

import com.expediagroup.graphql.dataloader.instrumentation.fixture.DataLoaderInstrumentationStrategy
import com.expediagroup.graphql.dataloader.instrumentation.fixture.AstronautGraphQL
import com.expediagroup.graphql.dataloader.instrumentation.fixture.ProductGraphQL
import graphql.ExecutionInput
import io.mockk.clearAllMocks
import io.mockk.verify
import org.dataloader.DataLoaderRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphQLSyncExecutionExhaustedDataLoaderDispatcherTest {
    private val graphQLSyncExecutionExhaustedDataLoaderDispatcher = GraphQLSyncExecutionExhaustedDataLoaderDispatcher()
    private val astronautGraphQL = AstronautGraphQL.builder
        .instrumentation(graphQLSyncExecutionExhaustedDataLoaderDispatcher)
        // graphql java adds DataLoaderDispatcherInstrumentation by default
        .doNotAutomaticallyDispatchDataLoader()
        .build()

    private val productGraphQL = ProductGraphQL.builder
        .instrumentation(GraphQLSyncExecutionExhaustedDataLoaderDispatcher())
        // graphql java adds DataLoaderDispatcherInstrumentation by default
        .doNotAutomaticallyDispatchDataLoader()
        .build()

    @BeforeEach
    fun clear() {
        clearAllMocks()
    }

    @Test
    fun `Instrumentation should batch transactions on async top level fields`() {
        val queries = listOf(
            "{ astronaut(id: 1) { name } }",
            "{ mission(id: 3) { id } }",
            "{ astronaut(id: 2) { id } }",
            "{ mission(id: 4) { designation } }"
        )

        val (results, dataLoaderRegistry, graphQLContext) = AstronautGraphQL.executeOperations(
            astronautGraphQL,
            queries,
            DataLoaderInstrumentationStrategy.SYNC_EXHAUSTION
        )
        assertEquals(4, results.size)

        val astronautStatistics = dataLoaderRegistry.dataLoadersMap["AstronautDataLoader"]?.statistics
        val missionStatistics = dataLoaderRegistry.dataLoadersMap["MissionDataLoader"]?.statistics

        assertEquals(1, astronautStatistics?.batchInvokeCount)
        assertEquals(2, astronautStatistics?.batchLoadCount)

        assertEquals(1, missionStatistics?.batchInvokeCount)
        assertEquals(2, missionStatistics?.batchLoadCount)

        verify(exactly = 2) {
            graphQLContext.get(DataLoaderRegistry::class)
        }
    }

    @Test
    fun `Instrumentation should batch transactions on sync top level fields`() {
        val queries = listOf(
            "{ nasa { astronaut(id: 1) { name } } }",
            "{ nasa { astronaut(id: 2) { id name } } }",
            "{ nasa { mission(id: 3) { designation } } }",
            "{ nasa { mission(id: 4) { id designation } } }"
        )

        val (results, dataLoaderRegistry, graphQLContext) = AstronautGraphQL.executeOperations(
            astronautGraphQL,
            queries,
            DataLoaderInstrumentationStrategy.SYNC_EXHAUSTION
        )

        assertEquals(4, results.size)

        val astronautStatistics = dataLoaderRegistry.dataLoadersMap["AstronautDataLoader"]?.statistics
        val missionStatistics = dataLoaderRegistry.dataLoadersMap["MissionDataLoader"]?.statistics

        assertEquals(1, astronautStatistics?.batchInvokeCount)
        assertEquals(2, astronautStatistics?.batchLoadCount)

        assertEquals(1, missionStatistics?.batchInvokeCount)
        assertEquals(2, missionStatistics?.batchLoadCount)

        verify(exactly = 2) {
            graphQLContext.get(DataLoaderRegistry::class)
        }
    }

    @Test
    fun `Instrumentation should batch transactions on different levels`() {
        val queries = listOf(
            // L2 astronaut - L3 missions
            "{ nasa { astronaut(id: 1) { missions { designation } } } }",
            // L1 astronaut - L2 missions
            "{ astronaut(id: 2) { missions { designation } } }",
            // L2 mission
            "{ nasa { mission(id: 3) { designation } } }",
            // L1 mission
            "{ mission(id: 4) { designation } }"
        )

        val (results, dataLoaderRegistry, graphQLContext) = AstronautGraphQL.executeOperations(
            astronautGraphQL,
            queries,
            DataLoaderInstrumentationStrategy.SYNC_EXHAUSTION
        )

        assertEquals(4, results.size)

        val astronautStatistics = dataLoaderRegistry.dataLoadersMap["AstronautDataLoader"]?.statistics
        val missionStatistics = dataLoaderRegistry.dataLoadersMap["MissionDataLoader"]?.statistics
        val missionsByAstronautStatistics = dataLoaderRegistry.dataLoadersMap["MissionsByAstronautDataLoader"]?.statistics

        assertEquals(1, astronautStatistics?.batchInvokeCount)
        // Level 1 and 2
        assertEquals(2, astronautStatistics?.batchLoadCount)

        assertEquals(1, missionStatistics?.batchInvokeCount)
        // Level 1 and 2
        assertEquals(2, missionStatistics?.batchLoadCount)

        assertEquals(1, missionsByAstronautStatistics?.batchInvokeCount)
        // Level 2 and 3
        assertEquals(2, missionsByAstronautStatistics?.batchLoadCount)

        verify(exactly = 3) {
            graphQLContext.get(DataLoaderRegistry::class)
        }
    }

    @Test
    fun `Instrumentation should batch transactions after exhausting a single ExecutionInput`() {
        val queries = listOf(
            """
                fragment AstronautFragment on Astronaut { name missions { designation } }
                query ComplexQuery {
                    astronaut1: astronaut(id: 1) { ...AstronautFragment }
                    nasa {
                        astronaut(id: 2) {...AstronautFragment }
                    }
                    astronaut3: astronaut(id: 3) { ...AstronautFragment }
                }
            """.trimIndent()
        )

        val (results, dataLoaderRegistry, graphQLContext) = AstronautGraphQL.executeOperations(
            astronautGraphQL,
            queries,
            DataLoaderInstrumentationStrategy.SYNC_EXHAUSTION
        )

        assertEquals(1, results.size)

        val astronautStatistics = dataLoaderRegistry.dataLoadersMap["AstronautDataLoader"]?.statistics
        val missionsByAstronautStatistics = dataLoaderRegistry.dataLoadersMap["MissionsByAstronautDataLoader"]?.statistics

        assertEquals(1, astronautStatistics?.batchInvokeCount)
        assertEquals(3, astronautStatistics?.batchLoadCount)

        assertEquals(1, missionsByAstronautStatistics?.batchInvokeCount)
        assertEquals(3, missionsByAstronautStatistics?.batchLoadCount)

        verify(exactly = 3) {
            graphQLContext.get(DataLoaderRegistry::class)
        }
    }

    @Test
    fun `Instrumentation should batch transactions after exhausting a single ExecutionInput with async Leafs`() {
        val queries = listOf(
            """
                fragment AstronautFragment on Astronaut { name missions { designation } }
                query ComplexQuery {
                    astronaut1: astronaut(id: 1) { ...AstronautFragment }
                    nasa {
                        astronaut(id: 2) {...AstronautFragment }
                        phoneNumber
                        twitter
                    }
                }
            """.trimIndent()
        )

        val (results, dataLoaderRegistry, graphQLContext) = AstronautGraphQL.executeOperations(
            astronautGraphQL,
            queries,
            DataLoaderInstrumentationStrategy.SYNC_EXHAUSTION
        )

        assertEquals(1, results.size)

        val astronautStatistics = dataLoaderRegistry.dataLoadersMap["AstronautDataLoader"]?.statistics
        val missionsByAstronautStatistics = dataLoaderRegistry.dataLoadersMap["MissionsByAstronautDataLoader"]?.statistics

        assertEquals(1, astronautStatistics?.batchInvokeCount)
        assertEquals(2, astronautStatistics?.batchLoadCount)

        assertEquals(1, missionsByAstronautStatistics?.batchInvokeCount)
        assertEquals(2, missionsByAstronautStatistics?.batchLoadCount)

        verify(exactly = 3) {
            graphQLContext.get(DataLoaderRegistry::class)
        }
    }

    @Test
    fun `Instrumentation should batch transactions after exhausting multiple ExecutionInput`() {
        val queries = listOf(
            """
                fragment AstronautFragment on Astronaut { name missions { designation } }
                fragment MissionFragment on Mission { designation }
                query ComplexQuery {
                    mission1: mission(id: 1) { ...MissionFragment }
                    astronaut1: astronaut(id: 1) { ...AstronautFragment }
                    nasa {
                        astronaut(id: 2) {...AstronautFragment }
                    }
                    astronaut3: astronaut(id: 3) { ...AstronautFragment }
                }
            """.trimIndent(),
            """
                fragment AstronautFragment on Astronaut { name missions { designation } }
                fragment MissionFragment on Mission { designation }
                query ComplexQuery2 {
                    astronaut1: astronaut(id: 1) { ...AstronautFragment }
                    mission1: mission(id: 1) { ...MissionFragment }
                    nasa {
                        mission(id: 2) {...MissionFragment }
                    }
                    mission3: mission(id: 3) { ...MissionFragment }
                }
            """.trimIndent()
        )

        val (results, dataLoaderRegistry, graphQLContext) = AstronautGraphQL.executeOperations(
            astronautGraphQL,
            queries,
            DataLoaderInstrumentationStrategy.SYNC_EXHAUSTION
        )

        assertEquals(2, results.size)

        val astronautStatistics = dataLoaderRegistry.dataLoadersMap["AstronautDataLoader"]?.statistics
        val missionStatistics = dataLoaderRegistry.dataLoadersMap["MissionDataLoader"]?.statistics
        val missionsByAstronautStatistics = dataLoaderRegistry.dataLoadersMap["MissionsByAstronautDataLoader"]?.statistics

        assertEquals(1, astronautStatistics?.batchInvokeCount)
        assertEquals(3, astronautStatistics?.batchLoadCount)

        assertEquals(1, missionStatistics?.batchInvokeCount)
        assertEquals(3, missionStatistics?.batchLoadCount)

        assertEquals(1, missionsByAstronautStatistics?.batchInvokeCount)
        assertEquals(3, missionsByAstronautStatistics?.batchLoadCount)

        verify(exactly = 3) {
            graphQLContext.get(DataLoaderRegistry::class)
        }
    }

    @Test
    fun `Instrumentation should batch transactions for list of lists`() {
        val queries = listOf(
            """
                fragment AstronautFragment on Astronaut { name missions { designation } }
                fragment MissionFragment on Mission { designation }

                query ComplexQuery {
                    astronauts1And2: astronauts(ids: [1, 2]) { ...AstronautFragment }
                    missions1And2: missions(ids: [1, 2]) { ...MissionFragment }
                    nasa {
                        phoneNumber
                        address { street zipCode }
                        astronauts3AndNull: astronauts(ids: [3, 404]) { ...AstronautFragment }
                        missions3AndNull: missions(ids: [3, 404]) { ...MissionFragment }
                    }
                }
            """.trimIndent()
        )

        val (results, dataLoaderRegistry, graphQLContext) = AstronautGraphQL.executeOperations(
            astronautGraphQL,
            queries,
            DataLoaderInstrumentationStrategy.SYNC_EXHAUSTION
        )

        assertEquals(1, results.size)

        val astronautStatistics = dataLoaderRegistry.dataLoadersMap["AstronautDataLoader"]?.statistics
        val missionStatistics = dataLoaderRegistry.dataLoadersMap["MissionDataLoader"]?.statistics
        val missionsByAstronautStatistics = dataLoaderRegistry.dataLoadersMap["MissionsByAstronautDataLoader"]?.statistics

        assertEquals(1, astronautStatistics?.batchInvokeCount)
        assertEquals(4, astronautStatistics?.batchLoadCount)

        assertEquals(1, missionStatistics?.batchInvokeCount)
        assertEquals(4, missionStatistics?.batchLoadCount)

        assertEquals(1, missionsByAstronautStatistics?.batchInvokeCount)
        assertEquals(3, missionsByAstronautStatistics?.batchLoadCount)

        verify(exactly = 3) {
            graphQLContext.get(DataLoaderRegistry::class)
        }
    }

    @Test
    fun `Instrumentation should batch transactions for list of lists without arguments`() {
        val queries = listOf(
            """
                {
                    astronauts { name missions { designation } }
                    nasa {
                        missions { designation }
                    }
                }
            """.trimIndent()
        )

        val (results, dataLoaderRegistry, _) = AstronautGraphQL.executeOperations(
            astronautGraphQL,
            queries,
            DataLoaderInstrumentationStrategy.SYNC_EXHAUSTION
        )

        assertEquals(1, results.size)

        val astronautStatistics = dataLoaderRegistry.dataLoadersMap["AstronautDataLoader"]?.statistics
        val missionStatistics = dataLoaderRegistry.dataLoadersMap["MissionDataLoader"]?.statistics
        val missionsByAstronautStatistics = dataLoaderRegistry.dataLoadersMap["MissionsByAstronautDataLoader"]?.statistics

        assertEquals(0, astronautStatistics?.batchInvokeCount)
        assertEquals(0, missionStatistics?.batchInvokeCount)
        assertEquals(1, missionsByAstronautStatistics?.batchInvokeCount)
    }

    @Test
    fun `Instrumentation multiple dataLoaders per field`() {
        val queries = listOf(
            """
                {
                    missions(ids: [1, 2]) { id designation planets { id name } }
                }
            """.trimIndent(),
            """
                {
                    missions(ids: [3, 4]) { id designation planets { id name } }
                }
            """.trimIndent(),
            """
                {
                    missions(ids: [2, 8, 9]) { id designation planets { id name } }
                }
            """.trimIndent()
        )

        val (results, dataLoaderRegistry, _) = AstronautGraphQL.executeOperations(
            astronautGraphQL,
            queries,
            DataLoaderInstrumentationStrategy.SYNC_EXHAUSTION
        )

        assertEquals(3, results.size)

        results.forEach { result ->
            assertTrue(result.getOrThrow().errors.isEmpty())
        }

        val missionStatistics = dataLoaderRegistry.dataLoadersMap["MissionDataLoader"]?.statistics
        val planetStatistics = dataLoaderRegistry.dataLoadersMap["PlanetsByMissionDataLoader"]?.statistics

        assertEquals(1, missionStatistics?.batchInvokeCount)
        assertEquals(1, planetStatistics?.batchInvokeCount)
    }

    @Test
    fun `Instrumentation should batch chained dataLoaders per field dataFetcher`() {
        val queries = listOf(
            """
                {
                    astronaut(id: 1) { planets { name } }
                }
            """.trimIndent(),
            """
                {
                    astronaut(id: 3) { planets { name } }
                }
            """.trimIndent(),
            """
                {
                    astronauts(ids: [4, 5]) { planets { name } }
                }
            """.trimIndent()
        )

        val (results, dataLoaderRegistry, _) = AstronautGraphQL.executeOperations(
            astronautGraphQL,
            queries,
            DataLoaderInstrumentationStrategy.SYNC_EXHAUSTION
        )

        assertEquals(3, results.size)
        results.forEach { result ->
            assertTrue(result.getOrThrow().errors.isEmpty())
        }

        val astronautStatistics = dataLoaderRegistry.dataLoadersMap["AstronautDataLoader"]?.statistics
        val missionsByAstronautStatistics = dataLoaderRegistry.dataLoadersMap["MissionsByAstronautDataLoader"]?.statistics
        val planetStatistics = dataLoaderRegistry.dataLoadersMap["PlanetsByMissionDataLoader"]?.statistics

        assertEquals(1, astronautStatistics?.batchInvokeCount)
        assertEquals(1, missionsByAstronautStatistics?.batchInvokeCount)
        assertEquals(1, planetStatistics?.batchInvokeCount)
    }

    @Test
    fun `Instrumentation should batch chained dataLoaders per field dataFetcher with different queries`() {
        val queries = listOf(
            """
                {
                    astronaut(id: 1) {
                        name
                        planets {
                            name
                        }
                    }
                }
            """.trimIndent(),
            """
                {
                    astronaut(id: 3) {
                        name
                        missions {
                            designation
                            planets {
                                name
                            }
                        }
                    }
                }
            """.trimIndent(),
        )

        val (results, dataLoaderRegistry) = AstronautGraphQL.executeOperations(
            astronautGraphQL,
            queries,
            DataLoaderInstrumentationStrategy.SYNC_EXHAUSTION
        )

        val astronautStatistics = dataLoaderRegistry.dataLoadersMap["AstronautDataLoader"]?.statistics
        val missionsByAstronautStatistics = dataLoaderRegistry.dataLoadersMap["MissionsByAstronautDataLoader"]?.statistics
        val planetStatistics = dataLoaderRegistry.dataLoadersMap["PlanetsByMissionDataLoader"]?.statistics

        assertEquals(2, results.size)
        results.forEach { result ->
            assertTrue(result.getOrThrow().errors.isEmpty())
        }

        assertEquals(1, astronautStatistics?.batchInvokeCount)
        assertEquals(1, missionsByAstronautStatistics?.batchInvokeCount)
        assertEquals(1, planetStatistics?.batchInvokeCount)
    }

    @Test
    fun `Instrumentation should batch and deduplicate by field selections`() {
        val queries = listOf(
            """
                {
                    product(id: 1) {
                        summary {
                            name
                        }
                    }
                }
            """.trimIndent(),
            """
                {
                    product(id: 1) {
                        details {
                            rating
                        }
                    }
                }
            """.trimIndent()
        )

        val (results, dataLoaderRegistry, _) = ProductGraphQL.execute(
            productGraphQL,
            queries,
            DataLoaderInstrumentationStrategy.SYNC_EXHAUSTION
        )

        assertEquals(2, results.size)

        val productStatistics = dataLoaderRegistry.dataLoadersMap["ProductDataLoader"]?.statistics

        assertEquals(1, productStatistics?.batchInvokeCount)
        assertEquals(2, productStatistics?.batchLoadCount)
    }

    @Test
    fun `Instrumentation should batch and deduplicate root selection fields`() {
        val queries = listOf(
            """
                {
                    productSummary(productId: 1) {
                        name
                    }
                }
            """.trimIndent(),
            """
                {
                    productDetails(productId: 1) {
                        rating
                    }
                }
            """.trimIndent()
        )

        val (results, dataLoaderRegistry, _) = ProductGraphQL.execute(
            productGraphQL,
            queries,
            DataLoaderInstrumentationStrategy.SYNC_EXHAUSTION
        )

        assertEquals(2, results.size)

        val productStatistics = dataLoaderRegistry.dataLoadersMap["ProductDataLoader"]?.statistics

        assertEquals(1, productStatistics?.batchInvokeCount)
        assertEquals(2, productStatistics?.batchLoadCount)
    }

    @Test
    fun `Instrumentation should not apply to mutations`() {
        val queries = listOf(
            """mutation { createAstronaut(name: "spaceMan") { id name } }"""
        )

        val (results, dataLoaderRegistry, graphQLContext) = AstronautGraphQL.executeOperations(
            astronautGraphQL,
            queries,
            DataLoaderInstrumentationStrategy.SYNC_EXHAUSTION
        )

        assertEquals(1, results.size)
        verify(exactly = 0) {
            graphQLContext.get(DataLoaderRegistry::class)
        }
    }

    @Test
    fun `Instrumentation should not consider executions with invalid operations`() {
        val queries = listOf(
            "invalid query{ astronaut(id: 1) {",
            "{ astronaut(id: 2) { id name } }",
            "{ mission(id: 3) { id designation } }",
            "{ mission(id: 4) { designation } }"
        )

        val (results, dataLoaderRegistry, graphQLContext) = AstronautGraphQL.executeOperations(
            astronautGraphQL,
            queries,
            DataLoaderInstrumentationStrategy.SYNC_EXHAUSTION
        )

        assertEquals(4, results.size)

        val astronautStatistics = dataLoaderRegistry.dataLoadersMap["AstronautDataLoader"]?.statistics
        val missionStatistics = dataLoaderRegistry.dataLoadersMap["MissionDataLoader"]?.statistics

        assertEquals(1, astronautStatistics?.batchInvokeCount)
        assertEquals(1, astronautStatistics?.batchLoadCount)

        assertEquals(1, missionStatistics?.batchInvokeCount)
        assertEquals(2, missionStatistics?.batchLoadCount)

        verify(exactly = 2) {
            graphQLContext.get(DataLoaderRegistry::class)
        }
    }

    @Test
    fun `Instrumentation should not consider executions that thrown exceptions`() {
        val executions = listOf(
            ExecutionInput.newExecutionInput("query test1 { astronaut(id: 1) { id name } }").operationName("test1").build(),
            ExecutionInput.newExecutionInput("query test2 { astronaut(id: 2) { id name } }").operationName("OPERATION_NOT_IN_DOCUMENT").build(),
            ExecutionInput.newExecutionInput("query test3 { mission(id: 3) { id designation } }").operationName("OPERATION_NOT_IN_DOCUMENT").build(),
            ExecutionInput.newExecutionInput("query test4 { mission(id: 4) { designation } }").operationName("test4").build()
        )

        val (results, dataLoaderRegistry, graphQLContext) = AstronautGraphQL.execute(
            astronautGraphQL,
            executions,
            DataLoaderInstrumentationStrategy.SYNC_EXHAUSTION
        )

        assertEquals(4, results.size)

        val astronautStatistics = dataLoaderRegistry.dataLoadersMap["AstronautDataLoader"]?.statistics
        val missionStatistics = dataLoaderRegistry.dataLoadersMap["MissionDataLoader"]?.statistics

        assertEquals(1, astronautStatistics?.batchInvokeCount)
        assertEquals(1, astronautStatistics?.batchLoadCount)

        assertEquals(1, missionStatistics?.batchInvokeCount)
        assertEquals(1, missionStatistics?.batchLoadCount)

        verify(exactly = 2) {
            graphQLContext.get(DataLoaderRegistry::class)
        }
    }
}
