package com.github.sco1237896.connector.it.support

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import groovy.util.logging.Slf4j
import io.restassured.RestAssured
import io.restassured.builder.RequestSpecBuilder
import io.restassured.http.ContentType
import com.github.sco1237896.connector.it.support.ConnectorContainer
import com.github.sco1237896.connector.it.support.KafkaContainer
import org.testcontainers.containers.Network

@Slf4j
abstract class KafkaConnectorSpec extends ConnectorSpecSupport {
    static ObjectMapper mapper
    static Network network
    static KafkaContainer kafka

    def setupSpec() {
        mapper = new ObjectMapper(new YAMLFactory())

        RestAssured.requestSpecification = new RequestSpecBuilder()
                .setContentType(ContentType.JSON)
                .setAccept(ContentType.JSON)
                .build()

        network = Network.newNetwork()

        kafka = new KafkaContainer()
        kafka.withNetwork(network)
        kafka.start()
    }

    def cleanupSpec() {
        log.info("cleaning up kafka container")
        closeQuietly(kafka)

        log.info("cleaning up network container")
        closeQuietly(network)
    }

    // **********************************
    //
    // Helpers
    //
    // **********************************

    String topic() {
        def topic = UUID.randomUUID().toString()

        kafka.createTopic(topic)

        return topic
    }

    ConnectorContainer connectorContainer(String definition, Map<String, Object> properties) {
        return ConnectorContainer.forDefinition(definition).withProperties(properties).witNetwork(network).build()
    }

    ConnectorContainer connectorContainer(String definition,
                                          Map<String, String> properties,
                                          String dlqKafkaTopic,
                                          boolean simulateError) {

        return ConnectorContainer.forDefinition(definition)
                .withDlqErrorHandler(dlqKafkaTopic, simulateError)
                .withProperties(properties)
                .witNetwork(network)
                .build()
    }

    ConnectorContainer connectorContainer(String definition, String configuration) {
        return connectorContainer(definition, mapper.readValue(configuration, Map.class))
    }

    ConnectorContainer.Builder connectorContainer(String definition) {
        return ConnectorContainer.forDefinition(definition).witNetwork(network)
    }
}
