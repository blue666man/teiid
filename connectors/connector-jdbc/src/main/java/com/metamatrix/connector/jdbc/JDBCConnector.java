/*
 * JBoss, Home of Professional Open Source.
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */

/*
 */
package com.metamatrix.connector.jdbc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Enumeration;
import java.util.Properties;

import javax.sql.DataSource;
import javax.sql.XADataSource;

import com.metamatrix.common.util.PropertiesUtils;
import com.metamatrix.connector.DataPlugin;
import com.metamatrix.connector.api.Connection;
import com.metamatrix.connector.api.ConnectorCapabilities;
import com.metamatrix.connector.api.ConnectorEnvironment;
import com.metamatrix.connector.api.ConnectorException;
import com.metamatrix.connector.api.ConnectorLogger;
import com.metamatrix.connector.api.CredentialMap;
import com.metamatrix.connector.api.ExecutionContext;
import com.metamatrix.connector.api.ConnectorAnnotations.ConnectionPooling;
import com.metamatrix.connector.identity.ConnectorIdentity;
import com.metamatrix.connector.identity.ConnectorIdentityFactory;
import com.metamatrix.connector.identity.SingleIdentity;
import com.metamatrix.connector.identity.UserIdentity;
import com.metamatrix.connector.internal.ConnectorPropertyNames;
import com.metamatrix.connector.jdbc.translator.Translator;
import com.metamatrix.connector.jdbc.xa.JDBCSourceXAConnection;
import com.metamatrix.connector.jdbc.xa.XAJDBCPropertyNames;
import com.metamatrix.connector.xa.api.TransactionContext;
import com.metamatrix.connector.xa.api.XAConnection;
import com.metamatrix.connector.xa.api.XAConnector;
import com.metamatrix.core.MetaMatrixCoreException;
import com.metamatrix.core.util.ReflectionHelper;

/**
 * JDBC implementation of Connector interface.
 */
@ConnectionPooling
public class JDBCConnector implements XAConnector, ConnectorIdentityFactory {
	
    public static final String INVALID_AUTHORIZATION_SPECIFICATION_NO_SUBCLASS = "28000"; //$NON-NLS-1$

	static final int NO_ISOLATION_LEVEL_SET = Integer.MIN_VALUE;

	enum TransactionIsolationLevel {
        TRANSACTION_READ_UNCOMMITTED(java.sql.Connection.TRANSACTION_READ_UNCOMMITTED),
        TRANSACTION_READ_COMMITTED(java.sql.Connection.TRANSACTION_READ_COMMITTED),
        TRANSACTION_REPEATABLE_READ(java.sql.Connection.TRANSACTION_REPEATABLE_READ),
        TRANSACTION_SERIALIZABLE(java.sql.Connection.TRANSACTION_SERIALIZABLE),
        TRANSACTION_NONE(java.sql.Connection.TRANSACTION_NONE);

        private int connectionContant;

        private TransactionIsolationLevel(int connectionConstant) {
			this.connectionContant = connectionConstant;
		}
        
        public int getConnectionConstant() {
        	return connectionContant;
        }
	}
	
	protected ConnectorEnvironment environment;
    private ConnectorLogger logger;
    private ConnectorCapabilities capabilities;
    private Translator sqlTranslator;
    private DataSource ds;
    private XADataSource xaDs;
    private int transIsoLevel = NO_ISOLATION_LEVEL_SET;
    
	private boolean useCredentialMap;
	private boolean adminConnectionsAllowed = true;
	private String connectorName;
	
	@Override
	public ConnectorIdentity createIdentity(ExecutionContext context)
			throws ConnectorException {
		if (context == null) {
			if (adminConnectionsAllowed) {
				return new SingleIdentity();
			}
			throw new ConnectorException(DataPlugin.Util.getString("UserIdentityFactory.single_identity_not_supported")); //$NON-NLS-1$
		}
		Object payload = context.getTrustedPayload();
		if (!(payload instanceof CredentialMap)) {
			if (useCredentialMap) {
				throw new ConnectorException(JDBCPlugin.Util.getString("JDBCUserIdentityConnectionFactory.Unable_to_get_credentials")); //$NON-NLS-1$
			}
			return new SingleIdentity();
		}
		CredentialMap credMap = (CredentialMap)payload;
		String user = credMap.getUser(connectorName);
		String password = credMap.getPassword(connectorName);
		if (user == null || password == null) {
			throw new ConnectorException("Payload missing credentials for " + connectorName); //$NON-NLS-1$
		}
		return new UserIdentity(context.getUser(), user, password);
	}
	
	public void setConnectorName(String connectorName) {
		this.connectorName = connectorName;
	}
	
	public void setUseCredentialMap(boolean useCredentialMap) {
		this.useCredentialMap = useCredentialMap;
	}
	
	public void setAdminConnectionsAllowed(boolean adminConnectionsAllowed) {
		this.adminConnectionsAllowed = adminConnectionsAllowed;
	}
    
    @Override
    public void start(ConnectorEnvironment environment)
    		throws ConnectorException {
    	logger = environment.getLogger();
        this.environment = environment;
        
        logger.logInfo(JDBCPlugin.Util.getString("JDBCConnector.JDBCConnector_initialized._1")); //$NON-NLS-1$
        
        capabilities = createCapabilities(environment, Thread.currentThread().getContextClassLoader());

        Properties connectionProps = environment.getProperties();

        // Get the JDBC properties ...
        String dataSourceClassName = connectionProps.getProperty(JDBCPropertyNames.CONNECTION_SOURCE_CLASS);
        
        // Verify required items
        if (dataSourceClassName == null || dataSourceClassName.trim().length() == 0) {
            throw new ConnectorException(JDBCPlugin.Util.getString("JDBCSourceConnectionFactory.Missing_JDBC_driver_class_name_1")); //$NON-NLS-1$
        }
        dataSourceClassName = dataSourceClassName.trim();

        String levelStr = connectionProps.getProperty(JDBCPropertyNames.TRANSACTION_ISOLATION_LEVEL);
        
        if(levelStr != null && levelStr.trim().length() != 0){
        	transIsoLevel = TransactionIsolationLevel.valueOf(levelStr.toUpperCase()).getConnectionConstant();
        }
        
        try {
            String className = environment.getProperties().getProperty(JDBCPropertyNames.EXT_TRANSLATOR_CLASS, Translator.class.getName());  
            this.sqlTranslator = (Translator)ReflectionHelper.create(className, null, Thread.currentThread().getContextClassLoader());
        } catch (MetaMatrixCoreException e) {
            throw new ConnectorException(e);
        }
        sqlTranslator.initialize(environment);
        
        if (this.adminConnectionsAllowed) {
        	testConnection();
        }

        logger.logInfo(JDBCPlugin.Util.getString("JDBCConnector.JDBCConnector_started._4")); //$NON-NLS-1$
    }
    
    private void testConnection() throws ConnectorException {
    	Connection connection = null;
        try {
            connection = getConnection(null);
        } catch (ConnectorException e) {
            SQLException ex = (SQLException)e.getCause();
            String sqlState = ex.getSQLState();
            if (sqlState != null && INVALID_AUTHORIZATION_SPECIFICATION_NO_SUBCLASS.equals(sqlState)) {
                throw e;
            }
            this.logger.logError(e.getMessage(), e);
        } finally {
        	if (connection != null) {
        		connection.close();
        	}
        }
    }
    
	@Override
    public void stop() {     
		/*
		 * attempt to deregister drivers that may have been implicitly registered
		 * with the driver manager
		 */
        Enumeration drivers = DriverManager.getDrivers();

        String driverClassname = this.environment.getProperties().getProperty(JDBCPropertyNames.CONNECTION_SOURCE_CLASS);
        boolean usingCustomClassLoader = PropertiesUtils.getBooleanProperty(this.environment.getProperties(), ConnectorPropertyNames.USING_CUSTOM_CLASSLOADER, false);

        while(drivers.hasMoreElements()){
        	Driver tempdriver = (Driver)drivers.nextElement();
            if(tempdriver.getClass().getClassLoader() != this.getClass().getClassLoader()) {
            	continue;
            }
            if(usingCustomClassLoader || tempdriver.getClass().getName().equals(driverClassname)) {
                try {
                    DriverManager.deregisterDriver(tempdriver);
                } catch (Throwable e) {
                    this.environment.getLogger().logError(e.getMessage());
                }
            }
        }
                
        logger.logInfo(JDBCPlugin.Util.getString("JDBCConnector.JDBCConnector_stopped._3")); //$NON-NLS-1$
    }
    
	@Override
    public Connection getConnection(ExecutionContext context) throws ConnectorException {
		DataSource dataSource = getDataSource();
		if (dataSource == null) {
			return getXAConnection(context, null);
		}
		java.sql.Connection conn = null;
		try { 
			if (context == null || context.getConnectorIdentity() instanceof SingleIdentity) {
				conn = dataSource.getConnection();
			} else if (context.getConnectorIdentity() instanceof UserIdentity) {
				UserIdentity id = (UserIdentity)context.getConnectorIdentity();
				conn = dataSource.getConnection(id.getMappedUser(), id.getPassword());
			}
			setDefaultTransactionIsolationLevel(conn);
		} catch (SQLException e) {
			throw new ConnectorException(e);
		}
		return new JDBCSourceConnection(conn, this.environment, sqlTranslator);
    }
	
	@Override
	public XAConnection getXAConnection(
			ExecutionContext context,
			TransactionContext transactionContext) throws ConnectorException {
		XADataSource xaDataSource = getXADataSource();
		if (xaDataSource == null) {
			throw new UnsupportedOperationException("Connector is not XA capable");
		}
		javax.sql.XAConnection conn = null;
		try {
			if (context == null || context.getConnectorIdentity() instanceof SingleIdentity) {
				conn = xaDataSource.getXAConnection();
			} else if (context.getConnectorIdentity() instanceof UserIdentity) {
				UserIdentity id = (UserIdentity)context.getConnectorIdentity();
				conn = xaDataSource.getXAConnection(id.getMappedUser(), id.getPassword());
			}
			java.sql.Connection c = conn.getConnection();
			setDefaultTransactionIsolationLevel(c);
			return new JDBCSourceXAConnection(c, conn, this.environment, sqlTranslator);
		} catch (SQLException e) {
			throw new ConnectorException(e);
		}
	}

    @Override
	public ConnectorCapabilities getCapabilities() {
		return capabilities;
	}

	static ConnectorCapabilities createCapabilities(ConnectorEnvironment environment, ClassLoader loader)
		throws ConnectorException {
		//create Capabilities
		String className = environment.getProperties().getProperty(JDBCPropertyNames.EXT_CAPABILITY_CLASS, JDBCCapabilities.class.getName());  
		try {
		    ConnectorCapabilities result = (ConnectorCapabilities)ReflectionHelper.create(className, null, loader);
		    if(result instanceof JDBCCapabilities) {
		        String setCriteriaBatchSize = environment.getProperties().getProperty(JDBCPropertyNames.SET_CRITERIA_BATCH_SIZE);
		        if(setCriteriaBatchSize != null) {
		            int maxInCriteriaSize = Integer.parseInt(setCriteriaBatchSize);
		            if(maxInCriteriaSize > 0) {
		                ((JDBCCapabilities)result).setMaxInCriteriaSize(maxInCriteriaSize);
		            }
		        } 
		    }
		    return result;
		} catch (Exception e) {
			throw new ConnectorException(e);
		}
	}
	
    protected void createDataSources(String dataSourceClassName, final Properties connectionProps) throws ConnectorException {
        // create data source
        Object temp = null;
        try {
        	temp = ReflectionHelper.create(dataSourceClassName, null, Thread.currentThread().getContextClassLoader());
        } catch (MetaMatrixCoreException e) {
    		throw new ConnectorException(e,JDBCPlugin.Util.getString("JDBCSourceConnectionFactory.Unable_to_load_the_JDBC_driver_class_6", dataSourceClassName)); //$NON-NLS-1$
    	}

        final String url = connectionProps.getProperty(JDBCPropertyNames.URL);
        if (url == null || url.trim().length() == 0) {
            throw new ConnectorException(JDBCPlugin.Util.getString("JDBCSourceConnectionFactory.Missing_JDBC_database_name_3")); //$NON-NLS-1$
        }
        
    	if (temp instanceof Driver) {
    		final Driver driver = (Driver)temp;
    		// check URL if there is one
            validateURL(driver, url);
    		this.ds = (DataSource)Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(), new Class[] {DataSource.class}, new InvocationHandler() {
    			@Override
    			public Object invoke(Object proxy, Method method,
    					Object[] args) throws Throwable {
    				if (method.getName().equals("getConnection")) {
    					Properties p = new Properties();
    					if (args.length == 2) {
    						p.put("user", args[0]);
    						p.put("password", args[1]);
    					} else {
    						p.put("user", connectionProps.getProperty(JDBCPropertyNames.USERNAME));
    						p.put("password", connectionProps.getProperty(JDBCPropertyNames.PASSWORD));
    					}
    					return driver.connect(url, p);
    				} 
    				throw new UnsupportedOperationException("Driver DataSource proxy only provides Connections");
    			}
    		});
    	} else {
    		parseURL(url, connectionProps);
    		if (temp instanceof DataSource) {
	    		this.ds = (DataSource)temp;
	            PropertiesUtils.setBeanProperties(this.ds, connectionProps, null);
    		} else if (temp instanceof XADataSource) {
    			this.xaDs = (XADataSource)temp;
    	        PropertiesUtils.setBeanProperties(this.xaDs, connectionProps, null);
    		} else {
    			throw new ConnectorException("Specified class is not a XADataSource, DataSource, or Driver " + dataSourceClassName);
    		}
    	} 
    	if (this.ds instanceof XADataSource) {
    		this.xaDs = (XADataSource)this.ds;
    	}
    }
    
    public DataSource getDataSource() {
    	return ds;
    }
    
    public XADataSource getXADataSource() {
		return xaDs;
	}
        
    private void validateURL(Driver driver, String url) throws ConnectorException {
        boolean acceptsURL = false;
        try {
            acceptsURL = driver.acceptsURL(url);
        } catch ( SQLException e ) {
            throw new ConnectorException(e);
        }
        if(!acceptsURL ){
            throw new ConnectorException(JDBCPlugin.Util.getString("JDBCSourceConnectionFactory.Driver__7", driver.getClass().getName(), url)); //$NON-NLS-1$
        }
    }
    
    /**
     * Parse URL for DataSource connection properties and add to connectionProps.
     * @param url
     * @param connectionProps
     * @throws ConnectorException 
     */
    static void parseURL(final String url, final Properties connectionProps) throws ConnectorException {
        // Will be: [jdbc:mmx:dbType://aHost:aPort], [DatabaseName=aDataBase], [CollectionID=aCollectionID], ...
        final String[] urlParts = url.split(";"); //$NON-NLS-1$

        // Will be: [jdbc:mmx:dbType:], [aHost:aPort]
        final String[] protoHost = urlParts[0].split("//"); //$NON-NLS-1$

        // Will be: [aHost], [aPort]
        final String[] hostPort = protoHost[1].split(":"); //$NON-NLS-1$
        connectionProps.setProperty(XAJDBCPropertyNames.SERVER_NAME, (String)hostPort[0]);
        connectionProps.setProperty(XAJDBCPropertyNames.PORT_NUMBER, (String)hostPort[1]);

        // For "databaseName", "SID", and all optional props
        // (<propName1>=<propValue1>;<propName2>=<propValue2>;...)
        for ( int i = 1; i < urlParts.length; i++ ) {
            final String nameVal = (String) urlParts[i];
            // Will be: [propName], [propVal]
            final String[] aProp = nameVal.split("="); //$NON-NLS-1$
            if ( aProp.length > 1) {
                // Set optional prop names lower case so that we can find
                // set method names for them when we introspect the DataSource
                connectionProps.setProperty(aProp[0].toLowerCase(), aProp[1]);
            }
        }
        
        String serverName = connectionProps.getProperty(XAJDBCPropertyNames.SERVER_NAME);
        String serverPort = connectionProps.getProperty(XAJDBCPropertyNames.PORT_NUMBER);
    	if ( serverName == null || serverName.trim().length() == 0 ) {
            throw new ConnectorException(JDBCPlugin.Util.getString("JDBCSourceConnectionFactory.MissingProp",  //$NON-NLS-1$
                    XAJDBCPropertyNames.SERVER_NAME));
        }
        if ( serverPort == null || serverPort.trim().length() == 0 ) {
            throw new ConnectorException(JDBCPlugin.Util.getString("JDBCSourceConnectionFactory.MissingProp",  //$NON-NLS-1$
                    XAJDBCPropertyNames.PORT_NUMBER));
        }
        
     // Unique resource name for this connector
        final StringBuffer dataSourceResourceName = new StringBuffer(connectionProps.getProperty(XAJDBCPropertyNames.DATASOURCE_NAME, "XADS")); //$NON-NLS-1$
        dataSourceResourceName.append('_'); 
        dataSourceResourceName.append(serverName);
        dataSourceResourceName.append('_'); 
        dataSourceResourceName.append(connectionProps.getProperty(ConnectorPropertyNames.CONNECTOR_ID));
        connectionProps.setProperty( XAJDBCPropertyNames.DATASOURCE_NAME, dataSourceResourceName.toString());
    }
    
    public int getDefaultTransactionIsolationLevel() {
        return this.transIsoLevel;
    }
    
	protected void setDefaultTransactionIsolationLevel(java.sql.Connection sqlConn)
			throws SQLException {
		if(getDefaultTransactionIsolationLevel() != NO_ISOLATION_LEVEL_SET && getDefaultTransactionIsolationLevel() != java.sql.Connection.TRANSACTION_NONE){
		    sqlConn.setTransactionIsolation(getDefaultTransactionIsolationLevel());
		}
	}
        
}
