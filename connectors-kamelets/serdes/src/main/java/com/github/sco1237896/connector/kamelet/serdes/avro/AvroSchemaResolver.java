package com.github.sco1237896.connector.kamelet.serdes.avro;

import java.io.InputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.avro.Schema;
import org.apache.camel.Exchange;
import org.apache.camel.ExtendedCamelContext;
import org.apache.camel.Processor;
import org.apache.camel.component.jackson.SchemaResolver;
import org.apache.camel.spi.Resource;
import org.apache.camel.util.ObjectHelper;
import com.github.sco1237896.connector.kamelet.serdes.Serdes;

import com.fasterxml.jackson.core.FormatSchema;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.dataformat.avro.AvroSchema;

import static com.github.sco1237896.connector.kamelet.serdes.SerdesHelper.isPojo;

public class AvroSchemaResolver implements SchemaResolver, Processor {
    private final ConcurrentMap<String, AvroSchema> schemes;

    private AvroSchema schema;
    private String contentClass;

    public AvroSchemaResolver() {
        this.schemes = new ConcurrentHashMap<>();
    }

    public String getSchema() {
        if (this.schema != null) {
            return this.schema.getAvroSchema().toString();
        }

        return null;
    }

    public void setSchema(String schema) {
        if (ObjectHelper.isNotEmpty(schema)) {
            this.schema = new AvroSchema(new Schema.Parser().parse(schema));
        } else {
            this.schema = null;
        }
    }

    public String getContentClass() {
        return contentClass;
    }

    public void setContentClass(String contentClass) {
        if (ObjectHelper.isNotEmpty(contentClass)) {
            this.contentClass = contentClass;
        } else {
            this.contentClass = null;
        }
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        Object payload = exchange.getMessage().getBody();
        if (payload == null) {
            return;
        }

        AvroSchema answer = this.schema;

        if (answer == null) {
            answer = exchange.getProperty(Serdes.CONTENT_SCHEMA, AvroSchema.class);
        }

        if (answer == null) {
            String contentClass = exchange.getProperty(Serdes.CONTENT_CLASS, this.contentClass, String.class);
            if (contentClass == null && isPojo(payload.getClass())) {
                contentClass = payload.getClass().getName();
            }
            if (contentClass == null) {
                return;
            }

            answer = this.schemes.computeIfAbsent(contentClass, t -> {
                Resource res = exchange.getContext()
                        .adapt(ExtendedCamelContext.class)
                        .getResourceLoader()
                        .resolveResource("classpath:schemas/" + Avro.SCHEMA_TYPE + "/" + t + "." + Avro.SCHEMA_TYPE);

                try {
                    if (res.exists()) {
                        try (InputStream is = res.getInputStream()) {
                            if (is != null) {
                                return Avro.MAPPER.schemaFrom(is);
                            }
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(
                            "Unable to load Avro schema for type: " + t + ", resource: " + res.getLocation(),
                            e);
                }

                try {
                    return Avro.MAPPER.schemaFor(payload.getClass());
                } catch (JsonMappingException e) {
                    throw new RuntimeException(
                            "Unable to compute Avro schema for type: " + t,
                            e);
                }
            });
        }

        if (answer != null) {
            exchange.setProperty(Serdes.CONTENT_SCHEMA, answer);
            exchange.setProperty(Serdes.CONTENT_SCHEMA_TYPE, Avro.SCHEMA_TYPE);
            exchange.setProperty(Serdes.CONTENT_CLASS, payload.getClass().getName());
        }
    }

    @Override
    public FormatSchema resolve(Exchange exchange) {
        return exchange.getProperty(Serdes.CONTENT_SCHEMA, AvroSchema.class);
    }
}
