package com.github.sco1237896.connector.kamelet.serdes.json;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.common.header.Headers;
import com.github.sco1237896.connector.kamelet.serdes.BaseSerializer;

import com.fasterxml.jackson.databind.JsonNode;

import io.apicurio.registry.serde.headers.MessageTypeSerdeHeaders;

public class JsonSerializer extends BaseSerializer<JsonNode> {
    private MessageTypeSerdeHeaders serdeHeaders;

    public JsonSerializer() {
        super(Json.SCHEMA_PARSER);
    }

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
        super.configure(configs, isKey);

        serdeHeaders = new MessageTypeSerdeHeaders(new HashMap<>(configs), isKey);
    }

    @Override
    protected void configureHeaders(Headers headers) {
        serdeHeaders.addMessageTypeHeader(headers, byte[].class.getName());
    }
}
