/*
 * Copyright (c) 2016 Red Hat, Inc. and/or its affiliates.
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

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.jberet.support._private.SupportMessages;

/**
 * An implementation of {@code ItemWriter} that sends data items to Kafka {@code TopicPartition} as specified in batch
 * property {@link #topicPartition}.
 *
 * @see KafkaItemReader
 * @see KafkaItemReaderWriterBase
 *
 * @since 1.3.0
 */
@Named
@Dependent
public class KafkaItemWriter extends KafkaItemReaderWriterBase implements ItemWriter {
    /**
     * A topic partition in the form of {@code <topicName>:<partitionNumber>}. For example, "orders:0".
     * Unlike {@link KafkaItemReader}, which accepts multiple {@code TopicPartition} as source, this writer class only
     * accepts 1 {@code TopicPartition} as destination.
     *
     * @see KafkaItemReaderWriterBase#topicPartitionDelimiter
     * @see "org.apache.kafka.common.TopicPartition"
     */
    @Inject
    @BatchProperty
    protected String topicPartition;

    /**
     * The key used when sending {@code ProducerRecord}.
     *
     * @see "org.apache.kafka.clients.producer.ProducerRecord"
     */
    @Inject
    @BatchProperty
    protected String recordKey;

    /**
     * The Kafka producer responsible for sending the records.
     */
    protected KafkaProducer producer;

    /**
     * The topic name extracted from {@link #topicPartition}. This field is used as the default destination topic name.
     * Subclass may override method {@link #getTopic(Object)} to provide the topic name differently.
     */
    private String topic;

    /**
     * The partition number extracted from {@link #topicPartition}. This field is used as the default destination
     * partition number. Subclass may override method {@link #getPartition(Object)} to provide the partition number
     * differently.
     */
    private Integer partition;

    /**
     * During the writer opening, the Kafka producer is instantiated, based on the configuration properties as specified
     * in the batch property {@link #configFile}.
     *
     * @param checkpoint item writer checkpoint data, currently not used
     * @throws Exception if error occurs
     */
    @Override
    public void open(final Serializable checkpoint) throws Exception {
        producer = new KafkaProducer(createConfigProperties());

        if (topicPartition == null) {
            throw SupportMessages.MESSAGES.invalidReaderWriterProperty(null, null, "topicPartition");
        }
        final int colonPos = topicPartition.lastIndexOf(topicPartitionDelimiter);
        if (colonPos > 0) {
            topic = topicPartition.substring(0, colonPos);
            partition = Integer.valueOf(topicPartition.substring(colonPos + 1));
        } else if (colonPos < 0) {
            topic = topicPartition;
        } else {
            throw SupportMessages.MESSAGES.invalidReaderWriterProperty(null, topicPartition, "topicPartition");
        }
    }

    /**
     * Creates Kafka {@code ProducerRecord} and sends it to Kafka topic partition for each item in data {@code items}.
     *
     * @param items data items to be sent to Kafka server
     *
     * @throws Exception if error occurs
     */
    @Override
    @SuppressWarnings("unchecked")
    public void writeItems(final List<Object> items) throws Exception {
        for (final Object item : items) {
            producer.send(new ProducerRecord(getTopic(item), getPartition(item), getRecordKey(item), item));
        }
    }

    /**
     * Returns null checkpoint info for this writer.
     *
     * @return null checkpoint info
     */
    @Override
    public Serializable checkpointInfo() {
        return null;
    }

    /**
     * Closes the Kafka producer.
     */
    @Override
    public void close() {
        if (producer != null) {
            producer.close();
            producer = null;
        }
    }

    /**
     * Gets the destination topic used when sending {@code ProducerRecord}.
     * Subclass may override this method to provide a suitable topic.
     * The default implementation returns a value based on the injected field {@link #topicPartition}.
     *
     * @param item the item currently being sent
     *
     * @return topic used for sending the current {@code ProducerRecord}
     */
    @SuppressWarnings("unused")
    protected String getTopic(final Object item) {
        return topic;
    }

    /**
     * Gets the destination topic partition used when sending {@code ProducerRecord}.
     * Subclass may override this method to provide a suitable topic partition number.
     * The default implementation returns a value based on the injected field {@link #topicPartition}.
     *
     * @param item the item currently being sent
     *
     * @return topic partition used for sending the current {@code ProducerRecord}
     */
    @SuppressWarnings("unused")
    protected Integer getPartition(final Object item) {
        return partition;
    }

    /**
     * Gets the key used when sending {@code ProducerRecord}.
     * Subclass may override this method to provide a suitable key.
     * The default implementation returns the injected value of field {@link #recordKey}.
     *
     * @param item the item currently being sent
     *
     * @return a key used for sending the current {@code ProducerRecord}
     */
    @SuppressWarnings("unused")
    protected String getRecordKey(final Object item) {
        return recordKey;
    }
}
