/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.sql.validate;

import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.SqlSyntax;
import org.apache.calcite.sql.util.SqlBasicVisitor;
import org.apache.calcite.util.Util;

import static org.apache.calcite.sql.SqlKind.QUERY;

import com.google.common.collect.Lists;

import java.util.List;

/**
 * Visitor which looks for an aggregate function inside a tree of
 * {@link SqlNode} objects.
 */
class AggFinder extends SqlBasicVisitor<Void> {
  //~ Instance fields --------------------------------------------------------

  private final SqlOperatorTable opTab;

  /** Whether to find windowed aggregates. */
  private final boolean over;

  /** Whether to find regular (non-windowed) aggregates. */
  private boolean aggregate;

  private final AggFinder delegate;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates an AggFinder.
   *
   * @param opTab Operator table
   * @param over Whether to find windowed function calls {@code agg(x) OVER
   *             windowSpec}
   * @param aggregate Whether to find non-windowed aggregate calls
   * @param delegate Finder to which to delegate when processing the arguments
   *                  to a non-windowed aggregate
   */
  AggFinder(SqlOperatorTable opTab, boolean over, boolean aggregate,
      AggFinder delegate) {
    this.opTab = opTab;
    this.over = over;
    this.aggregate = aggregate;
    this.delegate = delegate;
  }

  //~ Methods ----------------------------------------------------------------

  /**
   * Finds an aggregate.
   *
   * @param node Parse tree to search
   * @return First aggregate function in parse tree, or null if not found
   */
  public SqlNode findAgg(SqlNode node) {
    try {
      node.accept(this);
      return null;
    } catch (Util.FoundOne e) {
      Util.swallow(e, null);
      return (SqlNode) e.getNode();
    }
  }

  public SqlNode findAgg(List<SqlNode> nodes) {
    try {
      for (SqlNode node : nodes) {
        node.accept(this);
      }
      return null;
    } catch (Util.FoundOne e) {
      Util.swallow(e, null);
      return (SqlNode) e.getNode();
    }
  }

  public Void visit(SqlCall call) {
    final SqlOperator operator = call.getOperator();
    // If nested aggregates disallowed or found an aggregate at invalid level
    if (operator.isAggregator() && !operator.requiresOver()) {
      if (delegate != null) {
        return operator.acceptCall(delegate, call);
      }
      if (aggregate) {
        throw new Util.FoundOne(call);
      }
    }
    // User-defined function may not be resolved yet.
    if (operator instanceof SqlFunction) {
      final SqlFunction sqlFunction = (SqlFunction) operator;
      if (sqlFunction.getFunctionType() == SqlFunctionCategory.USER_DEFINED_FUNCTION) {
        final List<SqlOperator> list = Lists.newArrayList();
        opTab.lookupOperatorOverloads(sqlFunction.getSqlIdentifier(),
            sqlFunction.getFunctionType(), SqlSyntax.FUNCTION, list);
        for (SqlOperator operator2 : list) {
          if (operator2.isAggregator() && !operator2.requiresOver()) {
            // If nested aggregates disallowed or found aggregate at invalid level
            if (aggregate) {
              throw new Util.FoundOne(call);
            }
          }
        }
      }
    }
    if (call.isA(QUERY)) {
      // don't traverse into queries
      return null;
    }
    if (call.getKind() == SqlKind.OVER) {
      if (over) {
        throw new Util.FoundOne(call);
      } else {
        // an aggregate function over a window is not an aggregate!
        return null;
      }
    }
    return super.visit(call);
  }
}

// End AggFinder.java
