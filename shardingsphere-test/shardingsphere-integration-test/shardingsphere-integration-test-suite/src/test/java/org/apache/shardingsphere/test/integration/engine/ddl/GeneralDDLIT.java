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

package org.apache.shardingsphere.test.integration.engine.ddl;

import org.apache.shardingsphere.test.integration.cases.SQLCommandType;
import org.apache.shardingsphere.test.integration.cases.SQLExecuteType;
import org.apache.shardingsphere.test.integration.framework.container.compose.ComposedContainerRegistry;
import org.apache.shardingsphere.test.integration.framework.param.ParameterizedArrayFactory;
import org.apache.shardingsphere.test.integration.framework.param.model.AssertionParameterizedArray;
import org.apache.shardingsphere.test.integration.framework.runner.parallel.annotaion.ParallelLevel;
import org.apache.shardingsphere.test.integration.framework.runner.parallel.annotaion.ParallelRuntimeStrategy;
import org.junit.AfterClass;
import org.junit.Test;
import org.junit.runners.Parameterized.Parameters;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.ParseException;
import java.util.Collection;

import static org.junit.Assert.assertFalse;

@ParallelRuntimeStrategy(ParallelLevel.SCENARIO)
public final class GeneralDDLIT extends BaseDDLIT {
    
    private static final ComposedContainerRegistry COMPOSED_CONTAINER_REGISTRY = new ComposedContainerRegistry();
    
    public GeneralDDLIT(final AssertionParameterizedArray parameterizedArray) {
        super(parameterizedArray, COMPOSED_CONTAINER_REGISTRY.getComposedContainer(GeneralDDLIT.class.getSimpleName(), parameterizedArray));
    }
    
    @Parameters(name = "{0}")
    public static Collection<AssertionParameterizedArray> getParameters() {
        return ParameterizedArrayFactory.getAssertionParameterized(SQLCommandType.DDL);
    }
    
    @AfterClass
    public static void closeContainers() {
        COMPOSED_CONTAINER_REGISTRY.close();
    }
    
    @Test
    public void assertExecuteUpdate() throws SQLException, ParseException {
        try (Connection connection = getTargetDataSource().getConnection()) {
            if (SQLExecuteType.Literal == getSqlExecuteType()) {
                executeUpdateForStatement(connection);
            } else {
                executeUpdateForPreparedStatement(connection);
            }
            assertTableMetaData();
        }
    }
    
    private void executeUpdateForStatement(final Connection connection) throws SQLException, ParseException {
        try (Statement statement = connection.createStatement()) {
            assertFalse("Not a DDL statement.", statement.executeUpdate(getSQL()) > 0);
        }
    }
    
    private void executeUpdateForPreparedStatement(final Connection connection) throws SQLException, ParseException {
        try (PreparedStatement preparedStatement = connection.prepareStatement(getSQL())) {
            assertFalse("Not a DDL statement.", preparedStatement.executeUpdate() > 0);
        }
    }
    
    @Test
    public void assertExecute() throws SQLException, ParseException {
        try (Connection connection = getTargetDataSource().getConnection()) {
            if (SQLExecuteType.Literal == getSqlExecuteType()) {
                executeForStatement(connection);
            } else {
                executeForPreparedStatement(connection);
            }
            assertTableMetaData();
        }
    }
    
    private void executeForStatement(final Connection connection) throws SQLException, ParseException {
        try (Statement statement = connection.createStatement()) {
            assertFalse("Not a DDL statement.", statement.execute(getSQL()));
        }
    }
    
    private void executeForPreparedStatement(final Connection connection) throws SQLException, ParseException {
        try (PreparedStatement preparedStatement = connection.prepareStatement(getSQL())) {
            assertFalse("Not a DDL statement.", preparedStatement.execute());
        }
    }
}
