package com.capitalone.calc.app;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;

/**
 * The body does not match the tenant's schema. The detail names the offending field but never
 * Jackson's message, which would expose the tenant module's class names.
 */
class InvalidInputException extends ApiException {
    private static final Pattern CREATOR_PROPERTY =
            Pattern.compile("^(?:Missing (?:required )?creator property|Null value for creator property) '([^']+)'");

    InvalidInputException(String detail) {
        super(HttpStatus.BAD_REQUEST, Problems.INVALID_INPUT, detail);
    }

    /** Describes a Jackson failure; prefix is the JSON path of the object being read, or empty for the body. */
    static InvalidInputException from(Throwable failure, String prefix) {
        JsonMappingException mapping = findCause(failure);
        if (mapping == null) {
            return new InvalidInputException("Request body is not valid JSON");
        }
        String path = mapping.getPath().stream()
                .map(ref -> ref.getFieldName() != null ? ref.getFieldName() : "[" + ref.getIndex() + "]")
                .collect(Collectors.joining("."));
        if (mapping instanceof UnrecognizedPropertyException) {
            return new InvalidInputException("Unknown field '" + join(prefix, path) + "'");
        }
        Matcher creator = CREATOR_PROPERTY.matcher(String.valueOf(mapping.getOriginalMessage()));
        if (creator.find()) {
            String field = path.isEmpty() ? creator.group(1) : path;
            return new InvalidInputException("Missing or null field '" + join(prefix, field) + "'");
        }
        if (!path.isEmpty()) {
            return new InvalidInputException("Invalid value for field '" + join(prefix, path) + "'");
        }
        return new InvalidInputException(prefix.isEmpty()
                ? "Request body does not match the expected shape"
                : "'" + prefix + "' does not match this tenant's schema");
    }

    private static JsonMappingException findCause(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof JsonMappingException mapping) {
                return mapping;
            }
        }
        return null;
    }

    private static String join(String left, String right) {
        if (left.isEmpty()) {
            return right;
        }
        return right.isEmpty() ? left : left + "." + right;
    }
}
