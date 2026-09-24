package org.triplea.tree;

import java.util.Enumeration;

/** Minimal replacement for {@code javax.swing.tree.TreeNode}. */
public interface TreeNode {
  TreeNode getChildAt(int childIndex);

  int getChildCount();

  TreeNode getParent();

  int getIndex(TreeNode node);

  boolean isLeaf();

  Enumeration<? extends TreeNode> children();
}
