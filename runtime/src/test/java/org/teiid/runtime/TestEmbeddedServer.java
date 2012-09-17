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

package org.teiid.runtime;

import static org.junit.Assert.*;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.transaction.HeuristicMixedException;
import javax.transaction.HeuristicRollbackException;
import javax.transaction.InvalidTransactionException;
import javax.transaction.NotSupportedException;
import javax.transaction.RollbackException;
import javax.transaction.Status;
import javax.transaction.SystemException;
import javax.transaction.Transaction;
import javax.transaction.TransactionManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.teiid.adminapi.Model.Type;
import org.teiid.adminapi.impl.ModelMetaData;
import org.teiid.deployers.VirtualDatabaseException;
import org.teiid.jdbc.TeiidDriver;
import org.teiid.language.Command;
import org.teiid.language.QueryExpression;
import org.teiid.metadata.Column;
import org.teiid.metadata.MetadataFactory;
import org.teiid.metadata.RuntimeMetadata;
import org.teiid.metadata.Table;
import org.teiid.runtime.EmbeddedServer.ConnectionFactoryProvider;
import org.teiid.translator.DataNotAvailableException;
import org.teiid.translator.ExecutionContext;
import org.teiid.translator.ExecutionFactory;
import org.teiid.translator.ResultSetExecution;
import org.teiid.translator.TranslatorException;
import org.teiid.translator.TypeFacility;
import org.teiid.translator.UpdateExecution;

@SuppressWarnings("nls")
public class TestEmbeddedServer {
	private final class MockTransactionManager implements TransactionManager {
		ThreadLocal<Transaction> txns = new ThreadLocal<Transaction>();
		List<Transaction> txnHistory = new ArrayList<Transaction>();

		@Override
		public Transaction suspend() throws SystemException {
			Transaction result = txns.get();
			txns.remove();
			return result;
		}

		@Override
		public void setTransactionTimeout(int seconds) throws SystemException {
		}

		@Override
		public void setRollbackOnly() throws IllegalStateException, SystemException {
			Transaction result = txns.get();
			if (result == null) {
				throw new IllegalStateException();
			}
			result.setRollbackOnly();
		}

		@Override
		public void rollback() throws IllegalStateException, SecurityException,
				SystemException {
			Transaction t = checkNull(false);
			txns.remove();
			t.rollback();
		}

		@Override
		public void resume(Transaction tobj) throws InvalidTransactionException,
				IllegalStateException, SystemException {
			checkNull(true);
			txns.set(tobj);
		}

		private Transaction checkNull(boolean isNull) {
			Transaction t = txns.get();
			if ((!isNull && t == null) || (isNull && t != null)) {
				throw new IllegalStateException();
			}
			return t;
		}

		@Override
		public Transaction getTransaction() throws SystemException {
			return txns.get();
		}

		@Override
		public int getStatus() throws SystemException {
			Transaction t = txns.get();
			if (t == null) {
				return Status.STATUS_NO_TRANSACTION;
			}
			return t.getStatus();
		}

		@Override
		public void commit() throws RollbackException, HeuristicMixedException,
				HeuristicRollbackException, SecurityException,
				IllegalStateException, SystemException {
			Transaction t = checkNull(false);
			txns.remove();
			t.commit();
		}

		@Override
		public void begin() throws NotSupportedException, SystemException {
			checkNull(true);
			Transaction t = Mockito.mock(Transaction.class);
			txnHistory.add(t);
			txns.set(t);
		}
	}

	EmbeddedServer es;
	
	@Before public void setup() {
		es = new EmbeddedServer();
	}
	
	@After public void teardown() {
		es.stop();
	}
	
	@Test public void testDeploy() throws Exception {
		EmbeddedConfiguration ec = new EmbeddedConfiguration();
		ec.setUseDisk(false);
		es.start(ec);
		
		es.addTranslator("y", new ExecutionFactory<AtomicInteger, Object> () {
			@Override
			public Object getConnection(AtomicInteger factory)
					throws TranslatorException {
				return factory.incrementAndGet();
			}
			
			@Override
			public void closeConnection(Object connection, AtomicInteger factory) {
				
			}
			
			@Override
			public void getMetadata(MetadataFactory metadataFactory, Object conn)
					throws TranslatorException {
				assertEquals(conn, Integer.valueOf(1));
				Table t = metadataFactory.addTable("my-table");
				t.setSupportsUpdate(true);
				Column c = metadataFactory.addColumn("my-column", TypeFacility.RUNTIME_NAMES.STRING, t);
				c.setUpdatable(true);
			}
			
			@Override
			public ResultSetExecution createResultSetExecution(
					QueryExpression command, ExecutionContext executionContext,
					RuntimeMetadata metadata, Object connection)
					throws TranslatorException {
				ResultSetExecution rse = new ResultSetExecution() {
					
					@Override
					public void execute() throws TranslatorException {
						
					}
					
					@Override
					public void close() {
						
					}
					
					@Override
					public void cancel() throws TranslatorException {
						
					}
					
					@Override
					public List<?> next() throws TranslatorException, DataNotAvailableException {
						return null;
					}
				};
				return rse;
			}
			
			@Override
			public UpdateExecution createUpdateExecution(Command command,
					ExecutionContext executionContext,
					RuntimeMetadata metadata, Object connection)
					throws TranslatorException {
				UpdateExecution ue = new UpdateExecution() {
					
					@Override
					public void execute() throws TranslatorException {
						
					}
					
					@Override
					public void close() {
						
					}
					
					@Override
					public void cancel() throws TranslatorException {
						
					}
					
					@Override
					public int[] getUpdateCounts() throws DataNotAvailableException,
							TranslatorException {
						return new int[] {2};
					}
				};
				return ue;
			}
		});
		final AtomicInteger counter = new AtomicInteger();
		ConnectionFactoryProvider<AtomicInteger> cfp = new ConnectionFactoryProvider<AtomicInteger>() {
			@Override
			public AtomicInteger getConnectionFactory()
					throws TranslatorException {
				return counter;
			}
		};
		
		es.addConnectionFactoryProvider("z", cfp);
		
		ModelMetaData mmd = new ModelMetaData();
		mmd.setName("my-schema");
		mmd.addSourceMapping("x", "y", "z");

		ModelMetaData mmd1 = new ModelMetaData();
		mmd1.setName("virt");
		mmd1.setModelType(Type.VIRTUAL);
		mmd1.setSchemaSourceType("ddl");
		mmd1.setSchemaText("create view \"my-view\" OPTIONS (UPDATABLE 'true') as select * from \"my-table\"");

		es.deployVDB("test", mmd, mmd1);
		
		TeiidDriver td = es.getDriver();
		Connection c = td.connect("jdbc:teiid:test", null);
		Statement s = c.createStatement();
		ResultSet rs = s.executeQuery("select * from \"my-view\"");
		assertFalse(rs.next());
		assertEquals("my-column", rs.getMetaData().getColumnLabel(1));
		
		s.execute("update \"my-view\" set \"my-column\" = 'a'");
		assertEquals(2, s.getUpdateCount());
	}
	
	@Test(expected=VirtualDatabaseException.class) public void testDeploymentError() throws Exception {
		EmbeddedConfiguration ec = new EmbeddedConfiguration();
		ec.setUseDisk(false);
		es.start(ec);
		
		ModelMetaData mmd1 = new ModelMetaData();
		mmd1.setName("virt");
		mmd1.setModelType(Type.VIRTUAL);
		mmd1.setSchemaSourceType("ddl");
		mmd1.setSchemaText("create view \"my-view\" OPTIONS (UPDATABLE 'true') as select * from \"my-table\"");

		es.deployVDB("test", mmd1);
	}
	
	@Test public void testValidationOrder() throws Exception {
		EmbeddedConfiguration ec = new EmbeddedConfiguration();
		ec.setUseDisk(false);
		es.start(ec);
		
		ModelMetaData mmd1 = new ModelMetaData();
		mmd1.setName("b");
		mmd1.setModelType(Type.VIRTUAL);
		mmd1.setSchemaSourceType("ddl");
		mmd1.setSchemaText("create view v as select 1");

		ModelMetaData mmd2 = new ModelMetaData();
		mmd2.setName("a");
		mmd2.setModelType(Type.VIRTUAL);
		mmd2.setSchemaSourceType("ddl");
		mmd2.setSchemaText("create view v1 as select * from v");

		//We need mmd1 to validate before mmd2, reversing the order will result in an exception
		es.deployVDB("test", mmd1, mmd2);
		
		try {
			es.deployVDB("test2", mmd2, mmd1);
			fail();
		} catch (VirtualDatabaseException e) {
			
		}
	}
	
	@Test public void testTransactions() throws Exception {
		EmbeddedConfiguration ec = new EmbeddedConfiguration();
		MockTransactionManager tm = new MockTransactionManager();
		ec.setTransactionManager(tm);
		ec.setUseDisk(false);
		es.start(ec);
		
		ModelMetaData mmd1 = new ModelMetaData();
		mmd1.setName("b");
		mmd1.setModelType(Type.VIRTUAL);
		mmd1.setSchemaSourceType("ddl");
		mmd1.setSchemaText("create view v as select 1; " +
				"create virtual procedure proc () options (updatecount 2) as begin select * from v; end; " +
				"create virtual procedure proc1 () as begin atomic select * from v; end; ");

		es.deployVDB("test", mmd1);
		
		TeiidDriver td = es.getDriver();
		Connection c = td.connect("jdbc:teiid:test", null);
		//local txn
		c.setAutoCommit(false);
		Statement s = c.createStatement();
		s.execute("select 1");
		c.setAutoCommit(true);
		assertEquals(1, tm.txnHistory.size());
		Transaction txn = tm.txnHistory.remove(0);
		Mockito.verify(txn).commit();
		
		//should be an auto-commit txn (could also force with autoCommitTxn=true)
		s.execute("call proc ()");
		
		assertEquals(1, tm.txnHistory.size());
		txn = tm.txnHistory.remove(0);
		Mockito.verify(txn).commit();
		
		//block txn
		s.execute("call proc1()");
		
		assertEquals(1, tm.txnHistory.size());
		txn = tm.txnHistory.remove(0);
		Mockito.verify(txn).commit();
		
		s.execute("set autoCommitTxn on");
		s.execute("set noexec on");
		s.execute("select 1");
		assertFalse(s.getResultSet().next());
	}

}