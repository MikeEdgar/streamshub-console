package com.github.eyefloaters.console.api;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.IntStream;

import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.ws.rs.core.Response.Status;

import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.DescribeConsumerGroupsOptions;
import org.apache.kafka.clients.admin.DescribeConsumerGroupsResult;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.ApiException;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.eclipse.microprofile.config.Config;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;

import com.github.eyefloaters.console.kafka.systemtest.TestPlainProfile;
import com.github.eyefloaters.console.kafka.systemtest.deployment.DeploymentManager;
import com.github.eyefloaters.console.kafka.systemtest.utils.ConsumerUtils;
import com.github.eyefloaters.console.test.AdminClientSpy;
import com.github.eyefloaters.console.test.TestHelper;
import com.github.eyefloaters.console.test.TopicHelper;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.kubernetes.client.KubernetesServerTestResource;
import io.strimzi.api.kafka.model.Kafka;

import static com.github.eyefloaters.console.test.TestHelper.whenRequesting;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;

@QuarkusTest
@QuarkusTestResource(KubernetesServerTestResource.class)
@TestHTTPEndpoint(ConsumerGroupsResource.class)
@TestProfile(TestPlainProfile.class)
class ConsumerGroupsResourceIT {

    @Inject
    Config config;

    @Inject
    KubernetesClient client;

    @Inject
    SharedIndexInformer<Kafka> kafkaInformer;

    @DeploymentManager.InjectDeploymentManager
    DeploymentManager deployments;

    TestHelper utils;
    TopicHelper topicUtils;
    ConsumerUtils groupUtils;
    String clusterId1;
    String clusterId2;

    @BeforeEach
    void setup() throws IOException {
        URI bootstrapServers = URI.create(deployments.getExternalBootstrapServers());

        topicUtils = new TopicHelper(bootstrapServers, config, null);
        topicUtils.deleteAllTopics();

        groupUtils = new ConsumerUtils(config, null);
        groupUtils.deleteConsumerGroups();

        utils = new TestHelper(bootstrapServers, config, null);

        clusterId1 = utils.getClusterId();
        clusterId2 = UUID.randomUUID().toString();

        client.resources(Kafka.class).delete();
        client.resources(Kafka.class)
            .resource(utils.buildKafkaResource("test-kafka1", clusterId1, bootstrapServers))
            .create();

        // Wait for the informer cache to be populated with all Kafka CRs
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> Objects.equals(kafkaInformer.getStore().list().size(), 1));
    }

    @Test
    void testListConsumerGroupsDefault() {
        String topic1 = "t1-" + UUID.randomUUID().toString();
        String group1 = "g1-" + UUID.randomUUID().toString();
        String client1 = "c1-" + UUID.randomUUID().toString();

        try (var consumer = groupUtils.consume(group1, topic1, client1, 2, false)) {
            whenRequesting(req -> req.get("", clusterId1))
                .assertThat()
                .statusCode(is(Status.OK.getStatusCode()))
                .body("data.size()", is(1))
                .body("data[0].attributes.state", is(Matchers.notNullValue(String.class)))
                .body("data[0].attributes.simpleConsumerGroup", is(Matchers.notNullValue(Boolean.class)));
        }
    }

    @Test
    void testListConsumerGroupsEmpty() {
        whenRequesting(req -> req.get("", clusterId1))
            .assertThat()
            .statusCode(is(Status.OK.getStatusCode()))
            .body("links.size()", is(4))
            .body("links", allOf(
                    hasEntry(is("first"), nullValue()),
                    hasEntry(is("prev"), nullValue()),
                    hasEntry(is("next"), nullValue()),
                    hasEntry(is("last"), nullValue())))
            .body("meta.page.total", is(0))
            .body("data.size()", is(0));
    }

    @Test
    void testListConsumerGroupsWithTwoAssignments() {
        String topic1 = "t1-" + UUID.randomUUID().toString();
        String group1 = "g1-" + UUID.randomUUID().toString();
        String client1 = "c1-" + UUID.randomUUID().toString();

        try (var consumer = groupUtils.consume(group1, topic1, client1, 2, false)) {
            whenRequesting(req -> req
                    .param("fields[consumerGroups]", "simpleConsumerGroup,"
                            + "state,"
                            + "members,"
                            + "offsets,"
                            + "coordinator,"
                            + "authorizedOperations,"
                            + "partitionAssignor")
                    .get("", clusterId1))
                .assertThat()
                .statusCode(is(Status.OK.getStatusCode()))
                .body("data.size()", is(1))
                .body("data[0].attributes.state", is(notNullValue(String.class)))
                .body("data[0].attributes.simpleConsumerGroup", is(notNullValue(Boolean.class)))
                .body("data[0].attributes.coordinator.id", is(0))
                .body("data[0].attributes.authorizedOperations.size()", is(greaterThanOrEqualTo(1)))
                .body("data[0].attributes.offsets.size()", is(2))
                .body("data[0].attributes.offsets", everyItem(allOf(
                        hasKey("topicId"),
                        hasKey("topicName"),
                        hasKey("partition"),
                        hasKey("offset"),
                        hasKey("lag"),
                        hasKey("metadata"))))
                .body("data[0].attributes.members.size()", is(1))
                .body("data[0].attributes.members.clientId", contains(client1))
                .body("data[0].attributes.members.find { it.clientId == '%s' }.assignments.size()".formatted(client1), is(2));
        }
    }

    @Test
    void testListConsumerGroupsWithPagination() {
        List<String> groupIds = IntStream.range(0, 10)
                .mapToObj("grp-%02d-"::formatted)
                .map(prefix -> prefix + UUID.randomUUID().toString())
                .sorted()
                .toList();

        groupIds.forEach(groupId -> {
            String topic = "t-" + UUID.randomUUID().toString();
            String client = "c-" + UUID.randomUUID().toString();
            groupUtils.consume(groupId, topic, client, 1, true);
        });

        Function<String, JsonObject> linkExtract = response -> {
            try (JsonReader reader = Json.createReader(new StringReader(response))) {
                return reader.readObject().getJsonObject("links");
            }
        };

        // Page 1
        String response1 = whenRequesting(req -> req
                .param("sort", "id,state,someIgnoredField,-simpleConsumerGroup")
                .param("page[size]", 2)
                .param("fields[consumerGroups]", "state,simpleConsumerGroup,members")
                .get("", clusterId1))
            .assertThat()
            .statusCode(is(Status.OK.getStatusCode()))
            .body("links.size()", is(4))
            .body("links", allOf(
                    hasEntry(is("first"), notNullValue()),
                    hasEntry(is("prev"), nullValue()),
                    hasEntry(is("next"), notNullValue()),
                    hasEntry(is("last"), notNullValue())))
            .body("meta.page.total", is(10))
            .body("meta.page.pageNumber", is(1))
            .body("data.size()", is(2))
            .body("data[0].id", is(groupIds.get(0)))
            .body("data[1].id", is(groupIds.get(1)))
            .extract()
            .asString();

        JsonObject links1 = linkExtract.apply(response1);
        String links1First = links1.getString("first");
        String links1Last = links1.getString("last");

        // Advance to page 2, using `next` link from page 1
        URI request2 = URI.create(links1.getString("next"));
        String response2 = whenRequesting(req -> req
                .urlEncodingEnabled(false)
                .get(request2))
            .assertThat()
            .statusCode(is(Status.OK.getStatusCode()))
            .body("links.size()", is(4))
            .body("links", allOf(
                    hasEntry(is("first"), is(links1First)),
                    hasEntry(is("prev"), notNullValue()),
                    hasEntry(is("next"), notNullValue()),
                    hasEntry(is("last"), is(links1Last))))
            .body("meta.page.total", is(10))
            .body("meta.page.pageNumber", is(2))
            .body("data.size()", is(2))
            .body("data[0].id", is(groupIds.get(2)))
            .body("data[1].id", is(groupIds.get(3)))
            .extract()
            .asString();

        // Jump to final page 5 using `last` link from page 2
        JsonObject links2 = linkExtract.apply(response2);
        URI request3 = URI.create(links2.getString("last"));
        String response3 = whenRequesting(req -> req
                .urlEncodingEnabled(false)
                .get(request3))
            .assertThat()
            .statusCode(is(Status.OK.getStatusCode()))
            .body("links.size()", is(4))
            .body("links", allOf(
                    hasEntry(is("first"), is(links1First)),
                    hasEntry(is("prev"), notNullValue()),
                    hasEntry(is("next"), nullValue()),
                    hasEntry(is("last"), is(links1Last))))
            .body("meta.page.total", is(10))
            .body("meta.page.pageNumber", is(5))
            .body("data.size()", is(2))
            .body("data[0].id", is(groupIds.get(8)))
            .body("data[1].id", is(groupIds.get(9)))
            .extract()
            .asString();

        // Return to page 1 using the `first` link provided by the last page, 5
        JsonObject links3 = linkExtract.apply(response3);
        URI request4 = URI.create(links3.getString("first"));
        String response4 = whenRequesting(req -> req
                .urlEncodingEnabled(false)
                .get(request4))
            .assertThat()
            .statusCode(is(Status.OK.getStatusCode()))
            .body("links.size()", is(4))
            .body("links", allOf(
                    hasEntry(is("first"), is(links1First)),
                    hasEntry(is("prev"), nullValue()),
                    hasEntry(is("next"), notNullValue()),
                    hasEntry(is("last"), is(links1Last))))
            .body("meta.page.total", is(10))
            .body("meta.page.pageNumber", is(1))
            .body("data.size()", is(2))
            .body("data[0].id", is(groupIds.get(0)))
            .body("data[1].id", is(groupIds.get(1)))
            .extract()
            .asString();

        assertEquals(response1, response4);
    }

    @Test
    void testListConsumerGroupsWithDescribeError() {
        Answer<DescribeConsumerGroupsResult> describeConsumerGroupsFailed = args -> {
            @SuppressWarnings("unchecked")
            Collection<String> groupIds = args.getArgument(0, Collection.class);
            Map<String, KafkaFuture<ConsumerGroupDescription>> futures = new HashMap<>(groupIds.size());

            KafkaFutureImpl<ConsumerGroupDescription> failure = new KafkaFutureImpl<>();
            failure.completeExceptionally(new ApiException("EXPECTED TEST EXCEPTION"));

            groupIds.forEach(id -> futures.put(id, failure));

            return new DescribeConsumerGroupsResult(futures);
        };

        AdminClientSpy.install(client -> {
            // Mock listOffsets
            doAnswer(describeConsumerGroupsFailed)
                .when(client)
                .describeConsumerGroups(anyCollection(), any(DescribeConsumerGroupsOptions.class));
        });

        String topic1 = "t1-" + UUID.randomUUID().toString();
        String group1 = "g1-" + UUID.randomUUID().toString();
        String client1 = "c1-" + UUID.randomUUID().toString();

        try (var consumer = groupUtils.consume(group1, topic1, client1, 2, false)) {
            whenRequesting(req -> req
                    .param("fields[consumerGroups]", "members")
                    .get("", clusterId1))
                .assertThat()
                .statusCode(is(Status.OK.getStatusCode()))
                .body("data.size()", is(1))
                .body("data[0].id", is(group1))
                .body("data[0].meta.errors.size()", is(1))
                .body("data[0].meta.errors[0].detail", is("EXPECTED TEST EXCEPTION"));
        }
    }

    @Test
    void testDescribeConsumerGroupDefault() {
        String topic1 = "t1-" + UUID.randomUUID().toString();
        String group1 = "g1-" + UUID.randomUUID().toString();
        String client1 = "c1-" + UUID.randomUUID().toString();

        try (var consumer = groupUtils.consume(group1, topic1, client1, 2, false)) {
            whenRequesting(req -> req.get("{groupId}", clusterId1, group1))
                .assertThat()
                .statusCode(is(Status.OK.getStatusCode()))
                .body("data.attributes.state", is(Matchers.notNullValue(String.class)))
                .body("data.attributes.simpleConsumerGroup", is(Matchers.notNullValue(Boolean.class)));
        }
    }

    @Test
    void testDescribeConsumerGroupWithNoSuchGroup() {
        String topic1 = "t1-" + UUID.randomUUID().toString();
        String group1 = "g1-" + UUID.randomUUID().toString();
        String client1 = "c1-" + UUID.randomUUID().toString();

        try (var consumer = groupUtils.consume(group1, topic1, client1, 2, false)) {
            whenRequesting(req -> req.get("{groupId}", clusterId1, UUID.randomUUID().toString()))
                .assertThat()
                .statusCode(is(Status.NOT_FOUND.getStatusCode()))
                .body("errors.size()", is(1))
                .body("errors.status", contains("404"))
                .body("errors.code", contains("4041"));
        }
    }

    @Test
    void testDescribeConsumerGroupWithFetchGroupOffsetsError() {
        Answer<ListConsumerGroupOffsetsResult> listConsumerGroupOffsetsFailed = args -> {
            KafkaFutureImpl<Map<TopicPartition, OffsetAndMetadata>> failure = new KafkaFutureImpl<>();
            failure.completeExceptionally(new ApiException("EXPECTED TEST EXCEPTION"));

            ListConsumerGroupOffsetsResult resultMock = Mockito.mock(ListConsumerGroupOffsetsResult.class);

            doAnswer(partitionsToOffsetAndMetadataArgs -> failure)
                .when(resultMock)
                .partitionsToOffsetAndMetadata(Mockito.anyString());

            return resultMock;
        };

        AdminClientSpy.install(client -> {
            // Mock listOffsets
            doAnswer(listConsumerGroupOffsetsFailed)
                .when(client)
                .listConsumerGroupOffsets(anyMap());
        });

        String topic1 = "t1-" + UUID.randomUUID().toString();
        String group1 = "g1-" + UUID.randomUUID().toString();
        String client1 = "c1-" + UUID.randomUUID().toString();

        try (var consumer = groupUtils.consume(group1, topic1, client1, 2, false)) {
            whenRequesting(req -> req
                    .param("fields[consumerGroups]", "offsets")
                    .get("{groupId}", clusterId1, group1))
                .assertThat()
                .statusCode(is(Status.OK.getStatusCode()))
                .body("data.id", is(group1))
                .body("data.meta.errors.size()", is(1))
                .body("data.meta.errors[0].title", is("Unable to list consumer group offsets"))
                .body("data.meta.errors[0].detail", is("EXPECTED TEST EXCEPTION"));
        }
    }


    @Test
    void testDescribeConsumerGroupWithFetchTopicOffsetsError() {
        Answer<ListOffsetsResult> listOffsetsFailed = args -> {
            Map<TopicPartition, OffsetSpec> topicPartitionOffsets = args.getArgument(0);
            KafkaFutureImpl<ListOffsetsResultInfo> failure = new KafkaFutureImpl<>();
            failure.completeExceptionally(new ApiException("EXPECTED TEST EXCEPTION"));

            Map<TopicPartition, KafkaFuture<ListOffsetsResultInfo>> futures = new HashMap<>();
            topicPartitionOffsets.keySet().forEach(key -> futures.put(key, failure));

            return new ListOffsetsResult(futures);
        };

        AdminClientSpy.install(client -> {
            // Mock listOffsets
            doAnswer(listOffsetsFailed)
                .when(client)
                .listOffsets(anyMap());
        });

        String topic1 = "t1-" + UUID.randomUUID().toString();
        String group1 = "g1-" + UUID.randomUUID().toString();
        String client1 = "c1-" + UUID.randomUUID().toString();

        try (var consumer = groupUtils.consume(group1, topic1, client1, 2, false)) {
            whenRequesting(req -> req
                    .param("fields[consumerGroups]", "offsets")
                    .get("{groupId}", clusterId1, group1))
                .assertThat()
                .statusCode(is(Status.OK.getStatusCode()))
                .body("data.id", is(group1))
                .body("data.meta.errors.size()", is(2)) // 2 partitions, both failed
                .body("data.meta.errors.title", everyItem(startsWith("Unable to list offsets for topic/partition")))
                .body("data.meta.errors.detail", everyItem(is("EXPECTED TEST EXCEPTION")));
        }
    }

    @Test
    void testDeleteConsumerGroupWithMembers() {
        String topic1 = "t1-" + UUID.randomUUID().toString();
        String group1 = "g1-" + UUID.randomUUID().toString();
        String client1 = "c1-" + UUID.randomUUID().toString();

        try (var consumer = groupUtils.consume(group1, topic1, client1, 2, false)) {
            whenRequesting(req -> req.delete("{groupId}", clusterId1, group1))
                .assertThat()
                .statusCode(is(Status.CONFLICT.getStatusCode()))
                .body("errors.size()", is(1))
                .body("errors.status", contains("409"))
                .body("errors.code", contains("4091"));

            assertEquals(ConsumerGroupState.STABLE, groupUtils.consumerGroupState(group1));
        }
    }

    @Test
    void testDeleteConsumerGroupWithNoSuchGroup() {
        String topic1 = "t1-" + UUID.randomUUID().toString();
        String group1 = "g1-" + UUID.randomUUID().toString();
        String client1 = "c1-" + UUID.randomUUID().toString();

        try (var consumer = groupUtils.consume(group1, topic1, client1, 2, false)) {
            whenRequesting(req -> req.delete("{groupId}", clusterId1, UUID.randomUUID().toString()))
                .assertThat()
                .statusCode(is(Status.NOT_FOUND.getStatusCode()))
                .body("errors.size()", is(1))
                .body("errors.status", contains("404"))
                .body("errors.code", contains("4041"));

            assertEquals(ConsumerGroupState.STABLE, groupUtils.consumerGroupState(group1));
        }
    }

    @Test
    void testDeleteConsumerGroupSucceeds() {
        String topic1 = "t1-" + UUID.randomUUID().toString();
        String group1 = "g1-" + UUID.randomUUID().toString();
        String client1 = "c1-" + UUID.randomUUID().toString();

        try (var consumer = groupUtils.consume(group1, topic1, client1, 2, false)) {
            await().atMost(10, TimeUnit.SECONDS)
                .until(() -> ConsumerGroupState.STABLE == groupUtils.consumerGroupState(group1));
        }

        whenRequesting(req -> req.delete("{groupId}", clusterId1, group1))
            .assertThat()
            .statusCode(is(Status.NO_CONTENT.getStatusCode()));

        whenRequesting(req -> req.get("{groupId}", clusterId1, group1))
            .assertThat()
            .statusCode(is(Status.NOT_FOUND.getStatusCode()));
    }
}