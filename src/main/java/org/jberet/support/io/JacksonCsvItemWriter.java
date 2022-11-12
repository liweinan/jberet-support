/*
 * Copyright (c) 2015 Red Hat, Inc. and/or its affiliates.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.jberet.support.io;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import jakarta.batch.api.BatchProperty;
import jakarta.batch.api.chunk.ItemWriter;
import javax.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import com.fasterxml.jackson.dataformat.csv.CsvGenerator;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import org.jberet.support._private.SupportLogger;
import org.jberet.support._private.SupportMessages;

/**
 * An implementation of {@code jakarta.batch.api.chunk.ItemWriter} that writes data in CSV format using jackson-dataformat-csv.
 *
 * @see CsvItemWriter
 * @see JacksonCsvItemReader
 * @see JacksonCsvItemReaderWriterBase
 * @since 1.2.0
 */
@Named
@Dependent
public class JacksonCsvItemWriter extends JacksonCsvItemReaderWriterBase implements ItemWriter {
    /**
     * Instructs this class, when the target CSV resource already exists, whether to append to, or overwrite
     * the existing resource, or fail. Valid values are {@code append}, {@code overwrite}, and {@code failIfExists}.
     * Optional property, and defaults to {@code append}.
     */
    @Inject
    @BatchProperty
    protected String writeMode;

    /**
     * Character used to separate data rows.
     * Only used by generator; parser accepts three standard linefeeds ("\r", "\r\n", "\n").
     * Optional protected and defaults to '\n'.
     *
     * @see "com.fasterxml.jackson.dataformat.csv.CsvSchema"
     */
    @Inject
    @BatchProperty
    protected String lineSeparator;

    /**
     * A comma-separated list of key-value pairs that specify {@code com.fasterxml.jackson.core.JsonGenerator} features.
     * Optional property and defaults to null. Keys and values must be defined in
     * {@code com.fasterxml.jackson.core.JsonGenerator.Feature}. For example,
     * <p/>
     * <pre>
     * WRITE_BIGDECIMAL_AS_PLAIN=true, WRITE_NUMBERS_AS_STRINGS=true, QUOTE_NON_NUMERIC_NUMBERS=false
     * </pre>
     *
     * @see "com.fasterxml.jackson.core.JsonGenerator.Feature"
     */
    @Inject
    @BatchProperty
    protected Map<String, String> jsonGeneratorFeatures;

    /**
     * A comma-separated list of key-value pairs that specify {@code com.fasterxml.jackson.dataformat.csv.CsvGenerator.Feature}.
     * Optional property and defaults to null. For example,
     * <p/>
     * <pre>
     * ALWAYS_QUOTE_STRINGS=false, STRICT_CHECK_FOR_QUOTING=true, OMIT_MISSING_TAIL_COLUMNS=false
     * </pre>
     * With the above configuration, this writer will quote string values only when necessary,
     * and will omit columns without matching value when they are the last values of the row.
     *
     * @see "com.fasterxml.jackson.dataformat.csv.CsvGenerator.Feature"
     */
    @Inject
    @BatchProperty
    protected Map<String, String> csvGeneratorFeatures;

    /**
     * Fully-qualified name of a class that implements {@code com.fasterxml.jackson.core.io.OutputDecorator}, which
     * can be used to decorate output destinations. Typical use is to use a filter abstraction (filtered output stream,
     * writer) around original output destination, and apply additional processing during write operations.
     * Optional property and defaults to null. For example,
     * <p/>
     * <pre>
     * org.jberet.support.io.JsonItemReaderTest$NoopOutputDecorator
     * </pre>
     *
     * @see "com.fasterxml.jackson.core.io.OutputDecorator"
     * @see "com.fasterxml.jackson.core.JsonFactory#setOutputDecorator(com.fasterxml.jackson.core.io.OutputDecorator)"
     */
    @Inject
    @BatchProperty
    protected Class outputDecorator;

    protected CsvGenerator csvGenerator;

    @Override
    public void writeItems(final List<Object> items) throws Exception {
        for (final Object o : items) {
            csvGenerator.writeObject(o);
        }
        csvGenerator.flush();
    }

    @Override
    public void open(final Serializable checkpoint) throws Exception {
        init();
        csvGenerator = (CsvGenerator) JsonItemWriter.configureJsonGenerator(jsonFactory, getOutputStream(writeMode), outputDecorator, jsonGeneratorFeatures);

        if (csvGeneratorFeatures != null) {
            for (final Map.Entry<String, String> e : csvGeneratorFeatures.entrySet()) {
                final String key = e.getKey();
                final String value = e.getValue();
                final CsvGenerator.Feature feature;
                try {
                    feature = CsvGenerator.Feature.valueOf(key);
                } catch (final Exception e1) {
                    throw SupportMessages.MESSAGES.unrecognizedReaderWriterProperty(key, value);
                }
                if ("true".equals(value)) {
                    if (!feature.enabledByDefault()) {
                        csvGenerator.configure(feature, true);
                    }
                } else if ("false".equals(value)) {
                    if (feature.enabledByDefault()) {
                        csvGenerator.configure(feature, false);
                    }
                } else {
                    throw SupportMessages.MESSAGES.invalidReaderWriterProperty(null, value, key);
                }
            }
        }

        if (columns != null) {
            CsvSchema schema = buildCsvSchema(null);
            if (lineSeparator != null) {
                schema = schema.withLineSeparator(lineSeparator);
            }
            csvGenerator.setSchema(schema);
        }
    }

    @Override
    public void close() throws Exception {
        if (csvGenerator != null) {
            SupportLogger.LOGGER.closingResource(resource, this.getClass());
            csvGenerator.close();
            csvGenerator = null;
        }
    }

    @Override
    public Serializable checkpointInfo() throws Exception {
        return null;
    }
}
