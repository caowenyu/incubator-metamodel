package org.apache.metamodel;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Arrays;

import junit.framework.TestCase;

import org.apache.metamodel.data.DataSet;
import org.apache.metamodel.jdbc.JdbcDataContext;
import org.apache.metamodel.jdbc.dialects.IQueryRewriter;
import org.apache.metamodel.jdbc.dialects.SQLServerQueryRewriter;
import org.apache.metamodel.query.Query;
import org.apache.metamodel.query.SelectItem;
import org.apache.metamodel.schema.Schema;
import org.apache.metamodel.schema.Table;
import org.apache.metamodel.schema.TableType;

/**
 * Test case that tests MS SQL Server interaction. The test uses the
 * "AdventureWorks" sample database which can be downloaded from codeplex.
 * 
 * This testcase uses the official MS SQL Server driver.
 * 
 * @link{http://www.codeplex.com/MSFTDBProdSamples
 * */
public class SQLServerMicrosoftDriverTest extends TestCase {

	private Connection _connection;
	private String _databaseName = "AdventureWorks";

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
		_connection = DriverManager.getConnection("jdbc:sqlserver://localhost\\SQLEXPRESS;databaseName="
				+ _databaseName, "eobjects", "eobjects");
		_connection.setReadOnly(true);

	}

	@Override
	protected void tearDown() throws Exception {
		super.tearDown();
		_connection.close();
	}

	public void testQueryUsingExpressions() throws Exception {
		JdbcDataContext strategy = new JdbcDataContext(_connection,
				new TableType[] { TableType.TABLE, TableType.VIEW }, _databaseName);
		Query q = new Query().select("Name").from("Production.Product").where("COlor IS NOT NULL").setMaxRows(5);
		DataSet dataSet = strategy.executeQuery(q);
		assertEquals("[Name]", Arrays.toString(dataSet.getSelectItems()));
		assertTrue(dataSet.next());
		assertEquals("Row[values=[LL Crankarm]]", dataSet.getRow().toString());
		assertTrue(dataSet.next());
		assertTrue(dataSet.next());
		assertTrue(dataSet.next());
		assertTrue(dataSet.next());
		assertFalse(dataSet.next());
	}

	public void testGetSchemaNormalTableTypes() throws Exception {
		JdbcDataContext dc = new JdbcDataContext(_connection, new TableType[] { TableType.TABLE, TableType.VIEW },
				_databaseName);
		Schema[] schemas = dc.getSchemas();

		assertEquals(8, schemas.length);
		assertEquals("Schema[name=HumanResources]", schemas[0].toString());
		assertEquals(13, schemas[0].getTableCount());
		assertEquals("Schema[name=INFORMATION_SCHEMA]", schemas[1].toString());
		assertEquals(20, schemas[1].getTableCount());
		assertEquals("Schema[name=Person]", schemas[2].toString());
		assertEquals(8, schemas[2].getTableCount());
		assertEquals("Schema[name=Production]", schemas[3].toString());
		assertEquals(28, schemas[3].getTableCount());
		assertEquals("Schema[name=Purchasing]", schemas[4].toString());
		assertEquals(8, schemas[4].getTableCount());
		assertEquals("Schema[name=Sales]", schemas[5].toString());
		assertEquals(27, schemas[5].getTableCount());

	}

	public void testGetSchemaAllTableTypes() throws Exception {
		JdbcDataContext strategy = new JdbcDataContext(_connection, new TableType[] { TableType.OTHER,
				TableType.GLOBAL_TEMPORARY }, _databaseName);

		assertEquals("[Sales, HumanResources, dbo, Purchasing, sys, Production, INFORMATION_SCHEMA, Person]",
				Arrays.toString(strategy.getSchemaNames()));

		assertEquals("Schema[name=dbo]", strategy.getDefaultSchema().toString());
	}

	public void testQueryRewriterQuoteAliases() throws Exception {
		JdbcDataContext strategy = new JdbcDataContext(_connection, TableType.DEFAULT_TABLE_TYPES, _databaseName);
		IQueryRewriter queryRewriter = strategy.getQueryRewriter();
		assertSame(SQLServerQueryRewriter.class, queryRewriter.getClass());

		Schema schema = strategy.getSchemaByName("Sales");
		Table customersTable = schema.getTableByName("CUSTOMER");

		Query q = new Query().from(customersTable, "cus-tomers").select(
				new SelectItem(customersTable.getColumnByName("AccountNumber")).setAlias("c|o|d|e"));
		q.setMaxRows(5);

		assertEquals("SELECT cus-tomers.\"AccountNumber\" AS c|o|d|e FROM Sales.\"Customer\" cus-tomers", q.toString());

		String queryString = queryRewriter.rewriteQuery(q);
		assertEquals(
				"SELECT TOP 5 \"cus-tomers\".\"AccountNumber\" AS \"c|o|d|e\" FROM Sales.\"Customer\" \"cus-tomers\"",
				queryString);

		// We have to test that no additional quoting characters are added every
		// time we run the rewriting
		queryString = queryRewriter.rewriteQuery(q);
		queryString = queryRewriter.rewriteQuery(q);
		assertEquals(
				"SELECT TOP 5 \"cus-tomers\".\"AccountNumber\" AS \"c|o|d|e\" FROM Sales.\"Customer\" \"cus-tomers\"",
				queryString);

		// Test that the original query is still the same (ie. it has been
		// cloned for execution)
		assertEquals("SELECT cus-tomers.\"AccountNumber\" AS c|o|d|e FROM Sales.\"Customer\" cus-tomers", q.toString());

		DataSet data = strategy.executeQuery(q);
		assertNotNull(data);
		data.close();
	}

	public void testQuotedString() throws Exception {
		JdbcDataContext dc = new JdbcDataContext(_connection, TableType.DEFAULT_TABLE_TYPES, _databaseName);
		IQueryRewriter queryRewriter = dc.getQueryRewriter();
		assertSame(SQLServerQueryRewriter.class, queryRewriter.getClass());

		Query q = dc.query().from("Production", "Product").select("Name").where("Color").eq("R'ed").toQuery();

		assertEquals(
				"SELECT \"Product\".\"Name\" FROM Production.\"Product\" Product WHERE Product.\"Color\" = 'R''ed'",
				queryRewriter.rewriteQuery(q));
	}
}