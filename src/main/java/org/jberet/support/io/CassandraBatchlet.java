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

import jakarta.batch.api.Batchlet;
import javax.enterprise.context.Dependent;
import jakarta.inject.Named;

import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import org.jberet.support._private.SupportLogger;

/**
 * A batchlet that executes one cql statement, or multiple cql statements grouped into one
 * cql batch.
 *
 * @see CassandraItemReader
 * @see CassandraItemWriter
 * @see CassandraReaderWriterBase
 *
 * @since 1.3.0
 */
@Named
@Dependent
public class CassandraBatchlet extends CassandraReaderWriterBase implements Batchlet {
    /**
     * {@inheritDoc}
     * <p>
     * This method executes the cql statement(s) as specified in {@link #cql} batch property,
     * and returns the string representation of the first row in the result set.
     * For certain mutation cql statements (e.g., update, insert, delete),
     * the underlying driver does not return any row, and so the return value of this method
     * will also be {@code null}.
     */
    @Override
    public String process() throws Exception {
        String result = null;
        try {
            initSession();
            final ResultSet resultSet = session.execute(cql);
            final Row one = resultSet.one();
            if (one != null) {
                result = one.toString();
            }
        } finally {
            try {
                close();
            } catch (Exception e) {
                SupportLogger.LOGGER.failToClose(e, session == null ? null : session.toString());
            }
        }
        return result;
    }

    /**
     * {@inheritDoc}
     * <p>
     * This method does nothing.
     */
    @Override
    public void stop() throws Exception {
    }
}
