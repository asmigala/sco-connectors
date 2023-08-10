package com.github.sco1237896.connector.it.saas.salesforce

import groovy.util.logging.Slf4j
import com.github.sco1237896.connector.it.support.ConnectorContainer
import com.github.sco1237896.connector.it.support.SimpleConnectorSpec

@Slf4j
class ConnectorContainerIT extends SimpleConnectorSpec {

    def "container image exposes health and metrics"(String definition) {
        setup:
        def cnt = ConnectorContainer.forDefinition(definition).build()
        cnt.start()
        when:
        def health = cnt.request.get('/q/health')
        def metrics = cnt.request.get("/q/metrics")
        then:
        health.statusCode == 200
        metrics.statusCode == 200

        with (health.as(Map.class)) {
            status == 'UP'
            checks.find {
                it.name == 'context' && it.status == 'UP'
            }
        }
        cleanup:
        closeQuietly(cnt)
        where:
        definition << [
                'salesforce_create_sink_v1.json',
                'salesforce_delete_sink_v1.json',
                'salesforce_update_sink_v1.json',
                'salesforce_streaming_source_v1.json'
        ]
    }
}
