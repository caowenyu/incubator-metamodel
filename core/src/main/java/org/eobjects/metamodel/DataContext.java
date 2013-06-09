/**
 * eobjects.org MetaModel
 * Copyright (C) 2010 eobjects.org
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, write to:
 * Free Software Foundation, Inc.
 * 51 Franklin Street, Fifth Floor
 * Boston, MA  02110-1301  USA
 */
package org.eobjects.metamodel;

import org.eobjects.metamodel.data.DataSet;
import org.eobjects.metamodel.query.CompiledQuery;
import org.eobjects.metamodel.query.Query;
import org.eobjects.metamodel.query.QueryParameter;
import org.eobjects.metamodel.query.builder.InitFromBuilder;
import org.eobjects.metamodel.schema.Column;
import org.eobjects.metamodel.schema.Schema;
import org.eobjects.metamodel.schema.Table;

/**
 * A DataContext represents the central entry point for interactions with
 * datastores. The DataContext contains of the structure of data (in the form of
 * schemas) and interactions (in the form of queries) with data.
 * 
 * @author Kasper Sørensen
 */
public interface DataContext {

    /**
     * Enforces a refresh of the schemas. If not refreshed, cached schema
     * objects may be used.
     * 
     * @return this DataContext
     */
    public DataContext refreshSchemas();

    /**
     * Gets all schemas within this DataContext.
     * 
     * @return the schemas in this data context. Schemas are cached for reuse in
     *         many situations so if you want to update the schemas, use the
     *         refreshSchemas() method.
     * @throws MetaModelException
     *             if an error occurs retrieving the schema model
     */
    public Schema[] getSchemas() throws MetaModelException;

    /**
     * Gets the names of all schemas within this DataContext.
     * 
     * @return an array of valid schema names
     * @throws MetaModelException
     *             if an error occurs retrieving the schema model
     */
    public String[] getSchemaNames() throws MetaModelException;

    /**
     * Gets the default schema of this DataContext.
     * 
     * @return the schema that you are most probable to be interested in. The
     *         default schema is determined by finding the schema with most
     *         available of tables. In a lot of situations there will only be a
     *         single available schema and in that case this will of course be
     *         the schema returned.
     * @throws MetaModelException
     *             if an error occurs retrieving the schema model
     */
    public Schema getDefaultSchema() throws MetaModelException;

    /**
     * Gets a schema by a specified name.
     * 
     * @param name
     *            the name of the desired schema
     * @return the Schema with the specified name or null if no such schema
     *         exists
     * @throws MetaModelException
     *             if an error occurs retrieving the schema model
     */
    public Schema getSchemaByName(String name) throws MetaModelException;

    /**
     * Starts building a query using the query builder API. This way of building
     * queries is the preferred approach since it provides a more type-safe
     * approach to building API's as well as allows the DataContext
     * implementation to be aware of the query building process.
     * 
     * @return a query builder component at the initial position in building a
     *         query.
     */
    public InitFromBuilder query();

    /**
     * Parses a string-based SQL query and produces a corresponding
     * {@link Query} object.
     * 
     * @param queryString
     *            the SQL query to parse
     * @return a {@link Query} object corresponding to the SQL query.
     * @throws MetaModelException
     *             in case the parsing was unsuccesful.
     */
    public Query parseQuery(String queryString) throws MetaModelException;

    /**
     * Executes a query against the DataContext.
     * 
     * @param query
     *            the query object to execute
     * @return the {@link DataSet} produced from executing the query
     * @throws MetaModelException
     *             if the specified query does not make sense or cannot be
     *             executed because of restraints on the type of datastore.
     */
    public DataSet executeQuery(Query query) throws MetaModelException;

    /**
     * Compiles a query, preparing it for reuse. Often times compiled queries
     * have a performance improvement when executed, but at the cost of a
     * preparation time penalty. Therefore it is adviced to use compiled queries
     * when the same query is to be fired multiple times.
     * 
     * Compiled queries can contain {@link QueryParameter}s as operands in the
     * WHERE clause, making it possible to reuse the same query with different
     * parameter values.
     * 
     * @see CompiledQuery
     * @see QueryParameter
     * 
     * @param query
     *            the query object to execute, possibly holding one or more
     *            {@link QueryParameter}s.
     * @return the {@link CompiledQuery} after preparing the query
     * 
     * @throws MetaModelException
     *             if preparing the query is unsuccesful
     */
    public CompiledQuery compileQuery(Query query) throws MetaModelException;

    /**
     * Executes a compiled query with given values as parameters.
     * 
     * @param compiledQuery
     *            the compiledQuery object to execute
     * @param values
     *            the values for parameters in the {@link CompiledQuery}.
     * @return the {@link DataSet} produced from executing the query.
     */
    public DataSet executeQuery(CompiledQuery compiledQuery, Object... values);

    /**
     * Parses and executes a string-based SQL query.
     * 
     * This method is essentially equivalent to calling first
     * {@link #parseQuery(String)} and then {@link #executeQuery(Query)} with
     * the parsed query.
     * 
     * @param query
     *            the SQL query to parse
     * @return the {@link DataSet} produced from executing the query
     * @throws MetaModelException
     *             if either parsing or executing the query produces an
     *             exception
     */
    public DataSet executeQuery(String queryString) throws MetaModelException;

    /**
     * Finds a column in the DataContext based on a fully qualified column
     * label. The qualified label consists of the the schema, table and column
     * name, delimited by a dot (.).
     * 
     * @param columnName
     * @return a column that matches the qualified label, or null if no such
     *         column exists
     */
    public Column getColumnByQualifiedLabel(final String columnName);

    /**
     * Finds a table in the DataContext based on a fully qualified table label.
     * The qualified label consists of the the schema and table name, delimited
     * by a dot (.).
     * 
     * @param tableName
     * @return a table that matches the qualified label, or null if no such
     *         table exists
     */
    public Table getTableByQualifiedLabel(final String tableName);

}