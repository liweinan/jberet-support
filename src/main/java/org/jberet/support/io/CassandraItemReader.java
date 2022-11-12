/*
 * Copyright (c) 2018 Red Hat, Inc. and/or its affiliates.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.jberet.support.io;

import java.beans.IntrospectionException;
import java.beans.PropertyDescriptor;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import jakarta.batch.api.BatchProperty;
import jakarta.batch.api.chunk.ItemReader;
import javax.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import com.datastax.driver.core.ColumnDefinitions;
import com.datastax.driver.core.DataType;
import com.datastax.driver.core.LocalDate;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.SimpleStatement;
import com.datastax.driver.core.Statement;
import com.datastax.driver.core.TypeCodec;
import org.jberet.support._private.SupportLogger;
import org.jberet.support._private.SupportMessages;

/**
 * An implementation of {@code jakarta.batch.api.chunk.ItemReader} that reads data items from the Cassandra cluster.
 *
 * @see CassandraItemWriter
 * @see CassandraBatchlet
 * @see CassandraReaderWriterBase
 *
 * @since 1.3.0
 */
@Named
@Dependent
public class CassandraItemReader extends CassandraReaderWriterBase implements ItemReader {
    /**
     * The row number in the {@code ResultSet} to start reading.  It's a positive integer starting from 1.
     */
    @Inject
    @BatchProperty
    protected int start;

    /**
     * The row number in the {@code ResultSet} to end reading (inclusive).  It's a positive integer starting from 1.
     */
    @Inject
    @BatchProperty
    protected int end;

    /**
     * The query fetch size. As stated in {@code com.datastax.driver.core.Statement#setFetchSize(int)},
     * the fetch size controls how much resulting rows will be retrieved simultaneously
     * (the goal being to avoid loading too much results in memory for queries yielding large results).
     * Please note that while value as low as 1 can be used,
     * it is *highly* discouraged to use such a low value in practice as it will yield very poor performance.
     * If in doubt, leaving the default is probably a good idea.
     * Optional property, and defaults to null (not specified).
     */
    @Inject
    @BatchProperty
    protected Integer fetchSize;

    /**
     * String keys used in target data structure for database columns. Optional property, and if not specified, it
     * defaults to {@link #columnLabels}.
     * <p>
     * For example, if {@link #cql} is
     * <p>
     * SELECT NAME, ADDRESS, AGE FROM PERSON
     * <p>
     * And you want to map the data to the following form:
     * <p>
     * {"fn" = "Jon", "addr" = "1 Main st", "age" = 30}
     * <p>
     * then {@code columnMapping} should be specified as follows in job xml:
     * <p>
     * "fn, addr, age"
     */
    @Inject
    @BatchProperty
    protected String[] columnMapping;

    /**
     * Whether to perform bean validation with Bean Validation API, if the {@link #beanType}
     * is a custom POJO bean type. Optional property, and defaults to false
     * (perform bean validation).
     */
    @Inject
    @BatchProperty
    protected boolean skipBeanValidation;

    /**
     * The column names of the {@code ResultSet}
     */
    protected String[] columnLabels;

    /**
     * The regular cql statement based on {@link #cql}
     */
    protected Statement statement;

    /**
     * The {@code com.datastax.driver.core.ResultSet} from executing {@link #statement}
     */
    protected com.datastax.driver.core.ResultSet resultSet;

    /**
     * The iterator of {@code com.datastax.driver.core.Row} based on {@link #resultSet}
     */
    protected Iterator<Row> rowIterator;

    /**
     * The column definitions of the {@code ResultSet}
     */
    protected ColumnDefinitions columnDefinitions;

    /**
     * For {@link #beanType} of custom POJO bean, this property defines a mapping
     * between POJO bean's property name and its JavaBeans {@code java.beans.PropertyDescriptor}.
     */
    protected Map<String, PropertyDescriptor> propertyDescriptorMap;

    /**
     * The current row number.
     */
    protected int currentRowNumber;

    /**
     * {@inheritDoc}
     */
    @Override
    public void open(final Serializable checkpoint) throws Exception {
        if (session == null) {
            initSession();
        }

        initBeanPropertyDescriptors();

        if (statement == null) {
            statement = new SimpleStatement(cql);
        }

        if (fetchSize != null) {
            statement.setFetchSize(fetchSize);
        }
        resultSet = session.execute(statement);
        rowIterator = resultSet.iterator();

        columnDefinitions = resultSet.getColumnDefinitions();
        if (columnMapping == null) {
            if (beanType != List.class) {
                final int columnCount = columnDefinitions.size();
                columnLabels = new String[columnCount];
                for (int i = 0; i < columnCount; ++i) {
                    columnLabels[i] = columnDefinitions.getName(i);
                }
                columnMapping = columnLabels;
            }
        } else if (columnMapping.length != columnDefinitions.size()) {
            throw SupportMessages.MESSAGES.invalidReaderWriterProperty(null, Arrays.toString(columnMapping), "columnMapping");
        }

        if (start <= 0) {
            start = 1;
        }
        if (end == 0) {
            end = Integer.MAX_VALUE;
        }
        if (end < start) {
            throw SupportMessages.MESSAGES.invalidReaderWriterProperty(null, String.valueOf(end), "end");
        }

        //readyPosition is the position before the first item to be read
        int readyPosition = start - 1;
        if (checkpoint != null) {
            final int checkpointPosition = (Integer) checkpoint;
            if (checkpointPosition > readyPosition) {
                readyPosition = checkpointPosition;
            }
        }

        for (; currentRowNumber < readyPosition && rowIterator.hasNext(); currentRowNumber++) {
            rowIterator.next();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object readItem() throws Exception {
        if (currentRowNumber >= end) {
            return null;
        }
        Object result = null;
        if (rowIterator.hasNext()) {
            final Row row = rowIterator.next();

            if (beanType == List.class) {
                final List<Object> resultList = new ArrayList<Object>();
                for (int i = 0, k = columnDefinitions.size(); i < k; ++i) {
                    resultList.add(getColumnValue(row, i, null));
                }
                result = resultList;
            } else if (beanType == Map.class) {
                final Map<String, Object> resultMap = new HashMap<String, Object>();
                for (int i = 0; i < columnMapping.length; ++i) {
                    resultMap.put(columnMapping[i], getColumnValue(row, i, null));
                }
                result = resultMap;
            } else if (beanType != null) {
                final Object readValue = beanType.getDeclaredConstructor().newInstance();
                Object columnValue;
                for (int i = 0; i < columnMapping.length; ++i) {
                    final PropertyDescriptor propertyDescriptor = propertyDescriptorMap.get(columnMapping[i]);
                    columnValue = getColumnValue(row, i, propertyDescriptor.getPropertyType());
                    if (columnValue != null) {
                        propertyDescriptor.getWriteMethod().invoke(readValue, columnValue);
                    }
                }

                if (!skipBeanValidation) {
                    ItemReaderWriterBase.validate(readValue);
                }
                result = readValue;
            } else {
                throw SupportMessages.MESSAGES.invalidReaderWriterProperty(null, null, "beanType");
            }
        }
        currentRowNumber++;
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Serializable checkpointInfo() throws Exception {
        return currentRowNumber;
    }

    @Override
    protected void initBeanPropertyDescriptors() throws IntrospectionException {
        super.initBeanPropertyDescriptors();
        if (propertyDescriptors != null) {
            propertyDescriptorMap = new HashMap<>();
            for (PropertyDescriptor propertyDescriptor : propertyDescriptors) {
                final String name = propertyDescriptor.getName();
                if (!name.equals("class")) {
                    propertyDescriptorMap.put(name, propertyDescriptor);
                }
            }
        }
    }

    /**
     * Gets the value for a column in a row of data.
     *
     * @param row the current row of data
     * @param position the position of current column within the current row, 0-based
     * @param desiredType the desired Java type when de-serializing column data.
     *                    If null, it's up to the underlying driver to determine the
     *                    Java type mapping. This parameter is typically used when
     *                    the {@link #beanType} is custom POJO and its field types
     *                    differ from the default CQL data type mapping. If more
     *                    customization is needed during de-serialization, consider
     *                    using {@link #customCodecs}.
     *
     * @return the current column value
     */
    private Object getColumnValue(final Row row, final int position, final Class<?> desiredType) {
        if (row.isNull(position)) {
            return null;
        }

        Object val;
        final DataType columnDefinitionsType = columnDefinitions.getType(position);
        final DataType.Name cqlType = columnDefinitionsType.getName();

        //for POJO beanType, first check if any custom codec should be used based on the
        //POJO field type (desiredType).
        //for List or Map beanType, since desiredType is not passed in, this explicit check
        //of custom codecs is not performed. Just let the driver do the work with built-in and
        //registered codecs.
        if (desiredType != null && customCodecList != null) {
            for (TypeCodec c : customCodecList) {
                if (c.accepts(desiredType) && c.accepts(columnDefinitionsType)) {
                    return row.get(position, c);
                }
            }
        }

        switch (cqlType) {
            case ASCII:
            case TEXT:
            case VARCHAR:
                val = row.getString(position);
                break;
            case INT:
                val = row.getInt(position);
                break;
            case BIGINT:
            case COUNTER:
            case TIME:
                val = row.getLong(position);
                break;
            case BOOLEAN:
                val = row.getBool(position);
                break;
            case DOUBLE:
                val = row.getDouble(position);
                break;
            case VARINT:
                val = row.getVarint(position);
                break;
            case TINYINT:
                val = row.getByte(position);
                break;
            case SMALLINT:
                val = row.getShort(position);
                break;
            case FLOAT:
                val = row.getFloat(position);
                break;
            case DECIMAL:
                val = row.getDecimal(position);
                break;
            case DATE:
                final LocalDate localDate = row.getDate(position);
                if (desiredType == long.class || desiredType == Long.class) {
                    val = localDate.getMillisSinceEpoch();
                } else if (desiredType == java.util.Date.class) {
                    val = new java.util.Date(localDate.getMillisSinceEpoch());
                } else {
                    val = localDate;
                }
                break;
            case TIMESTAMP:
                final Date date = row.getTimestamp(position);
                if (desiredType == long.class || desiredType == Long.class) {
                    val = date.getTime();
                } else {
                    val = date;
                }
                break;
            case UUID:
            case TIMEUUID:
                val = row.getUUID(position);
                break;
            case BLOB:
                val = row.getBytes(position);
                break;
            case INET:
                val = row.getInet(position);
                break;
            case DURATION:
                val = row.getObject(position);
                break;
            case TUPLE:
                val = row.getTupleValue(position);
                break;
            case UDT:
                val = row.getUDTValue(position);
                break;
            case LIST:
                val = row.getList(position, Object.class);
                break;
            case MAP:
                val = row.getMap(position, Object.class, Object.class);
                break;
            case SET:
                val = row.getSet(position, Object.class);
                break;
            default:
                SupportLogger.LOGGER.unsupportedDataType(cqlType.name());
                val = row.getObject(position);
        }
        return val;
    }
}
