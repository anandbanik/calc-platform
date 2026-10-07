package com.capitalone.calc.app;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;
import com.fasterxml.jackson.databind.ser.std.StdScalarSerializer;
import java.io.IOException;
import java.math.BigDecimal;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The platform's ObjectMapper rejects unknown, missing and null fields, and carries money as a
 * decimal string in both directions. Tenant modules therefore need no JSON library.
 */
@Configuration
class JsonConfig {

    @Bean
    Jackson2ObjectMapperBuilderCustomizer strictJson() {
        return builder -> builder
                .featuresToEnable(
                        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                        DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES,
                        DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .featuresToDisable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .deserializerByType(BigDecimal.class, new MoneyDeserializer())
                .serializerByType(BigDecimal.class, new MoneySerializer());
    }

    /** Accepts "1500.00", rejects 1500.00: a JSON number may already have lost precision. */
    static final class MoneyDeserializer extends StdScalarDeserializer<BigDecimal> {
        MoneyDeserializer() {
            super(BigDecimal.class);
        }

        @Override
        public BigDecimal deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            if (p.currentToken() != JsonToken.VALUE_STRING) {
                return (BigDecimal) ctxt.handleUnexpectedToken(BigDecimal.class, p);
            }
            String text = p.getText().trim();
            try {
                return new BigDecimal(text);
            } catch (NumberFormatException e) {
                return (BigDecimal) ctxt.handleWeirdStringValue(BigDecimal.class, text, "not a decimal number");
            }
        }
    }

    /** Writes "1850.00": plain notation, as a string. */
    static final class MoneySerializer extends StdScalarSerializer<BigDecimal> {
        MoneySerializer() {
            super(BigDecimal.class);
        }

        @Override
        public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider provider) throws IOException {
            gen.writeString(value.toPlainString());
        }
    }
}
