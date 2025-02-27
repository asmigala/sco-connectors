package com.github.sco1237896.connector.it.nosql.mongodb

import com.mongodb.client.MongoClients
import com.mongodb.client.model.CreateCollectionOptions
import com.mongodb.client.model.InsertOneModel
import groovy.util.logging.Slf4j
import com.github.sco1237896.connector.it.support.ContainerImages
import com.github.sco1237896.connector.it.support.KafkaConnectorSpec
import com.github.sco1237896.connector.it.support.TestUtils
import org.bson.Document
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.containers.SelinuxContext
import spock.lang.Unroll

import java.util.concurrent.TimeUnit

import static com.mongodb.client.model.Filters.and
import static com.mongodb.client.model.Filters.eq

@Slf4j
class ConnectorIT extends KafkaConnectorSpec {
    static MongoDBContainer db
    static final String JKS_PATH = '/etc/wm/mongo-test.pem'
    static final String JKS_CLASSPATH = '/ssl/mongo-test.pem'
    static final String TLS_MODE = 'allowTLS'

    @Override
    def setupSpec() {
        db = ContainerImages.container("container.image.mongodb", MongoDBContainer.class)
        db.withLogConsumer(logger('tc-mongodb'))
        db.withNetwork(KafkaConnectorSpec.network)
        db.withNetworkAliases('tc-mongodb')
        db.withClasspathResourceMapping(JKS_CLASSPATH, JKS_PATH, BindMode.READ_ONLY, SelinuxContext.SHARED)

        db.withCommand(
                "--tlsMode", "${TLS_MODE}",
                "--tlsCertificateKeyFile", "${JKS_PATH}",
                "--tlsAllowInvalidHostnames",
                "--tlsAllowInvalidCertificates",
                "--tlsAllowConnectionsWithoutCertificates")

        db.start()
    }

    @Override
    def cleanupSpec() {
        closeQuietly(db)
    }

    @Unroll
    def "mongodb sink (#ssl)"(boolean ssl) {
        setup:
            def mongoClient = MongoClients.create(db.replicaSetUrl + "?tlsAllowInvalidHostnames=true")
            def database = mongoClient.getDatabase("toys")
            def collection = database.getCollection("cards")

            def topic = topic()
            def group = UUID.randomUUID().toString()
            def payload = "{ \"value\": ${ssl}, \"suit\": \"hearts\" }"

            def cnt = connectorContainer('mongodb_sink_v1.json', [
                'kafka_topic' : topic,
                'kafka_bootstrap_servers': KafkaConnectorSpec.kafka.outsideBootstrapServers,
                'kafka_consumer_group': UUID.randomUUID().toString(),
                'mongodb_hosts': 'tc-mongodb:27017',
                'mongodb_collection': collection.getNamespace().getCollectionName(),
                'mongodb_database': database.getName(),
                'mongodb_create_collection': 'true',
                'mongodb_ssl': ssl,
                'mongodb_ssl_validation_enabled': !ssl
            ])

            cnt.start()
        when:
        KafkaConnectorSpec.kafka.send(topic, payload)
        then:
            def records = KafkaConnectorSpec.kafka.poll(group, topic)
            records.size() == 1
            records.first().value() == payload

            await(10, TimeUnit.SECONDS) {
                return collection.countDocuments(and(eq('value', ssl), eq('suit', 'hearts'))) == 1
            }

        cleanup:
            closeQuietly(mongoClient)
            closeQuietly(cnt)
        where:
            ssl << [true, false]
    }

    def "mongodb sink with DLQ"() {
        setup:
            def mongoClient = MongoClients.create(db.replicaSetUrl)
            def database = mongoClient.getDatabase("toys2")
            def collection = database.getCollection("cards2")

            def topic = topic()
            def errorHandlerTopic = super.topic();
            def group = UUID.randomUUID().toString()
            def payload = '''{ "value": "4", "suit": "hearts" }'''

            def cnt = connectorContainer('mongodb_sink_v1.json', [
                    'kafka_topic' : topic,
                    'kafka_bootstrap_servers': KafkaConnectorSpec.kafka.outsideBootstrapServers,
                    'kafka_consumer_group': UUID.randomUUID().toString(),
                    'mongodb_hosts': 'tc-mongodb:27017',
                    'mongodb_collection': collection.getNamespace().getCollectionName(),
                    'mongodb_database': database.getName(),
                    'mongodb_create_collection': 'true',
                    'mongodb_ssl': 'false'
            ], errorHandlerTopic, true)

            cnt.start()
        when:
        KafkaConnectorSpec.kafka.send(topic, payload)
        then:
            def records = KafkaConnectorSpec.kafka.poll(group, topic)
            records.size() == 1
            records.first().value() == payload

            await(10, TimeUnit.SECONDS) {
                return collection.countDocuments(and(eq('value', '4'), eq('suit', 'hearts'))) == 0
            }

            def errorRecords = KafkaConnectorSpec.kafka.poll(group, errorHandlerTopic)
            errorRecords.size() == 1

            def errorMessage = errorRecords.first()
            def actual = TestUtils.SLURPER.parseText(errorMessage.value())
            def expected = TestUtils.SLURPER.parseText(payload)
            actual == expected

            def actualErrorHeaderBytes = (byte[]) errorMessage.headers().headers("rhoc.error-cause").first().value()
            def actualErrorHeader = new String(actualErrorHeaderBytes, "UTF-8");
            actualErrorHeader.contains("java.lang.RuntimeException: too bad")
            actualErrorHeader.contains("SimulateErrorProcessor.java")

        cleanup:
            closeQuietly(mongoClient)
            closeQuietly(cnt)
    }

    @Unroll
    def "mongodb source (#ssl)"(boolean ssl) {
        setup:
            def mongoClient = MongoClients.create(db.replicaSetUrl + "?tlsAllowInvalidHostnames=true")
            def database = mongoClient.getDatabase("toys")
            if(database.listCollectionNames().every {return !"boardgames".equals(it);}) {
                database.createCollection("boardgames", new CreateCollectionOptions().capped(true).sizeInBytes(256))
            }
            def collection = database.getCollection("boardgames", Document.class)

            def topic = topic()
            def group = UUID.randomUUID().toString()
            def payload = "{ \"owned\": ${ssl}, \"title\": \"powergrid\" }"

            def cnt = connectorContainer('mongodb_source_v1.json', [
                    'kafka_topic' : topic,
                    'kafka_bootstrap_servers': KafkaConnectorSpec.kafka.outsideBootstrapServers,
                    'kafka_consumer_group': UUID.randomUUID().toString(),
                    'mongodb_hosts': 'tc-mongodb:27017',
                    'mongodb_collection': collection.getNamespace().getCollectionName(),
                    'mongodb_database': database.getName(),
                    'mongodb_ssl': ssl,
                    'mongodb_ssl_validation_enabled': !ssl
            ])

            cnt.start()
        when:
           collection.insertOne(Document.parse(payload))
        then:
            await(10, TimeUnit.SECONDS) {
                def records = KafkaConnectorSpec.kafka.poll(group, topic)
                records.any {
                    def jsonDoc = Document.parse(it.value())
                    return jsonDoc.getBoolean("owned") == ssl && "powergrid".equals(jsonDoc.getString("title"))
                }
            }
        cleanup:
            closeQuietly(mongoClient)
            closeQuietly(cnt)
        where:
            ssl << [true, false]
    }

}
