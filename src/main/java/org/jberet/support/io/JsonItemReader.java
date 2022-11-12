/*
 * Copyright (c) 2014-2018 Red Hat, Inc. and/or its affiliates.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.jberet.support.io;

import java.io.Serializable;
import java.util.Map;
import jakarta.batch.api.BatchProperty;
import jakarta.batch.api.chunk.ItemReader;
import javax.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.io.InputDecorator;
import org.jberet.support._private.SupportLogger;
import org.jberet.support._private.SupportMessages;

/**
 * An implementation of {@code jakarta.batch.api.chunk.ItemReader} that reads from Json resource that consists of a
 * collection of same-typed data items. Its {@link #readItem()} method reads one item at a time, and binds it to a
 * user-provided bean type that represents individual data item in the source Json resource. The data item may also
 * be bound to {@code java.util.Map} or {@code com.fasterxml.jackson.databind.JsonNode} for applications that do not
 * need application bean type.
 *
 * @see     JsonItemWriter
 * @see     JsonItemReaderWriterBase
 * @since   1.0.2
 */
@Named
@Dependent
public class JsonItemReader extends JsonItemReaderWriterBase implements ItemReader {
    /**
     * The bean type that represents individual data item in the source Json {@link #resource}. Required property, and
     * valid values are:
     * <p>
     * <ul>
     *    <li>any custom bean type, for example {@code org.jberet.support.io.StockTrade}
     *    <li>{@code java.util.Map}
     *    <li>{@code com.fasterxml.jackson.databind.JsonNode}
     * </ul>
     */
    @Inject
    @BatchProperty
    protected Class beanType;

    /**
     * Specifies the start position (a positive integer starting from 1) to read the data. If reading from the beginning
     * of the input Json resource, there is no need to specify this property.
     */
    @Inject
    @BatchProperty
    protected int start;

    /**
     * Specify the end position in the data set (inclusive). Optional property, and defaults to {@code Integer.MAX_VALUE}.
     * If reading till the end of the input Json resource, there is no need to specify this property.
     */
    @Inject
    @BatchProperty
    protected int end;

    /**
     * A comma-separated list of key-value pairs that specify {@code com.fasterxml.jackson.core.JsonParser} features.
     * Optional property and defaults to null. For example,
     * <p>
     * <pre>
     * ALLOW_COMMENTS=true, ALLOW_YAML_COMMENTS=true, ALLOW_NUMERIC_LEADING_ZEROS=true, STRICT_DUPLICATE_DETECTION=true
     * </pre>
     * @see "com.fasterxml.jackson.core.JsonParser.Feature"
     */
    @Inject
    @BatchProperty
    protected Map<String, String> jsonParserFeatures;

    /**
     * A comma-separated list of fully-qualified names of classes that implement
     * {@code com.fasterxml.jackson.databind.deser.DeserializationProblemHandler}, which can be registered to get
     * called when a potentially recoverable problem is encountered during deserialization process.
     * Handlers can try to resolve the problem, throw an exception or do nothing. Optional property and defaults to null.
     * For example,
     * <p>
     * <pre>
     * org.jberet.support.io.JsonItemReaderTest$UnknownHandler, org.jberet.support.io.JsonItemReaderTest$UnknownHandler2
     * </pre>
     *
     * @see "com.fasterxml.jackson.databind.deser.DeserializationProblemHandler"
     * @see "com.fasterxml.jackson.databind.ObjectMapper#addHandler(com.fasterxml.jackson.databind.deser.DeserializationProblemHandler)"
     * @see MappingJsonFactoryObjectFactory#configureDeserializationProblemHandlers(com.fasterxml.jackson.databind.ObjectMapper, java.lang.String, java.lang.ClassLoader)
     */
    @Inject
    @BatchProperty
    protected String deserializationProblemHandlers;

    /**
     * Fully-qualified name of a class that extends {@code com.fasterxml.jackson.core.io.InputDecorator}, which can be
     * used to decorate input sources. Typical use is to use a filter abstraction (filtered stream, reader)
     * around original input source, and apply additional processing during read operations. Optional property and
     * defaults to null. For example,
     * <p>
     * <pre>
     * org.jberet.support.io.JsonItemReaderTest$NoopInputDecorator
     * </pre>
     *
     * @see "com.fasterxml.jackson.core.JsonFactory#setInputDecorator(com.fasterxml.jackson.core.io.InputDecorator)"
     * @see "com.fasterxml.jackson.core.io.InputDecorator"
     */
    @Inject
    @BatchProperty
    protected Class inputDecorator;

    protected JsonParser jsonParser;
    private JsonToken token;
    protected int rowNumber;

    @Override
    public void open(final Serializable checkpoint) throws Exception {
        if (end == 0) {
            end = Integer.MAX_VALUE;
        }
        if (checkpoint != null) {
            start = (Integer) checkpoint;
        }
        if (start > end) {
            throw SupportMessages.MESSAGES.invalidStartPosition((Integer) checkpoint, start, end);
        }
        initJsonFactoryAndObjectMapper();
        jsonParser = configureJsonParser(this, inputDecorator, deserializationProblemHandlers, jsonParserFeatures);
    }

    @Override
    public Object readItem() throws Exception {
        if (rowNumber >= end) {
            return null;
        }
        int nestedObjectLevel = 0;
        do {
            token = jsonParser.nextToken();
            if (token == null) {
                return null;
            } else if (token == JsonToken.START_OBJECT) {
                nestedObjectLevel++;
                if (nestedObjectLevel == 1) {
                    rowNumber++;
                } else if (nestedObjectLevel < 1) {
                    throw SupportMessages.MESSAGES.unexpectedJsonContent(jsonParser.getCurrentLocation());
                }
                if (rowNumber >= start) {
                    break;
                }
            } else if (token == JsonToken.END_OBJECT) {
                nestedObjectLevel--;
            }
        } while (true);
        final Object readValue = objectMapper.readValue(jsonParser, beanType);
        if (!skipBeanValidation) {
            ItemReaderWriterBase.validate(readValue);
        }
        return readValue;
    }

    @Override
    public Serializable checkpointInfo() throws Exception {
        return rowNumber;
    }

    @Override
    public void close() throws Exception {
        if (jsonParser != null) {
            SupportLogger.LOGGER.closingResource(resource, this.getClass());
            if (deserializationProblemHandlers != null) {
                objectMapper.clearProblemHandlers();
            }
            jsonParser.close();
            jsonParser = null;
        }
    }

    protected static JsonParser configureJsonParser(final JsonItemReaderWriterBase batchReaderArtifact,
                                                    final Class<?> inputDecorator,
                                                    final String deserializationProblemHandlers,
                                                    final Map<String, String> jsonParserFeatures) throws Exception {
        final JsonParser jsonParser;
        if (inputDecorator != null) {
            batchReaderArtifact.jsonFactory.setInputDecorator(
                    (InputDecorator) inputDecorator.getDeclaredConstructor().newInstance());
        }

        jsonParser = batchReaderArtifact.jsonFactory.createParser(getInputStream(batchReaderArtifact.resource, false));

        if (deserializationProblemHandlers != null) {
            MappingJsonFactoryObjectFactory.configureDeserializationProblemHandlers(
                    batchReaderArtifact.objectMapper, deserializationProblemHandlers,
                    batchReaderArtifact.getClass().getClassLoader());
        }
        SupportLogger.LOGGER.openingResource(batchReaderArtifact.resource, batchReaderArtifact.getClass());

        if (jsonParserFeatures != null) {
            for (final Map.Entry<String, String> e : jsonParserFeatures.entrySet()) {
                final String key = e.getKey();
                final String value = e.getValue();
                final JsonParser.Feature feature;
                try {
                    feature = JsonParser.Feature.valueOf(key);
                } catch (final Exception e1) {
                    throw SupportMessages.MESSAGES.unrecognizedReaderWriterProperty(key, value);
                }
                if ("true".equals(value)) {
                    if (!feature.enabledByDefault()) {
                        jsonParser.configure(feature, true);
                    }
                } else if ("false".equals(value)) {
                    if (feature.enabledByDefault()) {
                        jsonParser.configure(feature, false);
                    }
                } else {
                    throw SupportMessages.MESSAGES.invalidReaderWriterProperty(null, value, key);
                }
            }
        }

        return jsonParser;
    }
}
