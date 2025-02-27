package com.github.sco1237896.connector.kamelet.serdes;

import java.nio.ByteBuffer;
import java.util.Map;

import org.apache.kafka.common.header.Headers;

import io.apicurio.registry.resolver.ParsedSchema;
import io.apicurio.registry.resolver.SchemaParser;
import io.apicurio.registry.serde.AbstractKafkaDeserializer;

public abstract class BaseDeserializer<S> extends AbstractKafkaDeserializer<S, byte[]> {
    private final SchemaParser<S, byte[]> parser;

    protected BaseDeserializer(SchemaParser<S, byte[]> parser) {
        this.parser = parser;
    }

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
        super.configure(new BaseConfig(configs), isKey);
    }

    @Override
    public SchemaParser<S, byte[]> schemaParser() {
        return parser;
    }

    @Override
    protected byte[] readData(ParsedSchema<S> schema, ByteBuffer buffer, int start, int length) {
        throw new UnsupportedOperationException("Unsupported");
    }

    @Override
    protected byte[] readData(Headers headers, ParsedSchema<S> schema, ByteBuffer buffer, int start, int length) {
        configureHeaders(headers, schema);

        byte[] msgData = new byte[length];
        buffer.get(msgData, start, length);

        return msgData;
    }

    protected abstract void configureHeaders(Headers headers, ParsedSchema<S> schema);
}
