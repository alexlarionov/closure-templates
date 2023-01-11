/*
 * Copyright 2020 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.template.soy.exprtree;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.template.soy.base.internal.QuoteStyle;
import com.google.template.soy.basetree.CopyState;
import com.google.template.soy.exprtree.OperatorNodes.AssertNonNullOpNode;
import com.google.template.soy.internal.util.TreeStreams;
import java.util.List;

/**
 * Represents a null safe access: {@code ?.} or {@code ?[]}.
 *
 * <p>This works by splitting the base expression from the data access. The data access should only
 * be performed if the base expression is nonnull. Note that because the AST requires a base
 * expression for a data access, a placeholder {@link GlobalNode} is used. Consumers of {@code
 * NullSafeAccessNode}s should never actually use this placeholder {@link GlobalNode}, but should
 * instead use the base expression of the {@code NullSafeAccessNode}.
 *
 * <p>This is a better representation of the control flow of a null safe access since it puts
 * earlier accesses closer to the root of the AST. For example, for {@code $p?.a?.b} the access
 * chain with {@link NullSafeAccessNode}s has the {@code .a} access as the parent of the {@code .b}
 * access. This makes it easier to calculate the type of a null safe access (because {@code $p?.a.b}
 * should be nullable even if the {@code .b} access is nonnull.
 */
public final class NullSafeAccessNode extends AbstractParentExprNode {

  private static final String BASE_PLACEHOLDER_VALUE = "DO_NOT_USE__NULL_SAFE_ACCESS";

  private NullSafeAccessNode(ExprNode base, AccessChainComponentNode access) {
    super(access.getSourceLocation());
    addChild(base);
    addChild(access);
  }

  private NullSafeAccessNode(NullSafeAccessNode orig, CopyState copyState) {
    super(orig, copyState);
  }

  @Override
  public Kind getKind() {
    return Kind.NULL_SAFE_ACCESS_NODE;
  }

  @Override
  public String toSourceString() {
    StringBuilder sourceString = new StringBuilder(getBase().toSourceString());
    ExprNode dataAccess = getDataAccess();
    while (dataAccess.getKind() == Kind.NULL_SAFE_ACCESS_NODE) {
      NullSafeAccessNode node = (NullSafeAccessNode) dataAccess;
      accumulateDataAccess(sourceString, (AccessChainComponentNode) node.getBase());
      dataAccess = node.getDataAccess();
    }
    accumulateDataAccess(sourceString, (AccessChainComponentNode) dataAccess);
    return sourceString.toString();
  }

  private static void accumulateDataAccess(
      StringBuilder accumulator, AccessChainComponentNode node) {
    StringBuilder dataAccessChain = new StringBuilder();
    ExprNode child = node;
    while (child instanceof AccessChainComponentNode) {
      if (child instanceof DataAccessNode) {
        DataAccessNode dataAccessNode = (DataAccessNode) child;
        dataAccessChain.insert(0, dataAccessNode.getSourceStringSuffix());
        child = dataAccessNode.getBaseExprChild();
      } else {
        AssertNonNullOpNode assertNonNullOpNode = (AssertNonNullOpNode) child;
        dataAccessChain.insert(0, assertNonNullOpNode.getOperator().getTokenString());
        child = assertNonNullOpNode.getChild(0);
      }
    }
    accumulator.append('?').append(dataAccessChain);
  }

  @Override
  public ExprNode copy(CopyState copyState) {
    return new NullSafeAccessNode(this, copyState);
  }

  /** Gets the base expression of this null safe access. */
  public ExprNode getBase() {
    return getChild(0);
  }

  /**
   * Gets the data access of this null safe access. This could be another {@link NullSafeAccessNode}
   * in the case of a null safe access chain. Or this could be a {@link DataAccessNode} with a
   * placeholder base, as described above.
   */
  public ExprNode getDataAccess() {
    return getChild(1);
  }

  public static boolean isPlaceholder(ExprNode node) {
    return (node.getKind() == Kind.STRING_NODE
            && ((StringNode) node).getValue().equals(BASE_PLACEHOLDER_VALUE))
        || (node.getKind() == Kind.GROUP_NODE && ((GroupNode) node).isNullSafeAccessPlaceHolder());
  }

  /**
   * Creates a {@code NullSafeAccessNode} from the given (null safe) {@link DataAccessNode} and
   * inserts it into the correct place of the AST.
   *
   * <p>Note that to convert a null-safe access chain to {code NullSafeAccessNode}s, this must be
   * called on the deepest node first, then the shallower nodes. For example, for {@code
   * $p?.a?.b?.c}, this must first be called on the {@code .a} access, then {@code .b}, then {@code
   * .c}.
   */
  public static void createAndInsert(
      DataAccessNode node, AccessChainComponentNode accessChainRoot) {
    checkArgument(node.isNullSafe());
    ExprNode base = node.getBaseExprChild();
    // TODO(spishak): Find a better way to represent this placeholder node, likely by removing it
    // from the AST.
    ExprNode basePlaceholder =
        new StringNode(BASE_PLACEHOLDER_VALUE, QuoteStyle.DOUBLE, base.getSourceLocation());

    DataAccessNode child;
    switch (node.getKind()) {
      case FIELD_ACCESS_NODE:
        child =
            new FieldAccessNode(
                basePlaceholder,
                ((FieldAccessNode) node).getFieldName(),
                node.getAccessSourceLocation(),
                /* isNullSafe= */ false);
        break;
      case ITEM_ACCESS_NODE:
        child =
            new ItemAccessNode(
                basePlaceholder,
                ((ItemAccessNode) node).getKeyExprChild(),
                node.getAccessSourceLocation(),
                /* isNullSafe= */ false);
        break;
      case METHOD_CALL_NODE:
        MethodCallNode childMethodCall =
            MethodCallNode.newWithPositionalArgs(
                basePlaceholder,
                node.getChildren().subList(1, node.numChildren()),
                ((MethodCallNode) node).getMethodName(),
                node.getAccessSourceLocation(),
                /* isNullSafe= */ false);
        if (((MethodCallNode) node).isMethodResolved()) {
          childMethodCall.setSoyMethod(((MethodCallNode) node).getSoyMethod());
        }
        child = childMethodCall;
        break;
      default:
        throw new AssertionError(node.getKind());
    }

    ParentExprNode accessChainParent = accessChainRoot.getParent();
    int rootIndex = accessChainParent.getChildIndex(accessChainRoot);
    NullSafeAccessNode nullSafe;
    if (node == accessChainRoot) {
      nullSafe = new NullSafeAccessNode(base, child);
      accessChainParent.removeChild(rootIndex);
    } else {
      node.getParent().replaceChild(node, child);
      nullSafe = new NullSafeAccessNode(base, accessChainRoot);
    }
    accessChainParent.addChild(rootIndex, nullSafe);
  }

  /**
   * Returns a copy of this root NullSafeAccessNode, transformed back into the original
   * DataAccessNode that the Soy parser produced. This is the inverse AST operation as {@link
   * com.google.template.soy.passes.NullSafeAccessPass}.
   *
   * <p>The returned view of the AST may make some calculations easier, at the cost of copying the
   * AST.
   */
  public DataAccessNode asDataAccessNode() {
    // Clone entire branch so we can modify without worrying.
    NullSafeAccessNode nsan = (NullSafeAccessNode) this.copy(new CopyState());

    Preconditions.checkState(!(nsan.getParent() instanceof NullSafeAccessNode));

    ExprNode base = nsan.getBase();
    Preconditions.checkState(!NullSafeAccessNode.isPlaceholder(base));

    // Collect all the nodes with placeholder base expressions, in breadth first order. The last
    // of these is the root of the tree once we have restored the base expressions.
    ImmutableList<DataAccessNode> bfNodes =
        TreeStreams.depthFirst(nsan, NullSafeAccessNode::dataAccessSuccessors)
            .filter(DataAccessNode.class::isInstance)
            .map(DataAccessNode.class::cast)
            .filter(n -> NullSafeAccessNode.isPlaceholder(n.getBaseExprChild()))
            .collect(toImmutableList());
    Preconditions.checkState(!bfNodes.isEmpty());

    for (DataAccessNode node : bfNodes) {
      // Fix the base expression and set to null safe node. Safe since we've cloned the tree.
      node.setNullSafe(true);
      node.replaceChild(0, base);

      base = node;
      // Walk up any direct ancestors that are data access nodes (not null safe nodes).
      while (base.getParent() instanceof DataAccessNode) {
        base = base.getParent();
      }
    }
    return (DataAccessNode) base;
  }

  /**
   * We need special traversal rules so that we don't accidentally traverse into method parameters
   * and other parts of the subtree that could contain independent null safe chains.
   */
  private static List<ExprNode> dataAccessSuccessors(ExprNode node) {
    switch (node.getKind()) {
      case NULL_SAFE_ACCESS_NODE:
        return ((NullSafeAccessNode) node).getChildren();
      case FIELD_ACCESS_NODE:
      case METHOD_CALL_NODE:
      case ITEM_ACCESS_NODE:
        return ImmutableList.of(((DataAccessNode) node).getBaseExprChild());
      default:
        return ImmutableList.of();
    }
  }
}
