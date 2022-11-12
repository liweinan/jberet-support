/*
 * Copyright (c) 2016-2017 Red Hat, Inc. and/or its affiliates.
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
import jakarta.batch.api.BatchProperty;
import jakarta.batch.api.chunk.ItemWriter;
import javax.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * An implementation of {@code jakarta.batch.api.chunk.ItemWriter} that writes
 * data items with Java Persistence API (JPA).
 *
 * @see JpaItemReader
 * @since 1.3.0
 */
@Named
@Dependent
public class JpaItemWriter extends JpaItemReaderWriterBase implements ItemWriter {
    /**
     * Flag to control whether to begin entity transaction before writing items,
     * and to commit entity transaction after writing items.
     * Optional property, and defaults to {@code false}.
     */
    @Inject
    @BatchProperty
    protected boolean entityTransaction;

    /**
     * {@inheritDoc}
     */
    public void open(final Serializable checkpoint) throws Exception {
    }

    /**
     * {@inheritDoc}
     */
    public void close() throws Exception {
    }

    /**
     * {@inheritDoc}
     * <p>
     * In this method, the entity manager persists the {@code items}.
     * If {@link #entityTransaction} is true, this method explicitly
     * begins entity transaction before writing, and commit it after
     * writing.
     *
     * @param items items to write
     * @throws Exception upon errors
     */
    @Override
    public void writeItems(final List<Object> items) throws Exception {
        if (entityTransaction) {
            em.getTransaction().begin();
        }
        for (final Object e : items) {
            em.persist(e);
        }

        if (entityTransaction) {
            em.getTransaction().commit();
        }
    }

    /**
     * Returns the current checkpoint data for this writer.
     * It is called before a chunk checkpoint is committed.
     *
     * @return null
     * @throws Exception upon errors
     */
    public Serializable checkpointInfo() throws Exception {
        return null;
    }

}
