package org.triplea.tree;

import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Enumeration;
import java.util.List;
import java.util.NoSuchElementException;
import javax.annotation.Nullable;

/**
 * Minimal replacement for {@code javax.swing.tree.DefaultMutableTreeNode} that has no dependency on
 * Swing, so game history can be used on Android.
 */
public class DefaultMutableTreeNode implements TreeNode, Cloneable, Serializable {
  private static final long serialVersionUID = 1L;

  @Nullable private DefaultMutableTreeNode parent;
  private List<DefaultMutableTreeNode> children = new ArrayList<>();
  private transient Object userObject;
  private final boolean allowsChildren;

  public DefaultMutableTreeNode() {
    this(null, true);
  }

  public DefaultMutableTreeNode(final Object userObject) {
    this(userObject, true);
  }

  public DefaultMutableTreeNode(final Object userObject, final boolean allowsChildren) {
    this.userObject = userObject;
    this.allowsChildren = allowsChildren;
  }

  public void add(final DefaultMutableTreeNode child) {
    insert(child, children.size());
  }

  public void insert(final DefaultMutableTreeNode child, final int index) {
    if (!allowsChildren) {
      throw new IllegalStateException("node does not allow children");
    }
    if (child.parent != null) {
      child.parent.remove(child);
    }
    child.parent = this;
    children.add(index, child);
  }

  public void remove(final int index) {
    final DefaultMutableTreeNode child = children.remove(index);
    child.parent = null;
  }

  public void remove(final DefaultMutableTreeNode child) {
    final int index = children.indexOf(child);
    if (index >= 0) {
      remove(index);
    }
  }

  public void removeFromParent() {
    if (parent != null) {
      parent.remove(this);
    }
  }

  public void removeAllChildren() {
    for (final DefaultMutableTreeNode child : children) {
      child.parent = null;
    }
    children.clear();
  }

  @Override
  public DefaultMutableTreeNode getChildAt(final int childIndex) {
    return children.get(childIndex);
  }

  @Override
  public int getChildCount() {
    return children.size();
  }

  @Override
  @Nullable
  public DefaultMutableTreeNode getParent() {
    return parent;
  }

  @Override
  public int getIndex(final TreeNode node) {
    return children.indexOf(node);
  }

  @Override
  public boolean isLeaf() {
    return children.isEmpty();
  }

  public boolean isRoot() {
    return parent == null;
  }

  public boolean getAllowsChildren() {
    return allowsChildren;
  }

  @Override
  public Enumeration<DefaultMutableTreeNode> children() {
    return Collections.enumeration(new ArrayList<>(children));
  }

  /** Snapshot list of the children, safe to iterate while the tree changes. */
  public List<DefaultMutableTreeNode> childList() {
    return new ArrayList<>(children);
  }

  public DefaultMutableTreeNode getFirstChild() {
    if (children.isEmpty()) {
      throw new NoSuchElementException("node has no children");
    }
    return children.get(0);
  }

  public DefaultMutableTreeNode getLastChild() {
    if (children.isEmpty()) {
      throw new NoSuchElementException("node has no children");
    }
    return children.get(children.size() - 1);
  }

  public Object getUserObject() {
    return userObject;
  }

  public void setUserObject(final Object userObject) {
    this.userObject = userObject;
  }

  public DefaultMutableTreeNode getRoot() {
    DefaultMutableTreeNode node = this;
    while (node.parent != null) {
      node = node.parent;
    }
    return node;
  }

  /** Path from the root down to and including this node. */
  public List<DefaultMutableTreeNode> getPath() {
    final Deque<DefaultMutableTreeNode> path = new ArrayDeque<>();
    DefaultMutableTreeNode node = this;
    while (node != null) {
      path.addFirst(node);
      node = node.parent;
    }
    return new ArrayList<>(path);
  }

  public int getLevel() {
    int level = 0;
    DefaultMutableTreeNode node = parent;
    while (node != null) {
      level++;
      node = node.parent;
    }
    return level;
  }

  @Nullable
  public DefaultMutableTreeNode getPreviousSibling() {
    if (parent == null) {
      return null;
    }
    final int index = parent.getIndex(this);
    return index > 0 ? parent.getChildAt(index - 1) : null;
  }

  @Nullable
  public DefaultMutableTreeNode getNextSibling() {
    if (parent == null) {
      return null;
    }
    final int index = parent.getIndex(this);
    return index < parent.getChildCount() - 1 ? parent.getChildAt(index + 1) : null;
  }

  /** Returns the node that precedes this node in a preorder traversal, or null for the root. */
  @Nullable
  public DefaultMutableTreeNode getPreviousNode() {
    if (parent == null) {
      return null;
    }
    final DefaultMutableTreeNode previousSibling = getPreviousSibling();
    return previousSibling != null ? previousSibling.getLastLeaf() : parent;
  }

  /** Returns the last leaf that comes before this node in preorder traversal, or null. */
  @Nullable
  public DefaultMutableTreeNode getPreviousLeaf() {
    DefaultMutableTreeNode node = this;
    while (node.parent != null) {
      final DefaultMutableTreeNode previousSibling = node.getPreviousSibling();
      if (previousSibling != null) {
        return previousSibling.getLastLeaf();
      }
      node = node.parent;
    }
    return null;
  }

  @Nullable
  public DefaultMutableTreeNode getNextLeaf() {
    DefaultMutableTreeNode node = this;
    while (node.parent != null) {
      final DefaultMutableTreeNode nextSibling = node.getNextSibling();
      if (nextSibling != null) {
        return nextSibling.getFirstLeaf();
      }
      node = node.parent;
    }
    return null;
  }

  public DefaultMutableTreeNode getFirstLeaf() {
    DefaultMutableTreeNode node = this;
    while (!node.isLeaf()) {
      node = node.getFirstChild();
    }
    return node;
  }

  public DefaultMutableTreeNode getLastLeaf() {
    DefaultMutableTreeNode node = this;
    while (!node.isLeaf()) {
      node = node.getLastChild();
    }
    return node;
  }

  /** Preorder (depth first, parents before children) enumeration starting with this node. */
  public Enumeration<DefaultMutableTreeNode> preorderEnumeration() {
    final Deque<DefaultMutableTreeNode> stack = new ArrayDeque<>();
    stack.push(this);
    return new Enumeration<>() {
      @Override
      public boolean hasMoreElements() {
        return !stack.isEmpty();
      }

      @Override
      public DefaultMutableTreeNode nextElement() {
        final DefaultMutableTreeNode node = stack.pop();
        for (int i = node.children.size() - 1; i >= 0; i--) {
          stack.push(node.children.get(i));
        }
        return node;
      }
    };
  }

  /** Shallow copy: same user object, no parent, no children (as in Swing). */
  @Override
  public DefaultMutableTreeNode clone() {
    try {
      final DefaultMutableTreeNode copy = (DefaultMutableTreeNode) super.clone();
      copy.parent = null;
      copy.children = new ArrayList<>();
      return copy;
    } catch (final CloneNotSupportedException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public String toString() {
    return userObject == null ? "" : userObject.toString();
  }
}
