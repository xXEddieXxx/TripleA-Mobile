package org.triplea.tree;

import java.io.Serializable;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Minimal replacement for {@code javax.swing.tree.DefaultTreeModel}. Holds the root node and
 * notifies listeners about structural changes so a UI can observe the history tree.
 */
public class DefaultTreeModel implements Serializable {
  private static final long serialVersionUID = 1L;

  /** Listener for structural changes of the tree. */
  public interface Listener {
    void nodesInserted(DefaultMutableTreeNode parent, int[] childIndices);

    void nodesRemoved(DefaultMutableTreeNode parent, int[] childIndices, Object[] removed);

    void nodeChanged(DefaultMutableTreeNode node);
  }

  private final DefaultMutableTreeNode root;
  private transient List<Listener> listeners = new CopyOnWriteArrayList<>();

  public DefaultTreeModel(final DefaultMutableTreeNode root) {
    this.root = root;
  }

  public DefaultMutableTreeNode getRoot() {
    return root;
  }

  private List<Listener> listeners() {
    if (listeners == null) {
      listeners = new CopyOnWriteArrayList<>();
    }
    return listeners;
  }

  public void addListener(final Listener listener) {
    listeners().add(listener);
  }

  public void removeListener(final Listener listener) {
    listeners().remove(listener);
  }

  public void insertNodeInto(
      final DefaultMutableTreeNode newChild, final DefaultMutableTreeNode parent, final int index) {
    parent.insert(newChild, index);
    nodesWereInserted(parent, new int[] {index});
  }

  public void removeNodeFromParent(final DefaultMutableTreeNode node) {
    final DefaultMutableTreeNode parent = node.getParent();
    if (parent == null) {
      throw new IllegalArgumentException("node does not have a parent.");
    }
    final int index = parent.getIndex(node);
    parent.remove(index);
    nodesWereRemoved(parent, new int[] {index}, new Object[] {node});
  }

  public void nodesWereInserted(final DefaultMutableTreeNode parent, final int[] childIndices) {
    for (final Listener listener : listeners()) {
      listener.nodesInserted(parent, childIndices);
    }
  }

  public void nodesWereRemoved(
      final DefaultMutableTreeNode parent, final int[] childIndices, final Object[] removed) {
    for (final Listener listener : listeners()) {
      listener.nodesRemoved(parent, childIndices, removed);
    }
  }

  public void nodeChanged(final DefaultMutableTreeNode node) {
    for (final Listener listener : listeners()) {
      listener.nodeChanged(node);
    }
  }
}
