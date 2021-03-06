package com.ibm.si.jaql.jdbc4;

import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import java.sql.ResultSetMetaData;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.ibm.si.jaql.Driver;
import com.ibm.si.jaql.api.ArielException;
import com.ibm.si.jaql.api.IArielDatabase;
import com.ibm.si.jaql.api.pojo.ArielResult;
import com.ibm.si.jaql.api.pojo.ColumnTuple;
import com.ibm.si.jaql.api.pojo.ParsedColumn;
import com.ibm.si.jaql.jdbc.ArielDatabaseMetaData;
import com.ibm.si.jaql.jdbc.ArielResultSet;
import com.ibm.si.jaql.jdbc.JdbcConnection;
import com.ibm.si.jaql.jdbc.PreparedJdbcStatement;
import com.ibm.si.jaql.util.SparkAQL;
/**
 * Ariel JDBC connection handler
 * @author IBM
 *
 */
public class Jdbc4Connection extends JdbcConnection
{
	static final Logger logger = LogManager.getLogger();
	
	private IQueryExecutor queryExecutor;
	private boolean sparkMode = false;
	public Jdbc4Connection (String url, Properties info) throws SQLException 
	{
		queryExecutor = new QueryExecutor(url, info);
		if (info.containsKey(Driver.SPARK_MODE))
			sparkMode = Boolean.parseBoolean((String) info.get(Driver.SPARK_MODE));
		logger.debug("Spark SQL=>AQL rewriting " + sparkMode);
	}
	
    public Jdbc4Statement createStatement() throws SQLException
    {
        return new Jdbc4Statement( this ) ;
    }
    
    @Override
    public PreparedStatement prepareStatement(final String sql) throws SQLException
    {
    	final PreparedJdbcStatement stmt = new PreparedJdbcStatement(this);
    	stmt.setPreparedSql(sql);
    	return stmt;
    }
    
    @Override
	public PreparedStatement prepareStatement(String sql, int resultSetType,
			int resultSetConcurrency) throws SQLException {
		return prepareStatement(sql);
	}

	public ResultSet executeQuery( final String query, Map<String, Object> parameters ) throws SQLException
    {
        logger.info("Jdbc4Connection>>>executeQuery(): before query={}",query);
        final String newQuery;
        if (sparkMode)
          newQuery = SparkAQL.sparkQueryUnwrapper(query);
        else
          newQuery = query;
        try
        {
          logger.info("Jdbc4Connection>>>executeQuery(): after query={}",newQuery);
        	final ArielResult result = queryExecutor.executeQuery(newQuery, parameters);
          ResultSet rs = toResultSet(result);
        	return rs ;
        }
        catch ( SQLException e )
        {
        	logger.error("Connection SQL Exception:",e);
            throw e;
        }
        catch ( Exception e )
        {
        	logger.error("Connection Exception:",e);
            throw new SQLException( "Error executing query " + newQuery + "\n with params " + parameters, e );
        }
    }
    
    private ResultSet toResultSet (ArielResult rawRes) throws SQLException
	{
    	//some examples of AQL statements :
    	//select x,y,x from events order by y
    	//select * from events order by y
    	//select eventcount from events
    	//select STR(sourceIp) from events
    	//select STR(sourceIp), 1+1 from events
    	//select STR(sourceIp), 1+1 as alias1 from events
    	//select STR(sourceIp), 1+1 as alias1, (STRLEN(destinationIp) + 1) from events
      // CHANGED 2016-07-26 No longer looking at the parsing the query. Fails for queries like `SELECT *,foo FROM events` will return only the column `foo`
      logger.trace("Raw ArielResult: {}", rawRes);
      List<ParsedColumn> columns = rawRes.getFieldList();
    	
    	final List<LinkedHashMap<String,ColumnTuple>> results = rawRes.getResults();
    	final List<Map<String,ColumnTuple>> orderedResults = new ArrayList<Map<String,ColumnTuple>>();
    	
    	//for all result rows, we need to parse each column to get data and metadata
    	for (LinkedHashMap<String,ColumnTuple> single : results)
    	{
    		final LinkedHashMap<String,ColumnTuple> orderedMap = new LinkedHashMap<String,ColumnTuple>();
    		for (final ParsedColumn column : columns)
    		{
    			//get the column we should lookup for metadata
    			//for standard fields (sourceIp, destinationIp) or standard simple use of functions (STRLEN(sourceIP)) we can use the columname
    			//else we have an aliased field/function, or an arithemtic expression, we need to rely on alias-ed ordering to extract the mapped column
          logger.trace("Parsed column: {}", column);
    			String columnName = null;
    			if (column.alias != null && (!column.alias.equalsIgnoreCase("")) )
    			{
    				columnName = column.alias;
    			}
    			else
    			{
    				columnName = column.name;
    			}
    			
    			//try first to get the column metatdata by name
    			ColumnTuple tuple = single.get(columnName);
    			
    			//if it fails, we rely on ordering enforced by LinkedHashmaps to get the correct data and metadata on a column 
    			if (tuple == null)
    			{
    				logger.debug(String.format("Failed initially to map field %s by name", columnName));
    				if (column.alias != null && (!column.alias.equalsIgnoreCase(""))
    						|| (column.isArthimeticExpression))
    				{
	    				logger.debug(String.format("Now trying to map field %s by index: %s", columnName, column.alias));
	    				List<ColumnTuple> columnTupleList = new ArrayList<ColumnTuple>( single.values() );
	    				
	    				
	    				tuple = columnTupleList.get(Integer.parseInt(column.alias));
	    				logger.debug(String.format("Tuple found: name::%s, type::%s, value::%s", tuple.getName(), tuple.getType(), tuple.getValue()));
	    				columnName = tuple.getName();
    				}
    			}
    			
				logger.trace(String.format("*** Adding meta data for %s as %s", columnName, tuple.getType() ));
				orderedMap.put(columnName, tuple); // Really rather this ordering occurred at the parsing stage
			
    		}
    		orderedResults.add(orderedMap);
    	}
    	
    	ArielResultSet arielRS = new ArielResultSet( this, orderedResults, rawRes); 
		return arielRS;
	}
    
	@Override
	public DatabaseMetaData getMetaData() throws SQLException
	{
		try
		{
			return new ArielDatabaseMetaData(this.queryExecutor.getDatabase());
		}
		catch (ArielException ae)
		{
			throw new SQLException(ae);
		}
	}
	
	public IArielDatabase getArielDatabase()
	{
		return this.queryExecutor.getDatabase();
	}
}
