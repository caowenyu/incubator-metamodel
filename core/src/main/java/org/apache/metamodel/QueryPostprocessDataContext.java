/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.metamodel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.metamodel.convert.ConvertedDataSetInterceptor;
import org.apache.metamodel.convert.Converters;
import org.apache.metamodel.convert.HasReadTypeConverters;
import org.apache.metamodel.convert.TypeConverter;
import org.apache.metamodel.data.DataSet;
import org.apache.metamodel.data.DataSetHeader;
import org.apache.metamodel.data.DefaultRow;
import org.apache.metamodel.data.EmptyDataSet;
import org.apache.metamodel.data.FirstRowDataSet;
import org.apache.metamodel.data.InMemoryDataSet;
import org.apache.metamodel.data.MaxRowsDataSet;
import org.apache.metamodel.data.Row;
import org.apache.metamodel.data.SimpleDataSetHeader;
import org.apache.metamodel.query.FilterItem;
import org.apache.metamodel.query.FromItem;
import org.apache.metamodel.query.GroupByItem;
import org.apache.metamodel.query.JoinType;
import org.apache.metamodel.query.OperatorType;
import org.apache.metamodel.query.OrderByItem;
import org.apache.metamodel.query.Query;
import org.apache.metamodel.query.SelectItem;
import org.apache.metamodel.schema.Column;
import org.apache.metamodel.schema.ColumnType;
import org.apache.metamodel.schema.MutableColumn;
import org.apache.metamodel.schema.MutableRelationship;
import org.apache.metamodel.schema.MutableSchema;
import org.apache.metamodel.schema.MutableTable;
import org.apache.metamodel.schema.Relationship;
import org.apache.metamodel.schema.Schema;
import org.apache.metamodel.schema.Table;
import org.apache.metamodel.schema.TableType;
import org.apache.metamodel.util.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract DataContext for data sources that do not support SQL queries
 * natively.
 * 
 * Instead this superclass only requires that a subclass can materialize a
 * single table at a time. Then the query will be executed by post processing
 * the datasets client-side.
 */
public abstract class QueryPostprocessDataContext extends AbstractDataContext implements HasReadTypeConverters {

    private static final Logger logger = LoggerFactory.getLogger(QueryPostprocessDataContext.class);

    public static final String INFORMATION_SCHEMA_NAME = "information_schema";

    private final Map<Column, TypeConverter<?, ?>> _converters;

    private Schema _mainSchema;

    public QueryPostprocessDataContext() {
        super();
        _converters = new HashMap<Column, TypeConverter<?, ?>>();
    }

    @Override
    public DataSet executeQuery(final Query query) {
        final List<SelectItem> selectItems = query.getSelectClause().getItems();
        final List<FromItem> fromItems = query.getFromClause().getItems();
        final List<FilterItem> whereItems = query.getWhereClause().getItems();
        final List<SelectItem> whereSelectItems = query.getWhereClause().getEvaluatedSelectItems();
        final List<GroupByItem> groupByItems = query.getGroupByClause().getItems();
        final List<SelectItem> groupBySelectItems = query.getGroupByClause().getEvaluatedSelectItems();
        final List<SelectItem> havingSelectItems = query.getHavingClause().getEvaluatedSelectItems();
        final List<SelectItem> orderBySelectItems = query.getOrderByClause().getEvaluatedSelectItems();

        final List<FilterItem> havingItems = query.getHavingClause().getItems();
        final List<OrderByItem> orderByItems = query.getOrderByClause().getItems();

        // check for approximate SELECT COUNT(*) queries
        if (fromItems.size() == 1 && selectItems.size() == 1 && groupByItems.isEmpty() && havingItems.isEmpty()) {
            final SelectItem selectItem = query.getSelectClause().getItem(0);
            if (SelectItem.isCountAllItem(selectItem)) {
                final boolean functionApproximationAllowed = selectItem.isFunctionApproximationAllowed();
                final FromItem fromItem = query.getFromClause().getItem(0);
                final Table table = fromItem.getTable();
                if (table != null) {
                    if (isMainSchemaTable(table)) {
                        logger.debug("Query is a COUNT query with {} where items. Trying executeCountQuery(...)",
                                whereItems.size());
                        final Number count = executeCountQuery(table, whereItems, functionApproximationAllowed);
                        if (count == null) {
                            logger.debug("DataContext did not return any count query results. Proceeding with manual counting.");
                        } else {
                            List<Row> data = new ArrayList<Row>(1);
                            final DataSetHeader header = new SimpleDataSetHeader(new SelectItem[] { selectItem });
                            data.add(new DefaultRow(header, new Object[] { count }));
                            return new InMemoryDataSet(header, data);
                        }
                    }
                }
            }
        }

        final int firstRow = (query.getFirstRow() == null ? 1 : query.getFirstRow());
        final int maxRows = (query.getMaxRows() == null ? -1 : query.getMaxRows());

        // Check for very simple queries with max rows property set (typically
        // preview), see Ticket #187
        previewTable: if (whereItems.isEmpty() && groupByItems.isEmpty() && havingItems.isEmpty()
                && orderByItems.isEmpty() && fromItems.size() == 1) {

            final Table table = fromItems.get(0).getTable();
            if (table != null) {
                for (SelectItem item : selectItems) {
                    if (item.getFunction() != null || item.getExpression() != null) {
                        break previewTable;
                    }
                }

                DataSet dataSet = materializeTable(table, selectItems, firstRow, maxRows);
                dataSet = MetaModelHelper.getSelection(selectItems, dataSet);
                return dataSet;
            }
        }

        // Creates a list for all select items that are needed to execute query
        // (some may only be used as part of a filter, but not shown in result)
        List<SelectItem> workSelectItems = CollectionUtils.concat(true, selectItems, whereSelectItems,
                groupBySelectItems, havingSelectItems, orderBySelectItems);

        // Materialize the tables in the from clause
        final DataSet[] fromDataSets = new DataSet[fromItems.size()];
        for (int i = 0; i < fromDataSets.length; i++) {
            FromItem fromItem = fromItems.get(i);
            fromDataSets[i] = materializeFromItem(fromItem, workSelectItems);
        }

        // Execute the query using the raw data
        DataSet dataSet = MetaModelHelper.getCarthesianProduct(fromDataSets, whereItems);

        // we can now exclude the select items imposed by the WHERE clause (and
        // should, to make the aggregation process faster)
        workSelectItems = CollectionUtils.concat(true, selectItems, groupBySelectItems, havingSelectItems,
                orderBySelectItems);

        if (groupByItems.size() > 0) {
            dataSet = MetaModelHelper.getGrouped(workSelectItems, dataSet, groupByItems);
        } else {
            dataSet = MetaModelHelper.getAggregated(workSelectItems, dataSet);
        }
        dataSet = MetaModelHelper.getFiltered(dataSet, havingItems);

        if (query.getSelectClause().isDistinct()) {
            dataSet = MetaModelHelper.getSelection(selectItems, dataSet);
            dataSet = MetaModelHelper.getDistinct(dataSet);
            dataSet = MetaModelHelper.getOrdered(dataSet, orderByItems);
        } else {
            dataSet = MetaModelHelper.getOrdered(dataSet, orderByItems);
            dataSet = MetaModelHelper.getSelection(selectItems, dataSet);
        }

        if (firstRow > 1) {
            dataSet = new FirstRowDataSet(dataSet, firstRow);
        }
        if (maxRows != -1) {
            dataSet = new MaxRowsDataSet(dataSet, maxRows);
        }
        return dataSet;
    }

    /**
     * Executes a simple count query, if possible. This method is provided to
     * allow subclasses to optimize count queries since they are quite common
     * and often a datastore can retrieve the count using some specialized means
     * which is much more performant than counting all records manually.
     * 
     * @param table
     *            the table on which the count is requested.
     * @param whereItems
     *            a (sometimes empty) list of WHERE items.
     * @param functionApproximationAllowed
     *            whether approximation is allowed or not.
     * @return the count of the particular table, or null if not available.
     */
    protected Number executeCountQuery(Table table, List<FilterItem> whereItems, boolean functionApproximationAllowed) {
        return null;
    }

    protected DataSet materializeFromItem(final FromItem fromItem, final List<SelectItem> selectItems) {
        DataSet dataSet;
        JoinType joinType = fromItem.getJoin();
        if (fromItem.getTable() != null) {
            // We need to materialize a single table
            final Table table = fromItem.getTable();
            final List<SelectItem> selectItemsToMaterialize = new ArrayList<SelectItem>();

            for (final SelectItem selectItem : selectItems) {
                final FromItem selectedFromItem = selectItem.getFromItem();
                if (selectedFromItem != null) {
                    if (selectedFromItem.equals(fromItem)) {
                        selectItemsToMaterialize.add(selectItem.replaceFunction(null));
                    }
                } else {
                    // the select item does not specify a specific
                    // from-item
                    final Column selectedColumn = selectItem.getColumn();
                    if (selectedColumn != null) {
                        // we assume that if the table matches, we will use the
                        // column
                        if (selectedColumn.getTable() != null && selectedColumn.getTable().equals(table)) {
                            selectItemsToMaterialize.add(selectItem.replaceFunction(null));
                        }
                    }
                }
            }

            if (logger.isDebugEnabled()) {
                logger.debug("calling materializeTable(" + table.getName() + "," + selectItemsToMaterialize + ",1,-1");
            }

            // Dispatching to the concrete subclass of
            // QueryPostprocessDataContextStrategy
            dataSet = materializeTable(table, selectItemsToMaterialize, 1, -1);

        } else if (joinType != null) {
            // We need to (recursively) materialize a joined FromItem
            if (fromItem.getLeftSide() == null || fromItem.getRightSide() == null) {
                throw new IllegalArgumentException("Joined FromItem requires both left and right side: " + fromItem);
            }
            DataSet[] fromItemDataSets = new DataSet[2];

            // materialize left side
            List<SelectItem> leftOn = Arrays.asList(fromItem.getLeftOn());
            fromItemDataSets[0] = materializeFromItem(fromItem.getLeftSide(),
                    CollectionUtils.concat(true, selectItems, leftOn));

            // materialize right side
            List<SelectItem> rightOn = Arrays.asList(fromItem.getRightOn());
            fromItemDataSets[1] = materializeFromItem(fromItem.getRightSide(),
                    CollectionUtils.concat(true, selectItems, rightOn));

            FilterItem[] onConditions = new FilterItem[leftOn.size()];
            for (int i = 0; i < onConditions.length; i++) {
                FilterItem whereItem = new FilterItem(leftOn.get(i), OperatorType.EQUALS_TO, rightOn.get(i));
                onConditions[i] = whereItem;
            }
            if (joinType == JoinType.INNER) {
                dataSet = MetaModelHelper.getCarthesianProduct(fromItemDataSets, onConditions);
            } else if (joinType == JoinType.LEFT) {
                dataSet = MetaModelHelper.getLeftJoin(fromItemDataSets[0], fromItemDataSets[1], onConditions);
            } else if (joinType == JoinType.RIGHT) {
                dataSet = MetaModelHelper.getRightJoin(fromItemDataSets[0], fromItemDataSets[1], onConditions);
            } else {
                throw new IllegalArgumentException("FromItem type not supported: " + fromItem);
            }
        } else if (fromItem.getSubQuery() != null) {
            // We need to (recursively) materialize a subquery
            dataSet = executeQuery(fromItem.getSubQuery());
        } else {
            throw new IllegalArgumentException("FromItem type not supported: " + fromItem);
        }
        if (dataSet == null) {
            throw new IllegalStateException("FromItem was not succesfully materialized: " + fromItem);
        }
        return dataSet;
    }

    protected DataSet materializeTable(final Table table, final List<SelectItem> selectItems, final int firstRow,
            final int maxRows) {
        if (table == null) {
            throw new IllegalArgumentException("Table cannot be null");
        }

        if (selectItems == null || selectItems.isEmpty()) {
            // add any column (typically this occurs because of COUNT(*)
            // queries)
            Column[] columns = table.getColumns();
            if (columns.length == 0) {
                logger.warn("Queried table has no columns: {}", table);
            } else {
                selectItems.add(new SelectItem(columns[0]));
            }
        }

        if (maxRows == 0) {
            return new EmptyDataSet(selectItems);
        }

        final Schema schema = table.getSchema();
        final String schemaName;
        if (schema == null) {
            schemaName = null;
        } else {
            schemaName = schema.getName();
        }

        final DataSet dataSet;
        if (INFORMATION_SCHEMA_NAME.equals(schemaName)) {
            final DataSet informationDataSet = materializeInformationSchemaTable(table, selectItems, maxRows);
            if (firstRow > 1) {
                dataSet = new FirstRowDataSet(informationDataSet, firstRow);
            } else {
                dataSet = informationDataSet;
            }
        } else {
            final DataSet tableDataSet = materializeMainSchemaTable(table, selectItems, firstRow, maxRows);

            // conversion is done at materialization time, since it enables
            // the refined types to be used also in eg. where clauses.
            dataSet = new ConvertedDataSetInterceptor(_converters).intercept(tableDataSet);
        }

        return dataSet;
    }

    protected boolean isMainSchemaTable(Table table) {
        Schema schema = table.getSchema();
        if (INFORMATION_SCHEMA_NAME.equals(schema.getName())) {
            return false;
        } else {
            return true;
        }
    }

    @Override
    protected final String[] getSchemaNamesInternal() throws MetaModelException {
        final String[] schemaNames = new String[2];
        schemaNames[0] = INFORMATION_SCHEMA_NAME;
        schemaNames[1] = getMainSchemaName();
        return schemaNames;
    }

    @Override
    protected String getDefaultSchemaName() throws MetaModelException {
        return getMainSchemaName();
    }

    @Override
    protected final Schema getSchemaByNameInternal(final String name) throws MetaModelException {
        final String mainSchemaName = getMainSchemaName();
        if (name == null) {
            if (mainSchemaName == null) {
                return getMainSchema();
            }
            return null;
        }

        if (name.equalsIgnoreCase(mainSchemaName)) {
            return getMainSchemaInternal();
        } else if (name.equals(INFORMATION_SCHEMA_NAME)) {
            return getInformationSchema();
        }

        logger.warn("Could not find matching schema of name '{}'. Main schema name is: '{}'. Returning null.", name,
                mainSchemaName);
        return null;
    }

    private Schema getInformationSchema() {
        // Create schema
        MutableSchema informationSchema = new MutableSchema(INFORMATION_SCHEMA_NAME);
        MutableTable tablesTable = new MutableTable("tables", TableType.TABLE, informationSchema);
        MutableTable columnsTable = new MutableTable("columns", TableType.TABLE, informationSchema);
        MutableTable relationshipsTable = new MutableTable("relationships", TableType.TABLE, informationSchema);
        informationSchema.addTable(tablesTable).addTable(columnsTable).addTable(relationshipsTable);

        // Create "tables" table: name, type, num_columns, remarks
        tablesTable.addColumn(new MutableColumn("name", ColumnType.VARCHAR, tablesTable, 0, false));
        tablesTable.addColumn(new MutableColumn("type", ColumnType.VARCHAR, tablesTable, 1, true));
        tablesTable.addColumn(new MutableColumn("num_columns", ColumnType.INTEGER, tablesTable, 2, true));
        tablesTable.addColumn(new MutableColumn("remarks", ColumnType.VARCHAR, tablesTable, 3, true));

        // Create "columns" table: name, type, native_type, size, nullable,
        // indexed, table, remarks
        columnsTable.addColumn(new MutableColumn("name", ColumnType.VARCHAR, columnsTable, 0, false));
        columnsTable.addColumn(new MutableColumn("type", ColumnType.VARCHAR, columnsTable, 1, true));
        columnsTable.addColumn(new MutableColumn("native_type", ColumnType.VARCHAR, columnsTable, 2, true));
        columnsTable.addColumn(new MutableColumn("size", ColumnType.INTEGER, columnsTable, 3, true));
        columnsTable.addColumn(new MutableColumn("nullable", ColumnType.BOOLEAN, columnsTable, 4, true));
        columnsTable.addColumn(new MutableColumn("indexed", ColumnType.BOOLEAN, columnsTable, 5, true));
        columnsTable.addColumn(new MutableColumn("table", ColumnType.VARCHAR, columnsTable, 6, false));
        columnsTable.addColumn(new MutableColumn("remarks", ColumnType.VARCHAR, columnsTable, 7, true));

        // Create "relationships" table: primary_table, primary_column,
        // foreign_table, foreign_column
        relationshipsTable.addColumn(new MutableColumn("primary_table", ColumnType.VARCHAR, relationshipsTable, 0,
                false));
        relationshipsTable.addColumn(new MutableColumn("primary_column", ColumnType.VARCHAR, relationshipsTable, 1,
                false));
        relationshipsTable.addColumn(new MutableColumn("foreign_table", ColumnType.VARCHAR, relationshipsTable, 2,
                false));
        relationshipsTable.addColumn(new MutableColumn("foreign_column", ColumnType.VARCHAR, relationshipsTable, 3,
                false));

        MutableRelationship.createRelationship(tablesTable.getColumnByName("name"),
                columnsTable.getColumnByName("table"));
        MutableRelationship.createRelationship(tablesTable.getColumnByName("name"),
                relationshipsTable.getColumnByName("primary_table"));
        MutableRelationship.createRelationship(tablesTable.getColumnByName("name"),
                relationshipsTable.getColumnByName("foreign_table"));
        MutableRelationship.createRelationship(columnsTable.getColumnByName("name"),
                relationshipsTable.getColumnByName("primary_column"));
        MutableRelationship.createRelationship(columnsTable.getColumnByName("name"),
                relationshipsTable.getColumnByName("foreign_column"));

        return informationSchema;
    }

    private DataSet materializeInformationSchemaTable(final Table table, final List<SelectItem> selectItems,
            final int maxRows) {
        final String tableName = table.getName();
        final SelectItem[] columnSelectItems = MetaModelHelper.createSelectItems(table.getColumns());
        final SimpleDataSetHeader header = new SimpleDataSetHeader(columnSelectItems);
        final Table[] tables = getMainSchemaInternal().getTables();
        final List<Row> data = new ArrayList<Row>();
        if ("tables".equals(tableName)) {
            // "tables" columns: name, type, num_columns, remarks
            for (Table t : tables) {
                String typeString = null;
                if (t.getType() != null) {
                    typeString = t.getType().toString();
                }
                data.add(new DefaultRow(header, new Object[] { t.getName(), typeString, t.getColumnCount(),
                        t.getRemarks() }));
            }
        } else if ("columns".equals(tableName)) {
            // "columns" columns: name, type, native_type, size, nullable,
            // indexed, table, remarks
            for (Table t : tables) {
                for (Column c : t.getColumns()) {
                    String typeString = null;
                    if (t.getType() != null) {
                        typeString = c.getType().toString();
                    }
                    data.add(new DefaultRow(header, new Object[] { c.getName(), typeString, c.getNativeType(),
                            c.getColumnSize(), c.isNullable(), c.isIndexed(), t.getName(), c.getRemarks() }));
                }
            }
        } else if ("relationships".equals(tableName)) {
            // "relationships" columns: primary_table, primary_column,
            // foreign_table, foreign_column
            for (Relationship r : getMainSchemaInternal().getRelationships()) {
                Column[] primaryColumns = r.getPrimaryColumns();
                Column[] foreignColumns = r.getForeignColumns();
                Table pTable = r.getPrimaryTable();
                Table fTable = r.getForeignTable();
                for (int i = 0; i < primaryColumns.length; i++) {
                    Column pColumn = primaryColumns[i];
                    Column fColumn = foreignColumns[i];
                    data.add(new DefaultRow(header, new Object[] { pTable.getName(), pColumn.getName(),
                            fTable.getName(), fColumn.getName() }));
                }
            }
        } else {
            throw new IllegalArgumentException("Cannot materialize non information_schema table: " + table);
        }

        DataSet dataSet;
        if (data.isEmpty()) {
            dataSet = new EmptyDataSet(selectItems);
        } else {
            dataSet = new InMemoryDataSet(header, data);
        }

        // Handle column subset
        dataSet = MetaModelHelper.getSelection(selectItems, dataSet);

        // Handle maxRows
        if (maxRows != -1) {
            dataSet = new MaxRowsDataSet(dataSet, maxRows);
        }
        return dataSet;
    }

    protected Schema getMainSchemaInternal() {
        Schema schema = _mainSchema;
        if (schema == null) {
            schema = getMainSchema();
            _mainSchema = schema;
        }
        return schema;
    }

    /**
     * Adds a {@link TypeConverter} to this DataContext's query engine (Query
     * Postprocessor) for read operations. Note that this method should NOT be
     * invoked directly by consuming code. Rather use
     * {@link Converters#addTypeConverter(DataContext, Column, TypeConverter)}
     * to ensure conversion on both reads and writes.
     */
    @Override
    public void addConverter(Column column, TypeConverter<?, ?> converter) {
        _converters.put(column, converter);
    }

    /**
     * @return the main schema that subclasses of this class produce
     */
    protected abstract Schema getMainSchema() throws MetaModelException;

    /**
     * @return the name of the main schema that subclasses of this class produce
     */
    protected abstract String getMainSchemaName() throws MetaModelException;

    /**
     * Executes a simple one-table query against a table in the main schema of
     * the subclasses of this class. This default implementation will delegate
     * to {@link #materializeMainSchemaTable(Table, Column[], int, int)}.
     * 
     * @param table
     * @param selectItems
     * @param firstRow
     * @param maxRows
     * @return
     */
    protected DataSet materializeMainSchemaTable(Table table, List<SelectItem> selectItems, int firstRow, int maxRows) {
        Column[] columns = new Column[selectItems.size()];
        for (int i = 0; i < columns.length; i++) {
            columns[i] = selectItems.get(i).getColumn();
        }
        DataSet dataSet = materializeMainSchemaTable(table, columns, firstRow, maxRows);

        dataSet = MetaModelHelper.getSelection(selectItems, dataSet);

        return dataSet;
    }

    /**
     * Executes a simple one-table query against a table in the main schema of
     * the subclasses of this class. This default implementation will delegate
     * to {@link #materializeMainSchemaTable(Table, Column[], int)} and apply a
     * {@link FirstRowDataSet} if necessary.
     * 
     * @param table
     * @param columns
     * @param firstRow
     * @param maxRows
     * @return
     */
    protected DataSet materializeMainSchemaTable(Table table, Column[] columns, int firstRow, int maxRows) {
        final int rowsToMaterialize;
        if (firstRow == 1) {
            rowsToMaterialize = maxRows;
        } else {
            rowsToMaterialize = maxRows + (firstRow - 1);
        }
        DataSet dataSet = materializeMainSchemaTable(table, columns, rowsToMaterialize);
        if (firstRow > 1) {
            dataSet = new FirstRowDataSet(dataSet, firstRow);
        }
        return dataSet;
    }

    /**
     * Executes a simple one-table query against a table in the main schema of
     * the subclasses of this class.
     * 
     * @param table
     *            the table to query
     * @param columns
     *            the columns of the table to query
     * @param maxRows
     *            the maximum amount of rows needed or -1 if all rows are
     *            wanted.
     * @return a dataset with the raw table/column content.
     */
    protected abstract DataSet materializeMainSchemaTable(Table table, Column[] columns, int maxRows);
}