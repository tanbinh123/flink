/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.api;

import org.apache.flink.FlinkVersion;
import org.apache.flink.table.api.config.TableConfigOptions;
import org.apache.flink.table.api.internal.TableResultInternal;
import org.apache.flink.table.planner.utils.JsonPlanTestBase;
import org.apache.flink.table.planner.utils.JsonTestUtils;
import org.apache.flink.table.planner.utils.TableTestUtil;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import static org.apache.flink.core.testutils.FlinkAssertions.anyCauseMatches;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Test for {@link CompiledPlan} and related {@link TableEnvironment} methods. */
public class CompiledPlanITCase extends JsonPlanTestBase {

    private static final List<String> DATA =
            Arrays.asList("1,1,hi", "2,1,hello", "3,2,hello world");
    private static final String[] COLUMNS_DEFINITION =
            new String[] {"a bigint", "b int", "c varchar"};

    @Before
    public void setup() throws Exception {
        super.setup();

        String srcTableDdl =
                "CREATE TABLE MyTable (\n"
                        + String.join(",", COLUMNS_DEFINITION)
                        + ") with (\n"
                        + "  'connector' = 'values',\n"
                        + "  'bounded' = 'false')";
        tableEnv.executeSql(srcTableDdl);

        String sinkTableDdl =
                "CREATE TABLE MySink (\n"
                        + String.join(",", COLUMNS_DEFINITION)
                        + ") with (\n"
                        + "  'connector' = 'values',\n"
                        + "  'table-sink-class' = 'DEFAULT')";
        tableEnv.executeSql(sinkTableDdl);
    }

    @Test
    public void testCompilePlanSql() throws IOException {
        CompiledPlan compiledPlan =
                tableEnv.compilePlanSql("insert into MySink select * from MyTable");
        String expected = TableTestUtil.readFromResource("/jsonplan/testGetJsonPlan.out");
        assertThat(
                        TableTestUtil.replaceExecNodeId(
                                TableTestUtil.replaceFlinkVersion(
                                        TableTestUtil.getFormattedJson(
                                                compiledPlan.asJsonString()))))
                .isEqualTo(
                        TableTestUtil.replaceExecNodeId(
                                TableTestUtil.replaceFlinkVersion(
                                        TableTestUtil.getFormattedJson(expected))));
    }

    @Test
    public void testExecutePlan() throws Exception {
        File sinkPath = createSourceSinkTables();

        CompiledPlan plan = tableEnv.compilePlanSql("insert into sink select * from src");
        tableEnv.executePlan(plan).await();

        assertResult(DATA, sinkPath);
    }

    @Test
    public void testExecutePlanSql() throws Exception {
        Path planPath = Paths.get(URI.create(getTempDirPath("plan")).getPath(), "plan.json");
        FileUtils.createParentDirectories(planPath.toFile());

        File sinkPath = createSourceSinkTables();

        CompiledPlan plan = tableEnv.compilePlanSql("insert into sink select * from src");
        plan.writeToFile(planPath);

        tableEnv.executeSql(String.format("EXECUTE PLAN '%s'", planPath.toAbsolutePath())).await();

        assertResult(DATA, sinkPath);
    }

    @Test
    public void testCompilePlan() throws Exception {
        Path planPath =
                Paths.get(URI.create(getTempDirPath("plan")).getPath(), "plan.json")
                        .toAbsolutePath();
        FileUtils.createParentDirectories(planPath.toFile());

        File sinkPath = createSourceSinkTables();

        TableResult tableResult =
                tableEnv.executeSql(
                        String.format(
                                "COMPILE PLAN '%s' FOR INSERT INTO sink SELECT * FROM src",
                                planPath));

        assertThat(tableResult).isEqualTo(TableResultInternal.TABLE_RESULT_OK);
        assertThat(planPath.toFile()).exists();

        assertThatThrownBy(
                        () ->
                                tableEnv.executeSql(
                                        String.format(
                                                "COMPILE PLAN '%s' FOR INSERT INTO sink SELECT * FROM src",
                                                planPath)))
                .satisfies(anyCauseMatches(TableException.class, "Cannot overwrite the plan file"));

        tableEnv.executeSql(String.format("EXECUTE PLAN '%s'", planPath)).await();

        assertResult(DATA, sinkPath);
    }

    @Test
    public void testCompilePlanWithStatementSet() throws Exception {
        Path planPath =
                Paths.get(URI.create(getTempDirPath("plan")).getPath(), "plan.json")
                        .toAbsolutePath();
        FileUtils.createParentDirectories(planPath.toFile());

        createTestCsvSourceTable("src", DATA, COLUMNS_DEFINITION);
        File sinkAPath = createTestCsvSinkTable("sinkA", COLUMNS_DEFINITION);
        File sinkBPath = createTestCsvSinkTable("sinkB", COLUMNS_DEFINITION);

        TableResult tableResult =
                tableEnv.executeSql(
                        String.format(
                                "COMPILE PLAN '%s' FOR STATEMENT SET BEGIN "
                                        + "INSERT INTO sinkA SELECT * FROM src;"
                                        + "INSERT INTO sinkB SELECT a + 1, b + 1, CONCAT(c, '-something') FROM src;"
                                        + "END",
                                planPath));

        assertThat(tableResult).isEqualTo(TableResultInternal.TABLE_RESULT_OK);
        assertThat(planPath.toFile()).exists();

        tableEnv.executeSql(String.format("EXECUTE PLAN '%s'", planPath)).await();

        assertResult(DATA, sinkAPath);
        assertResult(
                Arrays.asList(
                        "2,2,hi-something", "3,2,hello-something", "4,3,hello world-something"),
                sinkBPath);
    }

    @Test
    public void testCompilePlanIfNotExists() throws Exception {
        Path planPath =
                Paths.get(URI.create(getTempDirPath("plan")).getPath(), "plan.json")
                        .toAbsolutePath();
        FileUtils.createParentDirectories(planPath.toFile());

        File sinkPath = createSourceSinkTables();

        TableResult tableResult =
                tableEnv.executeSql(
                        String.format(
                                "COMPILE PLAN '%s' IF NOT EXISTS FOR INSERT INTO sink SELECT * FROM src",
                                planPath));

        assertThat(tableResult).isEqualTo(TableResultInternal.TABLE_RESULT_OK);
        assertThat(planPath.toFile()).exists();

        // This should not mutate the plan, as it already exists
        assertThat(
                        tableEnv.executeSql(
                                String.format(
                                        "COMPILE PLAN '%s' IF NOT EXISTS FOR INSERT INTO sink SELECT a + 1, b + 1, CONCAT(c, '-something') FROM src",
                                        planPath)))
                .isEqualTo(TableResultInternal.TABLE_RESULT_OK);

        tableEnv.executeSql(String.format("EXECUTE PLAN '%s'", planPath)).await();

        assertResult(DATA, sinkPath);
    }

    @Test
    public void testCompilePlanOverwrite() throws Exception {
        tableEnv.getConfig().getConfiguration().set(TableConfigOptions.PLAN_FORCE_RECOMPILE, true);

        Path planPath =
                Paths.get(URI.create(getTempDirPath("plan")).getPath(), "plan.json")
                        .toAbsolutePath();
        FileUtils.createParentDirectories(planPath.toFile());

        List<String> expectedData =
                Arrays.asList(
                        "2,2,hi-something", "3,2,hello-something", "4,3,hello world-something");
        File sinkPath = createSourceSinkTables();

        TableResult tableResult =
                tableEnv.executeSql(
                        String.format(
                                "COMPILE PLAN '%s' FOR INSERT INTO sink SELECT * FROM src",
                                planPath));

        assertThat(tableResult).isEqualTo(TableResultInternal.TABLE_RESULT_OK);
        assertThat(planPath.toFile()).exists();

        // This should overwrite the plan
        assertThat(
                        tableEnv.executeSql(
                                String.format(
                                        "COMPILE PLAN '%s' FOR INSERT INTO sink SELECT a + 1, b + 1, CONCAT(c, '-something') FROM src",
                                        planPath)))
                .isEqualTo(TableResultInternal.TABLE_RESULT_OK);

        tableEnv.executeSql(String.format("EXECUTE PLAN '%s'", planPath)).await();

        assertResult(expectedData, sinkPath);
    }

    @Test
    public void testCompileAndExecutePlan() throws Exception {
        Path planPath =
                Paths.get(URI.create(getTempDirPath("plan")).getPath(), "plan.json")
                        .toAbsolutePath();
        FileUtils.createParentDirectories(planPath.toFile());

        File sinkPath = createSourceSinkTables();

        tableEnv.executeSql(
                        String.format(
                                "COMPILE AND EXECUTE PLAN '%s' FOR INSERT INTO sink SELECT * FROM src",
                                planPath))
                .await();

        assertThat(planPath.toFile()).exists();

        assertResult(DATA, sinkPath);
    }

    @Test
    public void testCompileAndExecutePlanWithStatementSet() throws Exception {
        Path planPath =
                Paths.get(URI.create(getTempDirPath("plan")).getPath(), "plan.json")
                        .toAbsolutePath();
        FileUtils.createParentDirectories(planPath.toFile());

        createTestCsvSourceTable("src", DATA, COLUMNS_DEFINITION);
        File sinkAPath = createTestCsvSinkTable("sinkA", COLUMNS_DEFINITION);
        File sinkBPath = createTestCsvSinkTable("sinkB", COLUMNS_DEFINITION);

        tableEnv.executeSql(
                        String.format(
                                "COMPILE AND EXECUTE PLAN '%s' FOR STATEMENT SET BEGIN "
                                        + "INSERT INTO sinkA SELECT * FROM src;"
                                        + "INSERT INTO sinkB SELECT a + 1, b + 1, CONCAT(c, '-something') FROM src;"
                                        + "END",
                                planPath))
                .await();

        assertThat(planPath.toFile()).exists();

        assertResult(DATA, sinkAPath);
        assertResult(
                Arrays.asList(
                        "2,2,hi-something", "3,2,hello-something", "4,3,hello world-something"),
                sinkBPath);
    }

    @Test
    public void testExplainPlan() throws IOException {
        String planFromResources =
                JsonTestUtils.setFlinkVersion(
                                JsonTestUtils.readFromResource("/jsonplan/testGetJsonPlan.out"),
                                FlinkVersion.current())
                        .toString();

        String actual =
                tableEnv.explainPlan(
                        tableEnv.loadPlan(PlanReference.fromJsonString(planFromResources)),
                        ExplainDetail.JSON_EXECUTION_PLAN);
        String expected = TableTestUtil.readFromResource("/explain/testExplainJsonPlan.out");
        assertThat(TableTestUtil.replaceNodeIdInOperator(TableTestUtil.replaceStreamNodeId(actual)))
                .isEqualTo(expected);
    }

    @Test
    public void testBatchMode() {
        tableEnv = TableEnvironment.create(EnvironmentSettings.inBatchMode());

        String srcTableDdl =
                "CREATE TABLE src (\n"
                        + "  a bigint\n"
                        + ") with (\n"
                        + "  'connector' = 'values',\n"
                        + "  'bounded' = 'true')";
        tableEnv.executeSql(srcTableDdl);

        String sinkTableDdl =
                "CREATE TABLE sink (\n"
                        + "  a bigint\n"
                        + ") with (\n"
                        + "  'connector' = 'values',\n"
                        + "  'table-sink-class' = 'DEFAULT')";
        tableEnv.executeSql(sinkTableDdl);

        assertThatThrownBy(() -> tableEnv.compilePlanSql("insert into sink select * from src"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("The compiled plan feature is not supported in batch mode.");
    }

    private File createSourceSinkTables() throws IOException {
        createTestCsvSourceTable("src", DATA, COLUMNS_DEFINITION);
        return createTestCsvSinkTable("sink", COLUMNS_DEFINITION);
    }
}
