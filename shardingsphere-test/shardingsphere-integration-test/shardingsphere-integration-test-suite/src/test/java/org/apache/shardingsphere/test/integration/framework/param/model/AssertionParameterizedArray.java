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

package org.apache.shardingsphere.test.integration.framework.param.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.shardingsphere.infra.database.type.DatabaseType;
import org.apache.shardingsphere.test.integration.cases.IntegrationTestCaseContext;
import org.apache.shardingsphere.test.integration.cases.SQLCommandType;
import org.apache.shardingsphere.test.integration.cases.SQLExecuteType;
import org.apache.shardingsphere.test.integration.cases.assertion.IntegrationTestCaseAssertion;

/**
 * Parameterized array of assertion based integration test.
 */
@RequiredArgsConstructor
@Getter
public final class AssertionParameterizedArray implements ParameterizedArray {
    
    private final IntegrationTestCaseContext testCaseContext;
    
    private final IntegrationTestCaseAssertion assertion;
    
    private final String adapter;
    
    private final String scenario;
    
    private final DatabaseType databaseType;
    
    private final SQLExecuteType sqlExecuteType;
    
    private final SQLCommandType sqlCommandType;
    
    @Override
    public String toString() {
        return String.format("%s: %s -> %s -> %s -> %s", adapter, scenario, databaseType.getName(), sqlExecuteType, testCaseContext.getTestCase().getSql());
    }
}
