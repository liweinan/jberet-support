/*
 * Copyright (c) 2017 Red Hat, Inc. and/or its affiliates.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.jberet.support.io;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import jakarta.batch.api.BatchProperty;
import jakarta.batch.api.Batchlet;
import javax.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import javax.naming.InitialContext;
import javax.sql.DataSource;

import org.jberet.support._private.SupportLogger;
import org.jberet.support._private.SupportMessages;

@Named
@Dependent
public class JdbcBatchlet implements Batchlet {
    /**
     * The sql statements to execute.
     */
    @Inject
    @BatchProperty
    protected String sqls;

    /**
     * JNDI lookup name of the {@code javax.sql.DataSource}. Optional property, and defaults to null. If specified,
     * it will be used to look up the target {@code DataSource}, and other database connection batch properties for
     * this writer class will be ignored.
     */
    @Inject
    @BatchProperty
    protected String dataSourceLookup;

    /**
     * JDBC connection url
     */
    @Inject
    @BatchProperty
    protected String url;

    /**
     * User name for the JDBC connection
     */
    @Inject
    @BatchProperty
    protected String user;

    /**
     * Password for the JDBC connection
     */
    @Inject
    @BatchProperty
    protected String password;

    /**
     * Additional properties for the JDBC connection
     */
    @Inject
    @BatchProperty
    protected Map<String, String> properties;

    /**
     * {@inheritDoc}
     */
    @Override
    public String process() throws Exception {
        Connection connection = null;
        Statement statement = null;
        String result;

        if (sqls == null || (sqls = sqls.trim()).length() == 0) {
            throw SupportMessages.MESSAGES.invalidReaderWriterProperty(null, sqls, "sqls");
        }

        try {
            if (dataSourceLookup != null) {
                DataSource dataSource = InitialContext.doLookup(dataSourceLookup);
                connection = dataSource.getConnection();
            } else {
                if (url == null) {
                    throw SupportMessages.MESSAGES.invalidReaderWriterProperty(null, null, "url");
                }
                Properties dbProperties = new Properties();
                if (properties != null) {
                    dbProperties.putAll(properties);
                }
                if (user != null) {
                    dbProperties.put("user", user.trim());
                }
                if (password != null) {
                    dbProperties.put("password", password.trim());
                }
                connection = DriverManager.getConnection(url, dbProperties);
            }

            final String[] sqlArray = sqls.split(";");
            statement = connection.createStatement();
            if (sqlArray.length == 0) {
                throw SupportMessages.MESSAGES.invalidReaderWriterProperty(null, sqls, "sqls");
            }
            if (sqlArray.length == 1) {
                SupportLogger.LOGGER.addingSql(sqlArray[0]);
                statement.execute(sqlArray[0]);
                final int updateCount = statement.getUpdateCount();
                result = String.valueOf(updateCount);
            } else {
                for (String sql : sqlArray) {
                    sql = sql.trim();
                    if(sql.length() > 0) {
                        SupportLogger.LOGGER.addingSql(sql);
                        statement.addBatch(sql);
                    }
                }
                int[] updateCounts;
                try {
                    updateCounts = statement.executeBatch();
                } catch (final SQLException sqlException) {
                    final SQLException cause = sqlException.getNextException();
                    for(SQLException nextException = sqlException.getNextException();
                        nextException != null; nextException = nextException.getNextException()) {
                        SupportLogger.LOGGER.error(nextException.toString());
                    }
                    if (sqlException instanceof BatchUpdateException) {
                        updateCounts = ((BatchUpdateException) sqlException).getUpdateCounts();
                        SupportLogger.LOGGER.jdbcBatchUpdateCounts(Arrays.toString(updateCounts));
                    }
                    throw cause != null ? cause : sqlException;
                }
                result = Arrays.toString(updateCounts);
            }
        } finally {
            if (statement != null) {
                try {
                    statement.close();
                } catch (Exception e) {
                    //ignore
                }
            }
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception e) {
                    //ignore
                }
            }
        }
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stop() throws Exception {
    }
}
