/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.proxy.backend.text.distsql.ral;

import io.netty.util.DefaultAttributeMap;
import org.apache.shardingsphere.distsql.parser.statement.ral.common.show.ShowVariableStatement;
import org.apache.shardingsphere.infra.config.props.ConfigurationProperties;
import org.apache.shardingsphere.infra.config.props.ConfigurationPropertyKey;
import org.apache.shardingsphere.mode.manager.ContextManager;
import org.apache.shardingsphere.mode.metadata.MetaDataContexts;
import org.apache.shardingsphere.proxy.backend.context.ProxyContext;
import org.apache.shardingsphere.proxy.backend.response.header.ResponseHeader;
import org.apache.shardingsphere.proxy.backend.response.header.query.QueryResponseHeader;
import org.apache.shardingsphere.proxy.backend.session.ConnectionSession;
import org.apache.shardingsphere.proxy.backend.text.distsql.ral.common.ShowDistSQLBackendHandler;
import org.apache.shardingsphere.proxy.backend.text.distsql.ral.common.enums.VariableEnum;
import org.apache.shardingsphere.proxy.backend.text.distsql.ral.common.exception.UnsupportedVariableException;
import org.apache.shardingsphere.proxy.backend.util.SystemPropertyUtil;
import org.apache.shardingsphere.transaction.core.TransactionType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.SQLException;
import java.util.Collection;
import java.util.Properties;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public final class ShowVariableBackendHandlerTest {
    
    private ContextManager contextManagerBefore;
    
    private ConnectionSession connectionSession;
    
    @Before
    public void setup() {
        contextManagerBefore = ProxyContext.getInstance().getContextManager();
        ProxyContext.getInstance().init(mock(ContextManager.class, RETURNS_DEEP_STUBS));
        when(ProxyContext.getInstance().getContextManager().getMetaDataContexts().getProps().getValue(ConfigurationPropertyKey.PROXY_BACKEND_DRIVER_TYPE)).thenReturn("JDBC");
        connectionSession = new ConnectionSession(TransactionType.LOCAL, new DefaultAttributeMap());
    }
    
    @Test
    public void assertShowTransactionType() throws SQLException {
        connectionSession.setCurrentSchema("schema");
        ShowDistSQLBackendHandler backendHandler = new ShowDistSQLBackendHandler(new ShowVariableStatement("transaction_type"), connectionSession);
        ResponseHeader actual = backendHandler.execute();
        assertThat(actual, instanceOf(QueryResponseHeader.class));
        assertThat(((QueryResponseHeader) actual).getQueryHeaders().size(), is(1));
        backendHandler.next();
        Collection<Object> rowData = backendHandler.getRowData();
        assertThat(rowData.iterator().next(), is("LOCAL"));
    }
    
    @Test
    public void assertShowCachedConnections() throws SQLException {
        connectionSession.setCurrentSchema("schema");
        ShowDistSQLBackendHandler backendHandler = new ShowDistSQLBackendHandler(new ShowVariableStatement("cached_connections"), connectionSession);
        ResponseHeader actual = backendHandler.execute();
        assertThat(actual, instanceOf(QueryResponseHeader.class));
        assertThat(((QueryResponseHeader) actual).getQueryHeaders().size(), is(1));
        backendHandler.next();
        Collection<Object> rowData = backendHandler.getRowData();
        assertThat(rowData.iterator().next(), is(0));
    }
    
    @Test(expected = UnsupportedVariableException.class)
    public void assertShowCachedConnectionFailed() throws SQLException {
        connectionSession.setCurrentSchema("schema");
        new ShowDistSQLBackendHandler(new ShowVariableStatement("cached_connectionss"), connectionSession).execute();
    }
    
    @Test
    public void assertShowAgentPluginsEnabled() throws SQLException {
        SystemPropertyUtil.setSystemProperty(VariableEnum.AGENT_PLUGINS_ENABLED.name(), Boolean.TRUE.toString());
        connectionSession.setCurrentSchema("schema");
        ShowDistSQLBackendHandler backendHandler = new ShowDistSQLBackendHandler(new ShowVariableStatement(VariableEnum.AGENT_PLUGINS_ENABLED.name()), connectionSession);
        ResponseHeader actual = backendHandler.execute();
        assertThat(actual, instanceOf(QueryResponseHeader.class));
        assertThat(((QueryResponseHeader) actual).getQueryHeaders().size(), is(1));
        backendHandler.next();
        Collection<Object> rowData = backendHandler.getRowData();
        assertThat(rowData.iterator().next(), is(Boolean.TRUE.toString()));
    }
    
    @Test
    public void assertShowPropsVariable() throws SQLException {
        connectionSession.setCurrentSchema("schema");
        ContextManager contextManager = mock(ContextManager.class);
        ProxyContext.getInstance().init(contextManager);
        MetaDataContexts metaDataContexts = mock(MetaDataContexts.class);
        when(contextManager.getMetaDataContexts()).thenReturn(metaDataContexts);
        Properties props = new Properties();
        props.put("sql-show", "true");
        ConfigurationProperties configurationProperties = new ConfigurationProperties(props);
        when(metaDataContexts.getProps()).thenReturn(configurationProperties);
        ShowDistSQLBackendHandler backendHandler = new ShowDistSQLBackendHandler(new ShowVariableStatement("SQL_SHOW"), connectionSession);
        ResponseHeader actual = backendHandler.execute();
        assertThat(actual, instanceOf(QueryResponseHeader.class));
        assertThat(((QueryResponseHeader) actual).getQueryHeaders().size(), is(1));
        backendHandler.next();
        Collection<Object> rowData = backendHandler.getRowData();
        assertThat(rowData.iterator().next(), is("true"));
    }
    
    @After
    public void tearDown() {
        ProxyContext.getInstance().init(contextManagerBefore);
    }
}
