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

package org.apache.flink.table.planner.plan.nodes.exec.stream;

import org.apache.flink.api.dag.Transformation;
import org.apache.flink.table.api.TableConfig;
import org.apache.flink.table.api.TableException;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.planner.codegen.sort.ComparatorCodeGenerator;
import org.apache.flink.table.planner.delegation.PlannerBase;
import org.apache.flink.table.planner.plan.nodes.exec.ExecEdge;
import org.apache.flink.table.planner.plan.nodes.exec.ExecNodeBase;
import org.apache.flink.table.planner.plan.nodes.exec.ExecNodeContext;
import org.apache.flink.table.planner.plan.nodes.exec.InputProperty;
import org.apache.flink.table.planner.plan.nodes.exec.spec.SortSpec;
import org.apache.flink.table.planner.plan.nodes.exec.utils.ExecNodeUtil;
import org.apache.flink.table.planner.utils.InternalConfigOptions;
import org.apache.flink.table.runtime.generated.GeneratedRecordComparator;
import org.apache.flink.table.runtime.operators.sort.StreamSortOperator;
import org.apache.flink.table.runtime.typeutils.InternalTypeInfo;
import org.apache.flink.table.types.logical.RowType;

import java.util.Collections;

/**
 * {@link StreamExecNode} for Sort.
 *
 * <p><b>NOTES:</b> This class is used for testing with bounded source now. If a query is converted
 * to this node in product environment, an exception will be thrown.
 */
public class StreamExecSort extends ExecNodeBase<RowData> implements StreamExecNode<RowData> {

    private static final String SORT_TRANSFORMATION = "sort";

    private final SortSpec sortSpec;

    public StreamExecSort(
            SortSpec sortSpec,
            InputProperty inputProperty,
            RowType outputType,
            String description) {
        super(
                ExecNodeContext.newNodeId(),
                ExecNodeContext.newContext(StreamExecSort.class),
                Collections.singletonList(inputProperty),
                outputType,
                description);
        this.sortSpec = sortSpec;
    }

    @SuppressWarnings("unchecked")
    @Override
    protected Transformation<RowData> translateToPlanInternal(PlannerBase planner) {
        TableConfig config = planner.getTableConfig();
        if (!config.getConfiguration()
                .getBoolean(InternalConfigOptions.TABLE_EXEC_NON_TEMPORAL_SORT_ENABLED)) {
            throw new TableException("Sort on a non-time-attribute field is not supported.");
        }

        ExecEdge inputEdge = getInputEdges().get(0);
        RowType inputType = (RowType) inputEdge.getOutputType();
        // sort code gen
        GeneratedRecordComparator rowComparator =
                ComparatorCodeGenerator.gen(
                        config, "StreamExecSortComparator", inputType, sortSpec);
        StreamSortOperator sortOperator =
                new StreamSortOperator(InternalTypeInfo.of(inputType), rowComparator);
        Transformation<RowData> inputTransform =
                (Transformation<RowData>) inputEdge.translateToPlan(planner);

        return ExecNodeUtil.createOneInputTransformation(
                inputTransform,
                createTransformationMeta(SORT_TRANSFORMATION, config),
                sortOperator,
                InternalTypeInfo.of(inputType),
                inputTransform.getParallelism());
    }
}
