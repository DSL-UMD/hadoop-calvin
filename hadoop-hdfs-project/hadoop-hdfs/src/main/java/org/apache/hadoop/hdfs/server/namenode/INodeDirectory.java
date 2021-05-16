/**
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
package org.apache.hadoop.hdfs.server.namenode;

import static org.apache.hadoop.hdfs.protocol.HdfsConstants.BLOCK_STORAGE_POLICY_ID_UNSPECIFIED;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Queue;
import java.util.LinkedList;
import java.util.Set;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.hadoop.fs.PathIsNotDirectoryException;
import org.apache.hadoop.fs.StorageType;
import org.apache.hadoop.fs.XAttr;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.fs.permission.FsAction;
import org.apache.hadoop.fs.permission.PermissionStatus;
import org.apache.hadoop.hdfs.DFSUtil;
import org.apache.hadoop.hdfs.db.*;
import org.apache.hadoop.hdfs.protocol.SnapshotException;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockStoragePolicySuite;
import org.apache.hadoop.hdfs.server.namenode.snapshot.DirectorySnapshottableFeature;
import org.apache.hadoop.hdfs.server.namenode.snapshot.DirectoryWithSnapshotFeature;
import org.apache.hadoop.hdfs.server.namenode.snapshot.DirectoryWithSnapshotFeature.DirectoryDiffList;
import org.apache.hadoop.hdfs.server.namenode.snapshot.Snapshot;
import org.apache.hadoop.hdfs.protocolPB.PBHelperClient;
import org.apache.hadoop.hdfs.server.namenode.FsImageProto.FileSummary;
import org.apache.hadoop.hdfs.server.namenode.FsImageProto.FilesUnderConstructionSection.FileUnderConstructionEntry;
import org.apache.hadoop.hdfs.server.namenode.FsImageProto.INodeDirectorySection;
import org.apache.hadoop.hdfs.server.namenode.FsImageProto.NamespaceSubtree;
import org.apache.hadoop.hdfs.server.namenode.FsImageProto.INodeSection;
import org.apache.hadoop.hdfs.server.namenode.FsImageProto.INodeSection.AclFeatureProto;
import org.apache.hadoop.hdfs.server.namenode.FsImageProto.INodeSection.XAttrCompactProto;
import org.apache.hadoop.hdfs.server.namenode.FsImageProto.INodeSection.XAttrFeatureProto;
import org.apache.hadoop.hdfs.server.namenode.FsImageProto.INodeSection.QuotaByStorageTypeEntryProto;
import org.apache.hadoop.hdfs.server.namenode.FsImageProto.INodeSection.QuotaByStorageTypeFeatureProto;
import org.apache.hadoop.hdfs.util.ReadOnlyList;
import org.apache.hadoop.hdfs.db.*;

import org.apache.hadoop.hdfs.cuckoofilter4j.*;
import org.apache.hadoop.hdfs.cuckoofilter4j.Utils.Algorithm;

import com.google.common.hash.Funnels;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import org.apache.hadoop.security.AccessControlException;
import org.apache.hadoop.hdfs.nnproxy.tools.LookupMount;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockInfo;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.protocol.Block;
import static org.apache.hadoop.hdfs.server.namenode.FSImageFormatPBINode.Saver.buildAclEntries;
import static org.apache.hadoop.hdfs.server.namenode.FSImageFormatPBINode.Saver.buildXAttrs;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import com.google.protobuf.ByteString;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.ipc.RPC;

import org.apache.ignite.*;
import org.apache.ignite.lang.IgniteClosure;
import org.apache.ignite.binary.BinaryObject;
import org.apache.ignite.binary.BinaryObjectBuilder;
import org.apache.hadoop.hdfs.db.ignite.BatchUpdateINodes;
import org.apache.hadoop.hdfs.db.ignite.RenamePayload;
import org.apache.hadoop.hdfs.db.ignite.RenameSubtreeINodes;

/**
 * Directory INode class.
 */
public class INodeDirectory extends INodeWithAdditionalFields
    implements INodeDirectoryAttributes {

  /** Cast INode to INodeDirectory. */
  public static INodeDirectory valueOf(INode inode, Object path
      ) throws FileNotFoundException, PathIsNotDirectoryException {
    if (inode == null) {
      throw new FileNotFoundException("Directory does not exist: "
          + DFSUtil.path2String(path));
    }
    if (!inode.isDirectory()) {
      throw new PathIsNotDirectoryException(DFSUtil.path2String(path));
    }
    return inode.asDirectory(); 
  }

  // Profiling shows that most of the file lists are between 1 and 4 elements.
  // Thus allocate the corresponding ArrayLists with a small initial capacity.
  public static final int DEFAULT_FILES_PER_DIRECTORY = 2;

  static final byte[] ROOT_NAME = DFSUtil.string2Bytes("");

  private HashSet<String> children = new HashSet<>();

  // public CuckooFilter<CharSequence> filter;

  /** constructor */
  public INodeDirectory(long id, byte[] name, PermissionStatus permissions,
      long mtime, String parentName) {
    super(id, name, permissions, mtime, 0L, 0L, parentName);
  }

  // public CuckooFilter<CharSequence> getFilter() {
  //   if (filter == null) {
  //     filter = FSDirectory.getInstance().borrowFilter();
  //   }
  //   return filter;
  // }

  public void updateINodeDirectory() {
    super.updateINode(0L);
  }

  public void renameINodeDirectory() {
    CompletableFuture.runAsync(() -> {
      DatabaseINode.renameInode(
          getId(),
          getParentId(),
          getLocalName(),
          getAccessTime(),
          getModificationTime(),
          getPermissionLong(),
          0L,
          getParentName());
      }, Database.getInstance().getExecutorService());
  }

  public INodeDirectory copyINodeDirectory() {
    INodeDirectory inode = new INodeDirectory(getId());
    inode.InitINodeDirectory(
        getParent(),
        getId(),
        getLocalNameBytes(),
        getPermissionStatus(),
        getModificationTime(),
        getAccessTime(),
        getParentName());
    return inode;
  }

  public void InitINodeDirectory(
      INode parent, long id, byte[] name, PermissionStatus permissions, long mtime, long atime, String parentName) {
    super.InitINodeWithAdditionalFields(parent, id, name, permissions, mtime, atime, parentName);
  }

  public void InitINodeDirectory(
      long parent, long id, byte[] name, long permissions, long mtime, long atime, long header, String parentName) {
    super.InitINodeWithAdditionalFields(parent, id, name, permissions, mtime, atime, header, parentName);
  }

  public INodeDirectory(INode parent, long id, byte[] name, PermissionStatus permissions,
      long mtime, String parentName) {
    super(parent, id, name, permissions, mtime, 0L, parentName);
  }

  // Note: only used by the loader of image file
  public INodeDirectory(long id) {
    super(id);
    // FIXME: filter should be recovered from zookeeper or db.
  }

  /**
   * Copy constructor
   * @param other The INodeDirectory to be copied
   * @param adopt Indicate whether or not need to set the parent field of child
   *              INodes to the new node
   * @param featuresToCopy any number of features to copy to the new node.
   *              The method will do a reference copy, not a deep copy.
   */
  public INodeDirectory(INodeDirectory other, boolean adopt,
      Feature... featuresToCopy) {
    super(other);
    // filter = other.filter.copy();
    final ReadOnlyList<INode> children = other.getCurrentChildrenList();
    if (adopt && children != null) {
      for (INode child : children) {
        child.setParent(this);
      }
    }
    // FIXME: change later
    // this.features = featuresToCopy;
    // AclFeature aclFeature = getFeature(AclFeature.class);
    // if (aclFeature != null) {
    //   // for the de-duplication of AclFeature
    //   removeFeature(aclFeature);
    //   addFeature(AclStorage.addAclFeature(aclFeature));
    // }
  }

  /** @return true unconditionally. */
  @Override
  public final boolean isDirectory() {
    return true;
  }

  /** @return this object. */
  @Override
  public final INodeDirectory asDirectory() {
    return this;
  }

  @Override
  public byte getLocalStoragePolicyID() {
    XAttrFeature f = getXAttrFeature();
    XAttr xattr =
        f == null ? null : f.getXAttr(BlockStoragePolicySuite.getStoragePolicyXAttrPrefixedName());
    if (xattr != null) {
      return (xattr.getValue())[0];
    }
    return BLOCK_STORAGE_POLICY_ID_UNSPECIFIED;
  }

  @Override
  public byte getStoragePolicyID() {
    // FIXME: only support unspecify policy for now
    // byte id = getLocalStoragePolicyID();
    // if (id != BLOCK_STORAGE_POLICY_ID_UNSPECIFIED) {
    //   return id;
    // }
    // if it is unspecified, check its parent
    // return getParent() != null ? getParent().getStoragePolicyID() :
    // BLOCK_STORAGE_POLICY_ID_UNSPECIFIED;
    return getLocalStoragePolicyID();
  }

  void setQuota(BlockStoragePolicySuite bsps, long nsQuota, long ssQuota, StorageType type) {
    DirectoryWithQuotaFeature quota = getDirectoryWithQuotaFeature();
    if (quota != null) {
      // already has quota; so set the quota to the new values
      if (type != null) {
        quota.setQuota(ssQuota, type);
      } else {
        quota.setQuota(nsQuota, ssQuota);
      }
      if (!isQuotaSet() && !isRoot()) {
        removeFeature(quota);
      }
    } else {
      final QuotaCounts c = computeQuotaUsage(bsps);
      DirectoryWithQuotaFeature.Builder builder =
          new DirectoryWithQuotaFeature.Builder().nameSpaceQuota(nsQuota);
      if (type != null) {
        builder.typeQuota(type, ssQuota);
      } else {
        builder.storageSpaceQuota(ssQuota);
      }
      addDirectoryWithQuotaFeature(builder.build()).setSpaceConsumed(c);
    }
  }

  @Override
  public QuotaCounts getQuotaCounts() {
    final DirectoryWithQuotaFeature q = getDirectoryWithQuotaFeature();
    return q != null ? q.getQuota() : super.getQuotaCounts();
  }

  @Override
  public void addSpaceConsumed(QuotaCounts counts) {
    super.addSpaceConsumed(counts);

    final DirectoryWithQuotaFeature q = getDirectoryWithQuotaFeature();
    if (q != null && isQuotaSet()) {
      q.addSpaceConsumed2Cache(counts);
    }
  }

  /**
   * If the directory contains a {@link DirectoryWithQuotaFeature}, return it;
   * otherwise, return null.
   */
  public final DirectoryWithQuotaFeature getDirectoryWithQuotaFeature() {
    return getFeature(DirectoryWithQuotaFeature.class);
  }

  /** Is this directory with quota? */
  final boolean isWithQuota() {
    return getDirectoryWithQuotaFeature() != null;
  }

  DirectoryWithQuotaFeature addDirectoryWithQuotaFeature(
      DirectoryWithQuotaFeature q) {
    Preconditions.checkState(!isWithQuota(), "Directory is already with quota");
    addFeature(q);
    return q;
  }

  // int searchChildren(byte[] name) {
  //   return children == null? -1: Collections.binarySearch(children, name);
  // }
  
  public DirectoryWithSnapshotFeature addSnapshotFeature(
      DirectoryDiffList diffs) {
    Preconditions.checkState(!isWithSnapshot(), 
        "Directory is already with snapshot");
    DirectoryWithSnapshotFeature sf = new DirectoryWithSnapshotFeature(diffs);
    addFeature(sf);
    return sf;
  }

  /**
   * If feature list contains a {@link DirectoryWithSnapshotFeature}, return it;
   * otherwise, return null.
   */
  public final DirectoryWithSnapshotFeature getDirectoryWithSnapshotFeature() {
    return getFeature(DirectoryWithSnapshotFeature.class);
  }

  /** Is this file has the snapshot feature? */
  public final boolean isWithSnapshot() {
    return getDirectoryWithSnapshotFeature() != null;
  }

  public DirectoryDiffList getDiffs() {
    DirectoryWithSnapshotFeature sf = getDirectoryWithSnapshotFeature();
    return sf != null ? sf.getDiffs() : null;
  }

  @Override
  public INodeDirectoryAttributes getSnapshotINode(int snapshotId) {
    DirectoryWithSnapshotFeature sf = getDirectoryWithSnapshotFeature();
    return sf == null ? this : sf.getDiffs().getSnapshotINode(snapshotId, this);
  }

  @Override
  public String toDetailString() {
    DirectoryWithSnapshotFeature sf = this.getDirectoryWithSnapshotFeature();
    return super.toDetailString() + (sf == null ? "" : ", " + sf.getDiffs());
  }

  public DirectorySnapshottableFeature getDirectorySnapshottableFeature() {
    return getFeature(DirectorySnapshottableFeature.class);
  }

  public boolean isSnapshottable() {
    return getDirectorySnapshottableFeature() != null;
  }

  /**
   * Check if this directory is a descendant directory
   * of a snapshot root directory.
   * @param snapshotRootDir the snapshot root directory
   * @return true if this directory is a descendant of snapshot root
   */
  public boolean isDescendantOfSnapshotRoot(INodeDirectory snapshotRootDir) {
    Preconditions.checkArgument(snapshotRootDir.isSnapshottable());
    INodeDirectory dir = this;
    while(dir != null) {
      if (dir.equals(snapshotRootDir)) {
        return true;
      }
      dir = dir.getParent();
    }
    return false;
  }

  public Snapshot getSnapshot(byte[] snapshotName) {
    return getDirectorySnapshottableFeature().getSnapshot(snapshotName);
  }

  public void setSnapshotQuota(int snapshotQuota) {
    getDirectorySnapshottableFeature().setSnapshotQuota(snapshotQuota);
  }

  public Snapshot addSnapshot(int id, String name,
      final LeaseManager leaseManager, final boolean captureOpenFiles,
      int maxSnapshotLimit)
      throws SnapshotException {
    return getDirectorySnapshottableFeature().addSnapshot(this, id, name,
        leaseManager, captureOpenFiles, maxSnapshotLimit);
  }

  public Snapshot removeSnapshot(
      ReclaimContext reclaimContext, String snapshotName)
      throws SnapshotException {
    return getDirectorySnapshottableFeature().removeSnapshot(
        reclaimContext, this, snapshotName);
  }

  public void renameSnapshot(String path, String oldName, String newName)
      throws SnapshotException {
    getDirectorySnapshottableFeature().renameSnapshot(path, oldName, newName);
  }

  /** add DirectorySnapshottableFeature */
  public void addSnapshottableFeature() {
    Preconditions.checkState(!isSnapshottable(),
        "this is already snapshottable, this=%s", this);
    DirectoryWithSnapshotFeature s = this.getDirectoryWithSnapshotFeature();
    final DirectorySnapshottableFeature snapshottable =
        new DirectorySnapshottableFeature(s);
    if (s != null) {
      this.removeFeature(s);
    }
    this.addFeature(snapshottable);
  }

  /** remove DirectorySnapshottableFeature */
  public void removeSnapshottableFeature() {
    DirectorySnapshottableFeature s = getDirectorySnapshottableFeature();
    Preconditions.checkState(s != null,
        "The dir does not have snapshottable feature: this=%s", this);
    this.removeFeature(s);
    if (s.getDiffs().asList().size() > 0) {
      // add a DirectoryWithSnapshotFeature back
      DirectoryWithSnapshotFeature sf = new DirectoryWithSnapshotFeature(
          s.getDiffs());
      addFeature(sf);
    }
  }

  /** 
   * Replace the given child with a new child. Note that we no longer need to
   * replace an normal INodeDirectory or INodeFile into an
   * INodeDirectoryWithSnapshot or INodeFileUnderConstruction. The only cases
   * for child replacement is for reference nodes.
   */
  public void replaceChild(INode oldChild, final INode newChild,
      final INodeMap inodeMap) {
    /*
    Preconditions.checkNotNull(children);
    final int i = searchChildren(newChild.getLocalNameBytes());
    Preconditions.checkState(i >= 0);
    Preconditions.checkState(oldChild == children.get(i)
        || oldChild == children.get(i).asReference().getReferredINode()
            .asReference().getReferredINode());
    oldChild = children.get(i);

    if (oldChild.isReference() && newChild.isReference()) {
      // both are reference nodes, e.g., DstReference -> WithName
      final INodeReference.WithCount withCount =
          (WithCount) oldChild.asReference().getReferredINode();
      withCount.removeReference(oldChild.asReference());
    }
    children.set(i, newChild);
    */
    newChild.setParent(getId());

    // replace the instance in the created list of the diff list
    DirectoryWithSnapshotFeature sf = this.getDirectoryWithSnapshotFeature();
    if (sf != null) {
      sf.getDiffs().replaceCreatedChild(oldChild, newChild);
    }

    // update the inodeMap
    if (inodeMap != null) {
      inodeMap.put(newChild);
    }
  }

  INodeReference.WithName replaceChild4ReferenceWithName(INode oldChild,
      int latestSnapshotId) {
    Preconditions.checkArgument(latestSnapshotId != Snapshot.CURRENT_STATE_ID);
    if (oldChild instanceof INodeReference.WithName) {
      return (INodeReference.WithName)oldChild;
    }

    final INodeReference.WithCount withCount;
    if (oldChild.isReference()) {
      Preconditions.checkState(oldChild instanceof INodeReference.DstReference);
      withCount = (INodeReference.WithCount) oldChild.asReference()
          .getReferredINode();
    } else {
      withCount = new INodeReference.WithCount(null, oldChild);
    }
    final INodeReference.WithName ref = new INodeReference.WithName(this,
        withCount, oldChild.getLocalNameBytes(), latestSnapshotId);
    replaceChild(oldChild, ref, null);
    return ref;
  }

  @Override
  public void recordModification(int latestSnapshotId) {
    if (isInLatestSnapshot(latestSnapshotId)
        && !shouldRecordInSrcSnapshot(latestSnapshotId)) {
      // add snapshot feature if necessary
      DirectoryWithSnapshotFeature sf = getDirectoryWithSnapshotFeature();
      if (sf == null) {
        sf = addSnapshotFeature(null);
      }
      // record self in the diff list if necessary
      sf.getDiffs().saveSelf2Snapshot(latestSnapshotId, this, null);
    }
  }

  /**
   * Save the child to the latest snapshot.
   * 
   * @return the child inode, which may be replaced.
   */
  public INode saveChild2Snapshot(final INode child, final int latestSnapshotId,
      final INode snapshotCopy) {
    if (latestSnapshotId == Snapshot.CURRENT_STATE_ID) {
      return child;
    }
    
    // add snapshot feature if necessary
    DirectoryWithSnapshotFeature sf = getDirectoryWithSnapshotFeature();
    if (sf == null) {
      sf = this.addSnapshotFeature(null);
    }
    return sf.saveChild2Snapshot(this, child, latestSnapshotId, snapshotCopy);
  }

  /**
   * @param name the name of the child
   * @param snapshotId
   *          if it is not {@link Snapshot#CURRENT_STATE_ID}, get the result
   *          from the corresponding snapshot; otherwise, get the result from
   *          the current directory.
   * @return the child inode.
   */
  public INode getChild(byte[] name, int snapshotId) {
    DirectoryWithSnapshotFeature sf;
    if (snapshotId == Snapshot.CURRENT_STATE_ID
        || (sf = getDirectoryWithSnapshotFeature()) == null) {
      return FSDirectory.getInstance().getInode(this.getPath(), DFSUtil.bytes2String(name));
    }

    return sf.getChild(this, name, snapshotId);
  }

  /**
   * Search for the given INode in the children list and the deleted lists of
   * snapshots.
   * @return {@link Snapshot#CURRENT_STATE_ID} if the inode is in the children
   * list; {@link Snapshot#NO_SNAPSHOT_ID} if the inode is neither in the
   * children list nor in any snapshot; otherwise the snapshot id of the
   * corresponding snapshot diff list.
   */
  public int searchChild(INode inode) {
    INode child = getChild(inode.getLocalNameBytes(), Snapshot.CURRENT_STATE_ID);
    if (child != inode) {
      // inode is not in parent's children list, thus inode must be in
      // snapshot. identify the snapshot id and later add it into the path
      DirectoryDiffList diffs = getDiffs();
      if (diffs == null) {
        return Snapshot.NO_SNAPSHOT_ID;
      }
      return diffs.findSnapshotDeleted(inode);
    } else {
      return Snapshot.CURRENT_STATE_ID;
    }
  }

  /**
   * @param snapshotId
   *          if it is not {@link Snapshot#CURRENT_STATE_ID}, get the result
   *          from the corresponding snapshot; otherwise, get the result from
   *          the current directory.
   * @return the current children list if the specified snapshot is null;
   *         otherwise, return the children list corresponding to the snapshot.
   *         Note that the returned list is never null.
   */
  public ReadOnlyList<INode> getChildrenList(final int snapshotId) {
    DirectoryWithSnapshotFeature sf;
    if (snapshotId == Snapshot.CURRENT_STATE_ID
        || (sf = this.getDirectoryWithSnapshotFeature()) == null) {
      return getCurrentChildrenList();
    }
    return sf.getChildrenList(this, snapshotId);
  }

  public HashSet<String> getCurrentChildrenList2() {
    if (children.isEmpty()) {
      children = new HashSet<>();
    }
    return children;
  }

  public void resetCurrentChildrenList() {
    children = new HashSet<>(DatabaseINode.getChildrenNames(getId()));
  }  

  private ReadOnlyList<INode> getCurrentChildrenList() {
    if (children.isEmpty()) {
      children = new HashSet<>(DatabaseINode.getChildrenNames(getId()));
    }
    List<INode> childs = new ArrayList<>(DEFAULT_FILES_PER_DIRECTORY);
    for (String cname : children) {
      INode child = FSDirectory.getInstance().getInode(getPath(), cname);
      if (child != null) {
        childs.add(child);
      }
    }
    return childs == null
        ? ReadOnlyList.Util.<INode>emptyList()
        : ReadOnlyList.Util.asReadOnlyList(childs);
  }

  /**
   * Given a child's name, return the index of the next child
   *
   * @param name a child's name
   * @return the index of the next child
   */
  static int nextChild(ReadOnlyList<INode> children, byte[] name) {
    if (name.length == 0) { // empty name
      return 0;
    }
    int nextPos = ReadOnlyList.Util.binarySearch(children, name) + 1;
    if (nextPos >= 0) {
      return nextPos;
    }
    return -nextPos;
  }

  /**
   * Remove the specified child from this directory.
   */
  public boolean removeChild(INode child, int latestSnapshotId) {
    if (isInLatestSnapshot(latestSnapshotId)) {
      // create snapshot feature if necessary
      DirectoryWithSnapshotFeature sf = this.getDirectoryWithSnapshotFeature();
      if (sf == null) {
        sf = this.addSnapshotFeature(null);
      }
      return sf.removeChild(this, child, latestSnapshotId);
    }

    return removeChild(child);
  }

  /** 
   * Remove the specified child from this directory.
   * The basic remove method which actually calls children.remove(..).
   *
   * @param child the child inode to be removed
   * 
   * @return true if the child is removed; false if the child is not found.
   */
  public boolean removeChild(final INode child) {
    if (children.isEmpty()) {
      return true;
    }
    return children.remove(child.getLocalName());
  }

  /**
   * Add a child inode to the directory.
   * 
   * @param node INode to insert
   * @param setModTime set modification time for the parent node
   *                   not needed when replaying the addition and 
   *                   the parent already has the proper mod time
   * @return false if the child with this name already exists; 
   *         otherwise, return true;
   */
  public boolean addChild(INode node, final boolean setModTime,
      final int latestSnapshotId) {

    // if (getFilter().mightContain(String.valueOf(getId()) + node.getLocalName())) {
    //   if (DatabaseINode.checkInodeExistence(getId(), node.getLocalName())) {
    //     return false;
    //   }
    // }

    if (getCurrentChildrenList2().contains(node.getLocalName())) {
      return false;
    }

    if (isInLatestSnapshot(latestSnapshotId)) {
      // create snapshot feature if necessary
      DirectoryWithSnapshotFeature sf = this.getDirectoryWithSnapshotFeature();
      if (sf == null) {
        sf = this.addSnapshotFeature(null);
      }
      return sf.addChild(this, node, setModTime, latestSnapshotId);
    }

    addChild(node);
    if (setModTime) {
      // update modification time of the parent directory
      updateModificationTime(node.getModificationTime(), latestSnapshotId);
    }
    return true;
  }

  private final String getOldPath(String oldParent, String oldName) {
    String path = null;
    if (oldParent.equals("/")) {
      path = oldParent + oldName;
    } else {
      path = oldParent + "/" + oldName;
    }
    return path;
  }

  public void localRename(INode node, String oldName, String oldParent, String newParent) {
    // String name = DFSUtil.bytes2String(node.getLocalNameBytes());
    String oldPath = getOldPath(oldParent, oldName);
    int skip_id = oldParent.length();
    Long old_id = node.getId();
    if (node.isDirectory()) {
      Queue<ImmutablePair<String, String>> q = new LinkedList<>();
      q.add(new ImmutablePair<>(oldParent, oldName));

      ImmutablePair<String, String> id = null;
      while ((id = q.poll()) != null) {
        INode child = FSDirectory.getInstance().getInode(id.getLeft(), id.getRight());   
        if (child != null) {
          if (child.isDirectory()) {
            HashSet<String> childNames = ((INodeDirectory)child).getCurrentChildrenList2();
            for (String cname : childNames) {
              if (child.getId() == old_id) {
                q.add(new ImmutablePair<>(getOldPath(oldParent, oldName), cname));
              } else {
                q.add(new ImmutablePair<>(child.getPath(), cname));
              }
            }
          }

          if (child.getId() != old_id) {
            child.setParent(child.getParentId() + 40000000);
            child.setParentName(newParent + child.getParentName().substring(skip_id));
          }
          child.setId(child.getId() + 40000000);

          INodeKeyedObjects.getCache().put(child.getPath(), child);
          INodeKeyedObjects.getRenameSet().add(child.getPath());
        }
      }
    } else {
      INodeFile inode = node.asFile().copyINodeFile();

      INodeKeyedObjects.getCache().invalidate(oldPath);
      INodeKeyedObjects.getCache()
          .put(inode.getPath(), inode);

      INodeKeyedObjects.getRenameSet().add(inode.getPath());
    }
  }

  public INodeSection.INode seralizeINodeFile(INodeFile newNode) {
    INodeSection.INodeFile.Builder b = INodeSection.INodeFile.newBuilder()
      .setAccessTime(newNode.getAccessTime())
      .setModificationTime(newNode.getModificationTime())
      .setPermission(newNode.getPermissionLong())
      .setPreferredBlockSize(newNode.getPreferredBlockSize())
      .setStoragePolicyID(newNode.getLocalStoragePolicyID())
      .setBlockType(PBHelperClient.convert(newNode.getBlockType()));

    if (newNode.isStriped()) {
      b.setErasureCodingPolicyID(newNode.getErasureCodingPolicyID());
    } else {
      b.setReplication(newNode.getFileReplication());
    }

    AclFeature acl = newNode.getAclFeature();
    if (acl != null) {
      b.setAcl(buildAclEntries(acl));
    }

    XAttrFeature xAttrFeature = newNode.getXAttrFeature();
    if (xAttrFeature != null) {
      b.setXAttrs(buildXAttrs(xAttrFeature));
    }

    BlockInfo[] blocks = newNode.getBlocks();
    if (blocks != null) {
      for (Block block : blocks) {
        b.addBlocks(PBHelperClient.convert(block));
      }
    }

    FileUnderConstructionFeature uc = newNode.getFileUnderConstructionFeature();
    if (uc != null) {
      long id = newNode.getId();
      INodeSection.FileUnderConstructionFeature f =
        INodeSection.FileUnderConstructionFeature
        .newBuilder().setClientName(uc.getClientName(id))
        .setClientMachine(uc.getClientMachine(id)).build();
      b.setFileUC(f);
    }

    INodeSection.INode r = null;
    try {	
      r = INodeSection.INode.newBuilder()
        .setId(newNode.getId())
        .setName(ByteString.copyFrom(newNode.getLocalNameBytes()))
        .setType(INodeSection.INode.Type.FILE).setFile(b)
        .setParent(newNode.getParentId())
        .setParentName(newNode.getParentName())
        .build();
    } catch (Exception e) {	
        e.printStackTrace();	
    }

    return r;
  }

  public INodeSection.INode seralizeINodeDirectory(INodeDirectory newNode) {
    INodeSection.INodeDirectory.Builder b = INodeSection.INodeDirectory
      .newBuilder()
      .setModificationTime(newNode.getModificationTime())
      .setPermission(newNode.getPermissionLong());

    AclFeature f = newNode.getAclFeature();
    if (f != null) {
      b.setAcl(buildAclEntries(f));
    }

    XAttrFeature xAttrFeature = newNode.getXAttrFeature();
    if (xAttrFeature != null) {
      b.setXAttrs(buildXAttrs(xAttrFeature));
    }

    INodeSection.INode r = null;
    try {	
      r = INodeSection.INode.newBuilder()
        .setId(newNode.getId())
        .setName(ByteString.copyFrom(newNode.getLocalNameBytes()))
        .setType(INodeSection.INode.Type.DIRECTORY).setDirectory(b)
        .setParent(newNode.getParentId())
        .setParentName(newNode.getParentName())
        .build();
    } catch (Exception e) {	
        e.printStackTrace();	
    }

    return r;
  }

  void update_subtree_v2(Set<INode> renameSet, String nameNodeAddress) {
    NamespaceSubtree.Builder b = NamespaceSubtree.newBuilder(); 

    Iterator<INode> iterator = renameSet.iterator();
    while (iterator.hasNext()) { 
      INode inode = iterator.next(); 
      if (inode == null) continue;
      if (inode.isDirectory()) {
        b.addInodes(seralizeINodeDirectory(inode.asDirectory()));
      } else {
        b.addInodes(seralizeINodeFile(inode.asFile()));
      }
      iterator.remove();
    }

    try {
      byte[] data = b.build().toByteArray();

      FSEditLogProtocol proxy = (FSEditLogProtocol) RPC.getProxy(
        FSEditLogProtocol.class, FSEditLogProtocol.versionID,
        new InetSocketAddress(nameNodeAddress, 10087), new Configuration());
      proxy.logEdit(data);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  void update_subtree(Set<INode> renameSet) {
    String database = System.getenv("DATABASE");
    List<Long> longAttr = new ArrayList<>();
    List<String> strAttr = new ArrayList<>();

    List<Long> fileIds = new ArrayList<>();
    List<String> fileAttr = new ArrayList<>();

    Map<BinaryObject, BinaryObject> map = new TreeMap<>();
    DatabaseConnection conn = Database.getInstance().getConnection();
    Iterator<INode> iterator = renameSet.iterator();
    while (iterator.hasNext()) {
      INode inode = iterator.next();
      if (inode == null) continue;
      if (database.equals("VOLT")) {
        strAttr.add(inode.getLocalName());
        if (inode.getId() == 16385) {
          strAttr.add(" ");
        } else {
          strAttr.add(inode.getParentName());
        }
        longAttr.add(inode.getParentId());
        longAttr.add(inode.getId());
        longAttr.add(inode.getModificationTime());
        longAttr.add(inode.getAccessTime());
        longAttr.add(inode.getPermissionLong());
        if (inode.isDirectory()) {
          longAttr.add(0L);
        } else {
          longAttr.add(inode.asFile().getHeaderLong());
          FileUnderConstructionFeature uc = inode.asFile().getFileUnderConstructionFeature();
          if (uc != null) {
            fileIds.add(inode.getId());
            fileAttr.add(uc.getClientName(inode.getId()));
            fileAttr.add(uc.getClientMachine(inode.getId()));
          }
        }
      } else if (database.equals("IGNITE")) {
        BinaryObjectBuilder inodeKeyBuilder = conn.getIgniteClient().binary().builder("InodeKey");
        BinaryObject inodeKey = inodeKeyBuilder.setField("parentName", inode.getParentName()).setField("name", inode.getLocalName()).build();
        BinaryObjectBuilder inodeBuilder = conn.getIgniteClient().binary().builder("INode");
        long header = 0L;
        if (inode.isFile()) {
          header = inode.asFile().getHeaderLong();
        } 
        String parentName = " ";
        if (inode.getId() != 16385) {
          parentName = inode.getParentName();
        }
        BinaryObject inodeValue = inodeBuilder
          .setField("id", inode.getId(), Long.class)
          .setField("parent", inode.getParentId(), Long.class)
          .setField("parentName", parentName)
          .setField("name", inode.getLocalName())
          .setField("accessTime", inode.getAccessTime(), Long.class)
          .setField("modificationTime", inode.getModificationTime(), Long.class)
          .setField("header", header, Long.class)
          .setField("permission", inode.getPermissionLong(), Long.class)
          .build();
        map.put(inodeKey, inodeValue);
      }
      iterator.remove();
    }
    try {
      if (database.equals("VOLT") && strAttr.size() > 0) {
        INodeKeyedObjects.setWalOffset(DatabaseINode.batchUpdateINodes(longAttr, strAttr, fileIds, fileAttr));
      } else if (database.equals("IGNITE") && map.size() > 0) {
        IgniteCompute compute = conn.getIgniteClient().compute();
        INodeKeyedObjects.setWalOffset(
          compute.apply(new BatchUpdateINodes(), map)
        );
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
    Database.getInstance().retConnection(conn);
  }

  public void remoteRename(INode node, String oldName, String oldParent, String newParent, String address) {
    int skip_id = oldParent.length();
    Long old_id = node.getId();
    if (node.isDirectory()) {
      Queue<ImmutablePair<String, String>> q = new LinkedList<>();
      q.add(new ImmutablePair<>(oldParent, oldName));

      // log: delete the old directory
      // FSDirectory.getInstance()
      //   .getEditLog()
      //   .logDelete(null, old_id, node.getModificationTime(), true);

      ImmutablePair<String, String> id = null;
      Set<INode> renameSet = new HashSet<>();

      long dirtyCount = 100000;
      String dirtyCountStr = System.getenv("FILESCALE_DIRTY_OBJECT_NUM");
      String database = System.getenv("DATABASE");
      if (dirtyCountStr != null) {
        dirtyCount = Long.parseLong(dirtyCountStr);
      }
      long count = 0;
      while ((id = q.poll()) != null) {
        if (dirtyCount == 0) break;
        INode child = FSDirectory.getInstance().getInode(id.getLeft(), id.getRight());   
        if (child != null) {
          if (child.isDirectory()) {
            HashSet<String> childNames = ((INodeDirectory)child).getCurrentChildrenList2();
            for (String cname : childNames) {
              if (child.getId() == old_id) {
                q.add(new ImmutablePair<>(getOldPath(oldParent, oldName), cname));
              } else {
                q.add(new ImmutablePair<>(child.getPath(), cname));
              }
            }
          }

          // if (child.getId() != old_id) {
          //   child.setParent(child.getParentId() + 40000000);
          //   child.setParentName(newParent + child.getParentName().substring(skip_id));
          // }
          // child.setId(child.getId() + 40000000);

          // if (child.isDirectory()) {
          //   // log: create new diretory
          //   FSDirectory.getInstance()
          //     .getEditLog()
          //     .logMkDir(null, (INodeDirectory)child);
          // } else {
          //   // log: create new file
          //   FSDirectory.getInstance()
          //     .getEditLog()
          //     .logOpenFile(null, (INodeFile)child, true, true);
          // }

          renameSet.add(child);
          count++;
          INodeKeyedObjects.getCache().invalidate(child.getPath());
          if (count == dirtyCount) {
            // write back to db
            update_subtree(renameSet);
            break;
          }
          if (database.equals("VOLT")) {
            if (renameSet.size() >= 5120) {
              update_subtree(renameSet);
            }
          }
        }
      }
      if (count < dirtyCount && renameSet.size() > 0) {
        update_subtree(renameSet);
        // update_subtree_v2(renameSet, address);
      }

      String start = INodeKeyedObjects.getWalOffset();
      if (database.equals("VOLT")) {
        INodeKeyedObjects.setWalOffset(DatabaseINode.updateSubtree(old_id, 40000000,
          oldParent, "/nnThroughputBenchmark/rename", node.getParentId())
        );
      } else if (database.equals("IGNITE")) {
        DatabaseConnection conn = Database.getInstance().getConnection();
        IgniteCompute compute = conn.getIgniteClient().compute();
        INodeKeyedObjects.setWalOffset(
          compute.apply(new RenameSubtreeINodes(), new RenamePayload(old_id, 40000000,
            oldParent, "/nnThroughputBenchmark/rename", node.getParentId()))
        );
        Database.getInstance().retConnection(conn);
      }
      // try{
      //   Thread.sleep(2); // 2 ms
      // } catch (Exception e) {
      //   e.printStackTrace();
      // }
      String end = INodeKeyedObjects.getWalOffset();
      FSDirectory.getInstance()
        .getEditLog()
        .logRenameMP("/nnThroughputBenchmark/create", "/nnThroughputBenchmark/rename",
        getModificationTime(), false, start, end);
    } else {
      // log: delete old file
      FSDirectory.getInstance()
        .getEditLog()
        .logDelete(null, old_id, node.getModificationTime(), true);

      node.setId(old_id + 40000000);
      // log: create new file
      FSDirectory.getInstance()
        .getEditLog()
        .logOpenFile(null, (INodeFile)node, true, true);

      // CompletableFuture.runAsync(() -> {
        // stored procedure: 1 DML statements
        DatabaseINode.setId(old_id, old_id + 40000000, newParent, node.getParentId());
      // }, Database.getInstance().getExecutorService());

      // invalidate old node
      INodeKeyedObjects.getCache().invalidate(oldParent + oldName);
    }
  }

  public boolean addChild(INode node) {
    node.setParent(getId());
    children.add(node.getLocalName());
    if (node.getGroupName() == null) {
      node.setGroup(getGroupName());
    }
    return true;
  }

  // for rename inode
  public boolean addChild(
      INode node, final String name, final boolean setModTime,
      final int latestSnapshotId, final String existingPath) {

    if (isInLatestSnapshot(latestSnapshotId)) {
      // create snapshot feature if necessary
      DirectoryWithSnapshotFeature sf = this.getDirectoryWithSnapshotFeature();
      if (sf == null) {
        sf = this.addSnapshotFeature(null);
      }
      return sf.addChild(this, node, setModTime, latestSnapshotId);
    }

    INode inode = node;
    // getFilter().put(String.valueOf(getId()) + name);
    getCurrentChildrenList2().add(name);
    if (node.getParentId() != getId() || !node.getLocalName().equals(name)) {
      node.getParent().getCurrentChildrenList2().remove(node.getLocalName());
      // node.getParent().getFilter().delete(String.valueOf(node.getParentId()) + node.getLocalName());

      String oldParent = node.getParentName();
      String oldName = node.getLocalName();
      node.setParent(getId());
      node.setParentName(getPath());
      node.setLocalName(DFSUtil.string2Bytes(name));
      String newParent = node.getParentName();

      // get mount point from zookeeper
      if (FSDirectory.getInstance().isLocalNN()) {
        localRename(node, oldName, oldParent, newParent);
      } else {
        String[] address = new String[2];
        try {
          String mpoint = FSDirectory.getInstance().getMountsManager().resolve(existingPath);
          LOG.info(existingPath + " : " + mpoint);
          address = mpoint.replace("hdfs://","").split(":");
        } catch (Exception e) {
          e.printStackTrace();
        }
        remoteRename(node, oldName, oldParent, newParent, address[0]);
      }
    }

    if (inode.getGroupName() == null) {
      inode.setGroup(getGroupName());
    }

    if (setModTime) {
      // update modification time of the parent directory
      // updateModificationTime(node.getModificationTime(), latestSnapshotId);
      long mtime = inode.getModificationTime();
      setModificationTime(mtime);
    }
    return true;
  }

  /**
   * Add the node to the children list at the given insertion point.
   * The basic add method which actually calls children.add(..).
   */
  /*
  private void addChild(final INode node, final int insertionPoint) {
    if (children == null) {
      children = new ArrayList<>(DEFAULT_FILES_PER_DIRECTORY);
    }
    node.setParent(this);
    children.add(-insertionPoint - 1, node);

    if (node.getGroupName() == null) {
      node.setGroup(getGroupName());
    }
  }
  */

  @Override
  public QuotaCounts computeQuotaUsage(BlockStoragePolicySuite bsps,
      byte blockStoragePolicyId, boolean useCache, int lastSnapshotId) {
    final DirectoryWithSnapshotFeature sf = getDirectoryWithSnapshotFeature();

    QuotaCounts counts = new QuotaCounts.Builder().build();
    // we are computing the quota usage for a specific snapshot here, i.e., the
    // computation only includes files/directories that exist at the time of the
    // given snapshot
    if (sf != null && lastSnapshotId != Snapshot.CURRENT_STATE_ID
        && !(useCache && isQuotaSet())) {
      ReadOnlyList<INode> childrenList = getChildrenList(lastSnapshotId);
      for (INode child : childrenList) {
        final byte childPolicyId = child.getStoragePolicyIDForQuota(
            blockStoragePolicyId);
        counts.add(child.computeQuotaUsage(bsps, childPolicyId, useCache,
            lastSnapshotId));
      }
      counts.addNameSpace(1);
      return counts;
    }
    
    // compute the quota usage in the scope of the current directory tree
    final DirectoryWithQuotaFeature q = getDirectoryWithQuotaFeature();
    if (useCache && q != null && q.isQuotaSet()) { // use the cached quota
      return q.AddCurrentSpaceUsage(counts);
    } else {
      useCache = q != null && !q.isQuotaSet() ? false : useCache;
      return computeDirectoryQuotaUsage(bsps, blockStoragePolicyId, counts,
          useCache, lastSnapshotId);
    }
  }

  private QuotaCounts computeDirectoryQuotaUsage(BlockStoragePolicySuite bsps,
      byte blockStoragePolicyId, QuotaCounts counts, boolean useCache,
      int lastSnapshotId) {
    if (children.isEmpty()) {
      children = new HashSet<>(DatabaseINode.getChildrenNames(getId()));
    }
    if (!children.isEmpty()) {
      for (String cname : children) {
        INode child = FSDirectory.getInstance().getInode(getPath(), cname);
        if (child != null) {
          final byte childPolicyId = child.getStoragePolicyIDForQuota(
              blockStoragePolicyId);
          counts.add(child.computeQuotaUsage(bsps, childPolicyId, useCache,
              lastSnapshotId));
        }
      }
    }
    return computeQuotaUsage4CurrentDirectory(bsps, blockStoragePolicyId,
        counts);
  }
  
  /** Add quota usage for this inode excluding children. */
  public QuotaCounts computeQuotaUsage4CurrentDirectory(
      BlockStoragePolicySuite bsps, byte storagePolicyId, QuotaCounts counts) {
    counts.addNameSpace(1);
    // include the diff list
    DirectoryWithSnapshotFeature sf = getDirectoryWithSnapshotFeature();
    if (sf != null) {
      counts.add(sf.computeQuotaUsage4CurrentDirectory(bsps, storagePolicyId));
    }
    return counts;
  }

  @Override
  public ContentSummaryComputationContext computeContentSummary(int snapshotId,
      ContentSummaryComputationContext summary) throws AccessControlException {
    final DirectoryWithSnapshotFeature sf = getDirectoryWithSnapshotFeature();
    if (sf != null && snapshotId == Snapshot.CURRENT_STATE_ID) {
      final ContentCounts counts = new ContentCounts.Builder().build();
      // if the getContentSummary call is against a non-snapshot path, the
      // computation should include all the deleted files/directories
      sf.computeContentSummary4Snapshot(summary.getBlockStoragePolicySuite(),
          counts);
      summary.getCounts().addContents(counts);
      // Also add ContentSummary to snapshotCounts (So we can extract it
      // later from the ContentSummary of all).
      summary.getSnapshotCounts().addContents(counts);
    }
    final DirectoryWithQuotaFeature q = getDirectoryWithQuotaFeature();
    if (q != null && snapshotId == Snapshot.CURRENT_STATE_ID) {
      return q.computeContentSummary(this, summary);
    } else {
      return computeDirectoryContentSummary(summary, snapshotId);
    }
  }

  protected ContentSummaryComputationContext computeDirectoryContentSummary(
      ContentSummaryComputationContext summary, int snapshotId)
      throws AccessControlException{
    // throws exception if failing the permission check
    summary.checkPermission(this, snapshotId, FsAction.READ_EXECUTE);
    ReadOnlyList<INode> childrenList = getChildrenList(snapshotId);
    // Explicit traversing is done to enable repositioning after relinquishing
    // and reacquiring locks.
    for (int i = 0;  i < childrenList.size(); i++) {
      INode child = childrenList.get(i);
      byte[] childName = child.getLocalNameBytes();

      long lastYieldCount = summary.getYieldCount();
      child.computeContentSummary(snapshotId, summary);

      // Check whether the computation was paused in the subtree.
      // The counts may be off, but traversing the rest of children
      // should be made safe.
      if (lastYieldCount == summary.getYieldCount()) {
        continue;
      }
      // The locks were released and reacquired. Check parent first.
      if (!isRoot() && getParent() == null) {
        // Stop further counting and return whatever we have so far.
        break;
      }
      // Obtain the children list again since it may have been modified.
      childrenList = getChildrenList(snapshotId);
      // Reposition in case the children list is changed. Decrement by 1
      // since it will be incremented when loops.
      i = nextChild(childrenList, childName) - 1;
    }

    // Increment the directory count for this directory.
    summary.getCounts().addContent(Content.DIRECTORY, 1);
    // Relinquish and reacquire locks if necessary.
    summary.yield();
    return summary;
  }
  
  /**
   * This method is usually called by the undo section of rename.
   * 
   * Before calling this function, in the rename operation, we replace the
   * original src node (of the rename operation) with a reference node (WithName
   * instance) in both the children list and a created list, delete the
   * reference node from the children list, and add it to the corresponding
   * deleted list.
   * 
   * To undo the above operations, we have the following steps in particular:
   * 
   * <pre>
   * 1) remove the WithName node from the deleted list (if it exists) 
   * 2) replace the WithName node in the created list with srcChild 
   * 3) add srcChild back as a child of srcParent. Note that we already add 
   * the node into the created list of a snapshot diff in step 2, we do not need
   * to add srcChild to the created list of the latest snapshot.
   * </pre>
   * 
   * We do not need to update quota usage because the old child is in the 
   * deleted list before. 
   * 
   * @param oldChild
   *          The reference node to be removed/replaced
   * @param newChild
   *          The node to be added back
   */
  public void undoRename4ScrParent(final INodeReference oldChild,
      final INode newChild) {
    DirectoryWithSnapshotFeature sf = getDirectoryWithSnapshotFeature();
    assert sf != null : "Directory does not have snapshot feature";
    sf.getDiffs().removeDeletedChild(oldChild);
    sf.getDiffs().replaceCreatedChild(oldChild, newChild);
    addChild(newChild, true, Snapshot.CURRENT_STATE_ID);
  }
  
  /**
   * Undo the rename operation for the dst tree, i.e., if the rename operation
   * (with OVERWRITE option) removes a file/dir from the dst tree, add it back
   * and delete possible record in the deleted list.  
   */
  public void undoRename4DstParent(final BlockStoragePolicySuite bsps,
      final INode deletedChild, int latestSnapshotId) {
    DirectoryWithSnapshotFeature sf = getDirectoryWithSnapshotFeature();
    assert sf != null : "Directory does not have snapshot feature";
    boolean removeDeletedChild = sf.getDiffs().removeDeletedChild(deletedChild);
    int sid = removeDeletedChild ? Snapshot.CURRENT_STATE_ID : latestSnapshotId;
    final boolean added = addChild(deletedChild, true, sid);
    // update quota usage if adding is successfully and the old child has not
    // been stored in deleted list before
    if (added && !removeDeletedChild) {
      final QuotaCounts counts = deletedChild.computeQuotaUsage(bsps);
      addSpaceConsumed(counts);
    }
  }

  /** Set the children list to null. */
  public void clearChildren() {
    this.children = null;
  }

  @Override
  public void clear() {
    super.clear();
    clearChildren();
  }

  /** Call cleanSubtree(..) recursively down the subtree. */
  public void cleanSubtreeRecursively(
      ReclaimContext reclaimContext, final int snapshot, int prior,
      final Map<INode, INode> excludedNodes) {
    // in case of deletion snapshot, since this call happens after we modify
    // the diff list, the snapshot to be deleted has been combined or renamed
    // to its latest previous snapshot. (besides, we also need to consider nodes
    // created after prior but before snapshot. this will be done in 
    // DirectoryWithSnapshotFeature)
    int s = snapshot != Snapshot.CURRENT_STATE_ID
        && prior != Snapshot.NO_SNAPSHOT_ID ? prior : snapshot;
    ReadOnlyList<INode> childs = getChildrenList(s);
    for (INode child : childs) {
      if (snapshot == Snapshot.CURRENT_STATE_ID || excludedNodes == null ||
          !excludedNodes.containsKey(child)) {
        child.cleanSubtree(reclaimContext, snapshot, prior);
      }
    }
  }

  @Override
  public void destroyAndCollectBlocks(ReclaimContext reclaimContext) {
    reclaimContext.quotaDelta().add(
        new QuotaCounts.Builder().nameSpace(1).build());
    final DirectoryWithSnapshotFeature sf = getDirectoryWithSnapshotFeature();
    if (sf != null) {
      sf.clear(reclaimContext, this);
    }

    ReadOnlyList<INode> childs = getChildrenList(Snapshot.CURRENT_STATE_ID);
    for (INode child : childs) {
      child.destroyAndCollectBlocks(reclaimContext);
    }
    if (getAclFeature() != null) {
      AclStorage.removeAclFeature(getAclFeature());
    }
    reclaimContext.removedINodes.add(this);
  }
  
  @Override
  public void cleanSubtree(ReclaimContext reclaimContext, final int snapshotId,
      int priorSnapshotId) {
    DirectoryWithSnapshotFeature sf = getDirectoryWithSnapshotFeature();
    // there is snapshot data
    if (sf != null) {
      sf.cleanDirectory(reclaimContext, this, snapshotId, priorSnapshotId);
    } else {
      // there is no snapshot data
      if (priorSnapshotId == Snapshot.NO_SNAPSHOT_ID &&
          snapshotId == Snapshot.CURRENT_STATE_ID) {
        // destroy the whole subtree and collect blocks that should be deleted
        // destroyAndCollectBlocks(reclaimContext);
      } else {
        // make a copy the quota delta
        QuotaCounts old = reclaimContext.quotaDelta().getCountsCopy();
        // process recursively down the subtree
        cleanSubtreeRecursively(reclaimContext, snapshotId, priorSnapshotId,
            null);
        QuotaCounts current = reclaimContext.quotaDelta().getCountsCopy();
        current.subtract(old);
        if (isQuotaSet()) {
          reclaimContext.quotaDelta().addQuotaDirUpdate(this, current);
        }
      }
    }
  }
  
  /**
   * Compare the metadata with another INodeDirectory
   */
  @Override
  public boolean metadataEquals(INodeDirectoryAttributes other) {
    return other != null
        && getQuotaCounts().equals(other.getQuotaCounts())
        && getPermissionLong() == other.getPermissionLong()
        && getAclFeature() == other.getAclFeature()
        && getXAttrFeature() == other.getXAttrFeature();
  }
  
  /*
   * The following code is to dump the tree recursively for testing.
   * 
   *      \- foo   (INodeDirectory@33dd2717)
   *        \- sub1   (INodeDirectory@442172)
   *          +- file1   (INodeFile@78392d4)
   *          +- file2   (INodeFile@78392d5)
   *          +- sub11   (INodeDirectory@8400cff)
   *            \- file3   (INodeFile@78392d6)
   *          \- z_file4   (INodeFile@45848712)
   */
  static final String DUMPTREE_EXCEPT_LAST_ITEM = "+-"; 
  static final String DUMPTREE_LAST_ITEM = "\\-";
  @VisibleForTesting
  @Override
  public void dumpTreeRecursively(PrintWriter out, StringBuilder prefix,
      final int snapshot) {
    super.dumpTreeRecursively(out, prefix, snapshot);
    out.print(", childrenSize=" + getChildrenList(snapshot).size());
    final DirectoryWithQuotaFeature q = getDirectoryWithQuotaFeature();
    if (q != null) {
      out.print(", " + q);
    }
    if (this instanceof Snapshot.Root) {
      out.print(", snapshotId=" + snapshot);
    }
    out.println();

    if (prefix.length() >= 2) {
      prefix.setLength(prefix.length() - 2);
      prefix.append("  ");
    }
    dumpTreeRecursively(out, prefix, new Iterable<SnapshotAndINode>() {
      final Iterator<INode> i = getChildrenList(snapshot).iterator();
      
      @Override
      public Iterator<SnapshotAndINode> iterator() {
        return new Iterator<SnapshotAndINode>() {
          @Override
          public boolean hasNext() {
            return i.hasNext();
          }

          @Override
          public SnapshotAndINode next() {
            return new SnapshotAndINode(snapshot, i.next());
          }

          @Override
          public void remove() {
            throw new UnsupportedOperationException();
          }
        };
      }
    });

    final DirectorySnapshottableFeature s = getDirectorySnapshottableFeature();
    if (s != null) {
      s.dumpTreeRecursively(this, out, prefix, snapshot);
    }
  }

  /**
   * Dump the given subtrees.
   * @param prefix The prefix string that each line should print.
   * @param subs The subtrees.
   */
  @VisibleForTesting
  public static void dumpTreeRecursively(PrintWriter out,
      StringBuilder prefix, Iterable<SnapshotAndINode> subs) {
    if (subs != null) {
      for(final Iterator<SnapshotAndINode> i = subs.iterator(); i.hasNext();) {
        final SnapshotAndINode pair = i.next();
        prefix.append(i.hasNext()? DUMPTREE_EXCEPT_LAST_ITEM: DUMPTREE_LAST_ITEM);
        pair.inode.dumpTreeRecursively(out, prefix, pair.snapshotId);
        prefix.setLength(prefix.length() - 2);
      }
    }
  }

  /** A pair of Snapshot and INode objects. */
  public static class SnapshotAndINode {
    public final int snapshotId;
    public final INode inode;

    public SnapshotAndINode(int snapshot, INode inode) {
      this.snapshotId = snapshot;
      this.inode = inode;
    }
  }

  public final int getChildrenNum(final int snapshotId) {
    return getChildrenList(snapshotId).size();
  }
}
