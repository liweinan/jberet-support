/*
 * Copyright (c) 2014 Red Hat, Inc. and/or its affiliates.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.jberet.support.io;

import jakarta.batch.api.BatchProperty;
import jakarta.inject.Inject;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

/**
 * The base class of Excel reader and writer classes: {@link ExcelUserModelItemReader},
 * {@link ExcelUserModelItemWriter}, {@link ExcelStreamingItemWriter}.
 *
 * @see     ExcelUserModelItemReader
 * @see     ExcelStreamingItemReader
 * @see     ExcelEventItemReader
 * @see     ExcelUserModelItemWriter
 * @see     ExcelStreamingItemWriter
 * @since   1.1.0
 */
public abstract class ExcelItemReaderWriterBase extends JsonItemReaderWriterBase {

    @Inject
    @BatchProperty
    protected Class beanType;

    /**
     * Specifies the header as an ordered string array. For reader, header information must be specified with either
     * this property or {@link ExcelUserModelItemReader#headerRow} property. This property is typically specified
     * when there is no header row in the Excel file. For example,
     * <p>
     * "id, name, age" specifies 1st column is id, 2nd column is name and 3rd column is age.
     * <p>
     * This is a required property for writer.
     */
    @Inject
    @BatchProperty
    protected String[] header;

    /**
     * The optional name of the target sheet. When specified for a reader, it has higher precedence over
     * {@link org.jberet.support.io.ExcelUserModelItemReader#sheetIndex}
     */
    @Inject
    @BatchProperty
    protected String sheetName;

    protected Workbook workbook;
    protected Sheet sheet;
    protected int currentRowNum;

    /**
     * Saves string values to a string array for all non-blank cells in the row passed in. Useful when trying to get
     * header values.
     *
     * @param row the source row to get values from
     * @return a String array containing values from all non-blank cells in the row
     */
    protected static String[] getCellStringValues(final Row row) {
        final short firstCellNum = row.getFirstCellNum();
        final short lastCellNum = row.getLastCellNum();
        final String[] values = new String[lastCellNum - firstCellNum];
        for (int i = 0; i < values.length; ++i) {
            values[i] = row.getCell(i).getStringCellValue();
        }
        return values;
    }
}
