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

import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jakarta.batch.api.BatchProperty;
import jakarta.batch.api.chunk.ItemWriter;
import javax.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import com.datastax.driver.core.BatchStatement;
import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.ColumnDefinitions;
import com.datastax.driver.core.DataType;
import com.datastax.driver.core.Duration;
import com.datastax.driver.core.LocalDate;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.TupleValue;
import com.datastax.driver.core.UDTValue;
import org.jberet.support._private.SupportLogger;

/**
 * An implementation of {@code jakarta.batch.api.chunk.ItemWriter} that inserts data items into Cassandra cluster.
 *
 * @see CassandraItemReader
 * @see CassandraReaderWriterBase
 * @see CassandraBatchlet
 *
 * @since 1.3.0
 */
@Named
@Dependent
public class CassandraItemWriter extends CassandraReaderWriterBase implements ItemWriter {

    /**
     * When the cql parameter (variable) name only differs from the corresponding table column name
     * in case (i.e., they are same when compared case insensitive), the current driver uses the column
     * name as the column definition name. When using this value as key to look up
     * in the date item map, it will return null if the Map is keyed with the cql parameter (variable)
     * name. Therefore, this property can be used to specify the parameter names in the correct case
     * matching the keys in data item map.
     */
    @Inject
    @BatchProperty
    protected String[] parameterNames;

    /**
     * The Cassandra batch statement that contains all insert statements within the
     * current chunk processing cycle. After the batch inserts for the current chunk
     * ends, it is cleared to be used by the next chunk.
     */
    protected BatchStatement batchStatement = new BatchStatement();

    /**
     * The {@code com.datastax.driver.core.PreparedStatement} based on {@link #cql}
     */
    protected PreparedStatement preparedStatement;

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeItems(final List<Object> items) throws Exception {
        try {
            for (final Object item : items) {
                batchStatement.add(mapParameters(item));
            }
            final ResultSet resultSet = session.execute(batchStatement);
        } finally {
            batchStatement.clear();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void open(final Serializable checkpoint) throws Exception {
        if (session == null) {
            initSession();
        }

        if (preparedStatement == null) {
            preparedStatement = session.prepare(cql);
        }

        //if parameterNames is null, assume the cql string contains named parameters
        //and the parameter value will be bound with its name instead of the index.

        initBeanPropertyDescriptors();
    }

    /**
     * {@inheritDoc}
     * <p>
     * This method returns null.
     */
    @Override
    public Serializable checkpointInfo() throws Exception {
        return null;
    }

    private BoundStatement mapParameters(final Object item) throws Exception {
        final BoundStatement boundStatement;

        if (item instanceof List) {
            final List itemAsList = (List) item;
            final int itemSize = itemAsList.size();

            //the item is a list and should contain data of proper types, e.g., String, Integer, Date, etc,
            //and in the same order as CQL insert statement parameters.

            //the item list may contain more elements than the number of cql parameters
            //in the insert cql statement.

            int parameterCount = preparedStatement.getVariables().size();
            final Object[] itemAsArray = new Object[parameterCount];
            for (int i = 0; i < parameterCount && i < itemSize; i++) {
                itemAsArray[i] = itemAsList.get(i);
            }
            boundStatement = preparedStatement.bind(itemAsArray);
        } else {
            final Map itemAsMap;
            if (item instanceof Map) {
                itemAsMap = (Map) item;
            } else {
                if (propertyDescriptors == null) {
                    propertyDescriptors = Introspector.getBeanInfo(item.getClass()).getPropertyDescriptors();
                }
                itemAsMap = new HashMap();
                for (PropertyDescriptor d : propertyDescriptors) {
                    final String name = d.getName();
                    if(!name.equals("class")) {
                        final Object val = d.getReadMethod().invoke(item);
                        itemAsMap.put(name, val);
                    }
                }
            }
            boundStatement = preparedStatement.bind();
            for (ColumnDefinitions.Definition cd : preparedStatement.getVariables()) {
                final String name = cd.getName();
                Object val = itemAsMap.get(name);

                //When the cql parameter (variable) name only differs from the corresponding table column name
                //in case (i.e., they are same when compared case insensitive), the driver will use the column
                //name as the ColumnDefinition getName() return value. When using this value as key to look up
                //in the date item Map, it will return null if the Map is keyed with the cql parameter (variable)
                //name.
                //If cql parameter (variable) name is totally different than the corresponding table column name,
                //then the cql parameter (variable) name will be used as the ColumnDefinition getName() return value.

                if (val == null && parameterNames != null) {
                    for (String n : parameterNames) {
                        if (name.equalsIgnoreCase(n)) {
                            val = itemAsMap.get(n);
                            break;
                        }
                    }
                }

                if (val == null) {
                    SupportLogger.LOGGER.queryParameterNotBound(name, cql);
                } else {
                    setParameter(boundStatement, cd.getType().getName(), name, val);
                }
            }
        }
        return boundStatement;
    }

    /**
     * Sets the value for a parameter marker in the CQL statement.
     * If the value {@code v} matches the CQL data type {@code cqlType}, or it
     * can be easily converted to match, then the appropriate type-specific
     * set method is called on the {@code BoundStatement st} to bind the value.
     * <p>
     * Otherwise, a generic
     * {@code com.datastax.driver.core.BoundStatement#set(java.lang.String, java.lang.Object, java.lang.Class)}
     * method is called on {@code BoundStatement st} to bind the value.
     * This also makes it possible to enlist any registered custom codec
     * to perform more customized serialization and de-serialization.
     *
     * @param st the {@code com.datastax.driver.core.BoundStatement} to bind values to
     * @param cqlType the CQL data type of the current parameter marker
     * @param n the column name of the current parameter marker
     * @param v the value to bind to the current parameter marker
     */
    private void setParameter(final BoundStatement st, final DataType.Name cqlType,
                              final String n, final Object v) {
        switch (cqlType) {
            case ASCII:
            case TEXT:
            case VARCHAR:
                st.setString(n, v.toString());
                break;
            case INT:
                if (v instanceof Integer) {
                    st.setInt(n, (Integer) v);
                } else {
                    st.set(n, v, (Class<Object>) v.getClass());
                }
                break;
            case BIGINT:
            case COUNTER:
            case TIME:
                if (v instanceof Long) {
                    st.setLong(n, (Long) v);
                } else {
                    st.set(n, v, (Class<Object>) v.getClass());
                }
                break;
            case BOOLEAN:
                if (v instanceof Boolean) {
                    st.setBool(n, (Boolean) v);
                } else {
                    st.set(n, v, (Class<Object>) v.getClass());
                }
                break;
            case DOUBLE:
                if (v instanceof Double) {
                    st.setDouble(n, (Double) v);
                } else {
                    st.set(n, v, (Class<Object>) v.getClass());
                }
                break;
            case VARINT:
                if (v instanceof BigInteger) {
                    st.setVarint(n, (BigInteger) v);
                } else {
                    st.set(n, v, (Class<Object>) v.getClass());
                }
                break;
            case TINYINT:
                if (v instanceof Byte) {
                    st.setByte(n, (Byte) v);
                } else {
                    st.set(n, v, (Class<Object>) v.getClass());
                }
                break;
            case SMALLINT:
                if (v instanceof Short) {
                    st.setShort(n, (Short) v);
                } else {
                    st.set(n, v, (Class<Object>) v.getClass());
                }
                break;
            case FLOAT:
                if (v instanceof Float) {
                    st.setFloat(n, (Float) v);
                } else {
                    st.set(n, v, (Class<Object>) v.getClass());
                }
                break;
            case DECIMAL:
                if (v instanceof BigDecimal) {
                    st.setDecimal(n, (BigDecimal) v);
                } else {
                    st.set(n, v, (Class<Object>) v.getClass());
                }
                break;
            case DATE:
                if (v instanceof LocalDate) {
                    st.setDate(n, (LocalDate) v);
                } else if (v instanceof java.util.Date) {
                    final long time = ((Date) v).getTime();
                    st.setDate(n, LocalDate.fromMillisSinceEpoch(time));
                } else if (v instanceof Long){
                    st.setDate(n, LocalDate.fromMillisSinceEpoch((Long) v));
                } else {
                    st.set(n, v, (Class<Object>) v.getClass());
                }
                break;
            case TIMESTAMP:
                if (v instanceof java.util.Date) {
                    st.setTimestamp(n, (java.util.Date) v);
                } else if(v instanceof Long) {
                    st.setTimestamp(n, new java.util.Date((Long) v));
                } else {
                    st.set(n, v, (Class<Object>) v.getClass());
                }
                break;
            case UUID:
            case TIMEUUID:
                if (v instanceof java.util.UUID) {
                    st.setUUID(n, (java.util.UUID) v);
                } else {
                    st.set(n, v, (Class<Object>) v.getClass());
                }
                break;
            case BLOB:
                if (v instanceof java.nio.ByteBuffer) {
                    st.setBytes(n, (java.nio.ByteBuffer) v);
                } else {
                    st.set(n, v, (Class<Object>) v.getClass());
                }
                break;
            case INET:
                if (v instanceof java.net.InetAddress) {
                    st.setInet(n, (java.net.InetAddress) v);
                } else {
                    st.set(n, v, (Class<Object>) v.getClass());
                }
                break;
            case DURATION:
                if (v instanceof Duration) {
                    st.set(n, (Duration) v, Duration.class);
                } else {
                    st.set(n, v, (Class<Object>) v.getClass());
                }
                break;
            case TUPLE:
                if (v instanceof TupleValue) {
                    st.setTupleValue(n, (TupleValue) v);
                } else {
                    st.set(n, v, (Class<Object>) v.getClass());
                }
                break;
            case UDT:
                if (v instanceof UDTValue) {
                    st.setUDTValue(n, (UDTValue) v);
                } else {
                    st.set(n, v, (Class<Object>) v.getClass());
                }
                break;
            case LIST:
                if (v instanceof java.util.List) {
                    st.setList(n, (java.util.List) v);
                } else {
                    st.set(n, v, (Class<Object>) v.getClass());
                }
                break;
            case MAP:
                if (v instanceof java.util.Map) {
                    st.setMap(n, (java.util.Map) v);
                } else {
                    st.set(n, v, (Class<Object>) v.getClass());
                }
                break;
            case SET:
                if (v instanceof java.util.Set) {
                    st.setSet(n, (java.util.Set) v);
                } else {
                    st.set(n, v, (Class<Object>) v.getClass());
                }
                break;
            default:
                SupportLogger.LOGGER.unsupportedDataType(cqlType.name());
                st.set(n, v, (Class<Object>) v.getClass());
        }
    }
}
