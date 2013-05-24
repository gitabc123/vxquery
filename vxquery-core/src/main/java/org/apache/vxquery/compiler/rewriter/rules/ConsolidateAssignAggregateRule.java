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
package org.apache.vxquery.compiler.rewriter.rules;

import org.apache.commons.lang3.mutable.Mutable;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.vxquery.compiler.algebricks.VXQueryConstantValue;
import org.apache.vxquery.datamodel.values.XDMConstants;
import org.apache.vxquery.functions.BuiltinFunctions;
import org.apache.vxquery.functions.BuiltinOperators;
import org.apache.vxquery.types.BuiltinTypeRegistry;
import org.apache.vxquery.types.Quantifier;
import org.apache.vxquery.types.SequenceType;

import edu.uci.ics.hyracks.algebricks.common.exceptions.AlgebricksException;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.ILogicalExpression;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.ILogicalOperator;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.IOptimizationContext;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.LogicalExpressionTag;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.LogicalOperatorTag;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.LogicalVariable;
import edu.uci.ics.hyracks.algebricks.core.algebra.expressions.AbstractFunctionCallExpression;
import edu.uci.ics.hyracks.algebricks.core.algebra.expressions.ConstantExpression;
import edu.uci.ics.hyracks.algebricks.core.algebra.expressions.VariableReferenceExpression;
import edu.uci.ics.hyracks.algebricks.core.algebra.functions.IFunctionInfo;
import edu.uci.ics.hyracks.algebricks.core.algebra.operators.logical.AbstractLogicalOperator;
import edu.uci.ics.hyracks.algebricks.core.algebra.operators.logical.AggregateOperator;
import edu.uci.ics.hyracks.algebricks.core.algebra.operators.logical.AssignOperator;
import edu.uci.ics.hyracks.algebricks.core.algebra.operators.logical.SubplanOperator;
import edu.uci.ics.hyracks.algebricks.core.rewriter.base.IAlgebraicRewriteRule;
import edu.uci.ics.hyracks.data.std.api.IPointable;
import edu.uci.ics.hyracks.data.std.primitive.BooleanPointable;

public class ConsolidateAssignAggregateRule extends AbstractVXQueryAggregateRule {
    /**
     * Find where an assign for a aggregate function is used before aggregate operator for a sequence.
     * Search pattern 1: assign [function-call: count(function-call: treat($$))]
     * Search pattern 2: $$ for aggregate [function-call: sequence()]
     */
    @Override
    public boolean rewritePre(Mutable<ILogicalOperator> opRef, IOptimizationContext context) throws AlgebricksException {
        IFunctionInfo aggregateInfo;
        AbstractFunctionCallExpression finalFunctionCall;
        Mutable<ILogicalExpression> mutableVariableExpresion;

        // Check if assign is for aggregate function.
        AbstractLogicalOperator op = (AbstractLogicalOperator) opRef.getValue();
        if (op.getOperatorTag() != LogicalOperatorTag.ASSIGN) {
            return false;
        }
        AssignOperator assign = (AssignOperator) op;

        Mutable<ILogicalExpression> mutableLogicalExpression = assign.getExpressions().get(0);
        ILogicalExpression logicalExpression = mutableLogicalExpression.getValue();
        if (logicalExpression.getExpressionTag() != LogicalExpressionTag.FUNCTION_CALL) {
            return false;
        }
        AbstractFunctionCallExpression functionCall = (AbstractFunctionCallExpression) logicalExpression;
        aggregateInfo = getAggregateFunction(functionCall);
        if (aggregateInfo == null) {
            return false;
        }
        finalFunctionCall = getAggregateLastFunctionCall(aggregateInfo, functionCall);
        if (finalFunctionCall == null) {
            return false;
        }
        
        // Variable details.
        mutableVariableExpresion = finalFunctionCall.getArguments().get(0);
        VariableReferenceExpression variableReference = (VariableReferenceExpression) mutableVariableExpresion
                .getValue();
        int variableId = variableReference.getVariableReference().getId();

        // Search for variable see if it is a aggregate sequence.
        AbstractLogicalOperator opSearch = (AbstractLogicalOperator) op.getInputs().get(0).getValue();
        opSearch = findSequenceAggregateOperator(opSearch, variableId);
        if (opSearch == null) {
            return false;
        }

        AggregateOperator aggregate = (AggregateOperator) opSearch;

        // Check to see if the expression is a function and sort-distinct-nodes-asc-or-atomics.
        ILogicalExpression logicalExpressionSearch = (ILogicalExpression) aggregate.getExpressions().get(0).getValue();
        if (logicalExpressionSearch.getExpressionTag() != LogicalExpressionTag.FUNCTION_CALL) {
            return false;
        }
        AbstractFunctionCallExpression functionCallSearch = (AbstractFunctionCallExpression) logicalExpressionSearch;
        if (!functionCallSearch.getFunctionIdentifier().equals(BuiltinOperators.SEQUENCE.getFunctionIdentifier())) {
            return false;
        }

        // Set the aggregate function to use new aggregate option.
        functionCallSearch.setFunctionInfo(aggregateInfo);

        // Alter arguments to include the aggregate arguments.
        finalFunctionCall.getArguments().get(0).setValue(functionCallSearch.getArguments().get(0).getValue());

        // Move the arguments for the assign function into aggregate. 
        functionCallSearch.getArguments().get(0).setValue(functionCall.getArguments().get(0).getValue());

        // Remove the aggregate assign, by creating a no op.
        assign.getExpressions().get(0).setValue(variableReference);

        // Add an assign operator to set up partitioning variable.
        // Create a new assign for a TRUE variable.
        LogicalVariable trueVar = context.newVar();
        IPointable p = (BooleanPointable) BooleanPointable.FACTORY.createPointable();
        XDMConstants.setTrue(p);
        VXQueryConstantValue cv = new VXQueryConstantValue(SequenceType.create(BuiltinTypeRegistry.XS_BOOLEAN,
                Quantifier.QUANT_ONE), p.getByteArray());
        AssignOperator trueAssignOp = new AssignOperator(trueVar, new MutableObject<ILogicalExpression>(
                new ConstantExpression(cv)));

        ILogicalOperator aggInput = aggregate.getInputs().get(0).getValue();
        aggregate.getInputs().get(0).setValue(trueAssignOp);
        trueAssignOp.getInputs().add(new MutableObject<ILogicalOperator>(aggInput));

        // Set partitioning variable.
        aggregate.setPartitioningVariable(trueVar);

        return true;
    }

    private AbstractLogicalOperator findSequenceAggregateOperator(AbstractLogicalOperator opSearch, int variableId) {
        while (true) {
            if (opSearch.getOperatorTag() == LogicalOperatorTag.AGGREGATE) {
                // Check for variable assignment and sequence.
                AggregateOperator aggregate = (AggregateOperator) opSearch;

                // Check to see if the expression is a function and sort-distinct-nodes-asc-or-atomics.
                ILogicalExpression logicalExpressionSearch = (ILogicalExpression) aggregate.getExpressions().get(0)
                        .getValue();
                if (logicalExpressionSearch.getExpressionTag() != LogicalExpressionTag.FUNCTION_CALL) {
                    opSearch = (AbstractLogicalOperator) opSearch.getInputs().get(0).getValue();
                    continue;
                }
                AbstractFunctionCallExpression functionCallSearch = (AbstractFunctionCallExpression) logicalExpressionSearch;
                if (!functionCallSearch.getFunctionIdentifier().equals(
                        BuiltinOperators.SEQUENCE.getFunctionIdentifier())) {
                    opSearch = (AbstractLogicalOperator) opSearch.getInputs().get(0).getValue();
                    continue;
                }

                // Only find operator for the following variable ID.
                if (variableId != aggregate.getVariables().get(0).getId()) {
                    opSearch = (AbstractLogicalOperator) opSearch.getInputs().get(0).getValue();
                    continue;
                }

                // Found the aggregate operator!!!
                return opSearch;
            } else if (opSearch.getOperatorTag() == LogicalOperatorTag.SUBPLAN) {
                // Run through subplan.
                SubplanOperator subplan = (SubplanOperator) opSearch;
                AbstractLogicalOperator opSubplan = (AbstractLogicalOperator) subplan.getNestedPlans().get(0)
                        .getRoots().get(0).getValue();
                AbstractLogicalOperator search = findSequenceAggregateOperator(opSubplan, variableId);
                if (search != null) {
                    return search;
                }
            }
            if (opSearch.getOperatorTag() != LogicalOperatorTag.EMPTYTUPLESOURCE
                    && opSearch.getOperatorTag() != LogicalOperatorTag.NESTEDTUPLESOURCE) {
                opSearch = (AbstractLogicalOperator) opSearch.getInputs().get(0).getValue();
            } else {
                break;
            }
        }
        if (opSearch.getOperatorTag() == LogicalOperatorTag.EMPTYTUPLESOURCE
                || opSearch.getOperatorTag() == LogicalOperatorTag.NESTEDTUPLESOURCE) {
            return null;
        }
        return opSearch;
    }

    @Override
    public boolean rewritePost(Mutable<ILogicalOperator> opRef, IOptimizationContext context) {
        return false;
    }
}
