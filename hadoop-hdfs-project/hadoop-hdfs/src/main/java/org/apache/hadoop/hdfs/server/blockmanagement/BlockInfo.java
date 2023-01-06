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
package org.apache.hadoop.hdfs.server.blockmanagement;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import com.google.common.base.Preconditions;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.fs.StorageType;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.BlockType;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants.BlockUCState;
import org.apache.hadoop.util.LightWeightGSet;

import org.apache.hadoop.hdfs.db.*;
import static org.apache.hadoop.hdfs.server.namenode.INodeId.INVALID_INODE_ID;

/**
 * For a given block (or an erasure coding block group), BlockInfo class
 * maintains 1) the {@link BlockCollection} it is part of, and 2) datanodes
 * where the replicas of the block, or blocks belonging to the erasure coding
 * block group, are stored.
 */
@InterfaceAudience.Private
public abstract class BlockInfo extends Block {

  public static final BlockInfo[] EMPTY_ARRAY = {};

  public BlockInfo(Block blk) {
    super(blk);
  }

  /**
   * Construct an entry for blocksmap
   * @param size the block's replication factor, or the total number of blocks
   *             in the block group
   */
  // FIXME: I don't think this function still be used!
  public BlockInfo(short size) {
    super(0, 0, 0);
    DatabaseDatablock.setReplication(0, isStriped() ? 0 : size);
  }

  public BlockInfo(Block blk, short size) {
    super(blk);
    DatabaseDatablock.setReplication(blk.getBlockId(), isStriped() ? 0 : size);
  }

  public BlockInfo(long bid, long num, long stamp, short size) {
    super(bid, num, stamp);
    DatabaseDatablock.setReplication(bid, isStriped() ? 0 : size);    
  }

  public short getReplication() {
    return DatabaseDatablock.getReplication(getBlockId());
  }

  public void setReplication(short repl) {
    DatabaseDatablock.setReplication(getBlockId(), repl);
  }

  public long getBlockCollectionId() {
    return DatabaseINode2Block.getBcId(getBlockId());
  }

  public void setBlockCollectionId(long id) {
    DatabaseINode2Block.setBcIdViaBlkId(getBlockId(), id);
  }

  public void delete() {
    DatabaseINode2Block.deleteViaBlkId(getBlockId());
  }

  public boolean isDeleted() {
    return DatabaseINode2Block.getBcId(getBlockId()) == 0;
  }

  public Iterator<DatanodeStorageInfo> getStorageInfos() {
    return new Iterator<DatanodeStorageInfo>() {

      private int index = 0;
      private List<DatanodeStorageInfo> storages = BlockManager.getInstance().getBlockStorages(getBlockId());
      @Override
      public boolean hasNext() {
        while (index < storages.size() && storages.get(index) == null) {
          index++;
        }
        return index < storages.size();
      }

      @Override
      public DatanodeStorageInfo next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        return storages.get(index++);
      }

      @Override
      public void remove() {
        throw new UnsupportedOperationException("Sorry. can't remove.");
      }
    };
  }

  public DatanodeDescriptor getDatanode(int index) {
    DatanodeStorageInfo storage = getStorageInfo(index);
    return storage == null ? null : storage.getDatanodeDescriptor();
  }

  DatanodeStorageInfo getStorageInfo(int index) {
    String storageId = DatabaseStorage.getStorageId(getBlockId(), index);
    if (storageId == null) {
      return null;
    }
    return BlockManager.getInstance().getBlockStorage(storageId); 
  }

  void setStorageInfo(int index, DatanodeStorageInfo storage) {
    int size = DatabaseStorage.getNumStorages(getBlockId());
    String storageId = null;
    if (storage != null) {
      storageId = storage.getStorageID();
      BlockManager.getInstance().setBlockStorage(storageId, storage);
    } 
    if (index < size) {
      DatabaseStorage.setStorage(getBlockId(), index, storageId);
    } else {
      assert index == size : "Expand one storage for BlockInfo"; 
      DatabaseStorage.insertStorage(getBlockId(), index, storageId);
    }
  }

  public int getCapacity() {
    return DatabaseStorage.getNumStorages(getBlockId());
  }

  /**
   * Count the number of data-nodes the block currently belongs to (i.e., NN
   * has received block reports from the DN).
   */
  public abstract int numNodes();

  /**
   * Add a {@link DatanodeStorageInfo} location for a block
   * @param storage The storage to add
   * @param reportedBlock The block reported from the datanode. This is only
   *                      used by erasure coded blocks, this block's id contains
   *                      information indicating the index of the block in the
   *                      corresponding block group.
   */
  abstract boolean addStorage(DatanodeStorageInfo storage, Block reportedBlock);

  /**
   * Remove {@link DatanodeStorageInfo} location for a block
   */
  abstract boolean removeStorage(DatanodeStorageInfo storage);

  public abstract boolean isStriped();

  public abstract BlockType getBlockType();

  /** @return true if there is no datanode storage associated with the block */
  abstract boolean hasNoStorage();

  /**
   * Find specified DatanodeStorageInfo.
   * @return DatanodeStorageInfo or null if not found.
   */
  DatanodeStorageInfo findStorageInfo(DatanodeDescriptor dn) {
    int len = getCapacity();
    DatanodeStorageInfo providedStorageInfo = null;
    for(int idx = 0; idx < len; idx++) {
      DatanodeStorageInfo cur = getStorageInfo(idx);
      if(cur != null) {
        if (cur.getStorageType() == StorageType.PROVIDED) {
          // if block resides on provided storage, only match the storage ids
          if (dn.getStorageInfo(cur.getStorageID()) != null) {
            // do not return here as we have to check the other
            // DatanodeStorageInfos for this block which could be local
            providedStorageInfo = cur;
          }
        } else if (cur.getDatanodeDescriptor() == dn) {
          return cur;
        }
      }
    }
    return providedStorageInfo;
  }

  /**
   * Find specified DatanodeStorageInfo.
   * @return index or -1 if not found.
   */
  int findStorageInfo(DatanodeStorageInfo storageInfo) {
    int len = getCapacity();
    for(int idx = 0; idx < len; idx++) {
      DatanodeStorageInfo cur = getStorageInfo(idx);
      if (cur == storageInfo) {
        return idx;
      }
    }
    return -1;
  }

  @Override
  public int hashCode() {
    // Super implementation is sufficient
    return super.hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    // Sufficient to rely on super's implementation
    return (this == obj) || super.equals(obj);
  }

  /* UnderConstruction Feature related */

  public BlockUnderConstructionFeature getUnderConstructionFeature() {
    return BlockManager.getInstance().getBlockUC(getBlockId());
  }

  public BlockUCState getBlockUCState() {
    BlockUnderConstructionFeature uc = getUnderConstructionFeature();
    return uc == null ? BlockUCState.COMPLETE : uc.getBlockUCState();
  }

  /**
   * Is this block complete?
   *
   * @return true if the state of the block is {@link BlockUCState#COMPLETE}
   */
  public boolean isComplete() {
    return getBlockUCState().equals(BlockUCState.COMPLETE);
  }

  public boolean isUnderRecovery() {
    return getBlockUCState().equals(BlockUCState.UNDER_RECOVERY);
  }

  public final boolean isCompleteOrCommitted() {
    final BlockUCState state = getBlockUCState();
    return state.equals(BlockUCState.COMPLETE) ||
        state.equals(BlockUCState.COMMITTED);
  }

  /**
   * Add/Update the under construction feature.
   */
  public void convertToBlockUnderConstruction(BlockUCState s,
      DatanodeStorageInfo[] targets) {
    if (isComplete()) {
      BlockUnderConstructionFeature uc = new BlockUnderConstructionFeature(
        this, s, targets, this.getBlockType());
      BlockManager.getInstance().setBlockUC(getBlockId(), uc);
    } else {
      // the block is already under construction
      BlockUnderConstructionFeature uc = getUnderConstructionFeature();
      uc.setBlockUCState(s);
      uc.setExpectedLocations(this, targets, this.getBlockType());
    }
  }

  /**
   * Convert an under construction block to complete.
   */
  void convertToCompleteBlock() {
    assert getBlockUCState() != BlockUCState.COMPLETE :
        "Trying to convert a COMPLETE block";
    BlockManager.getInstance().removeBlockUC(getBlockId());
  }

  /**
   * Process the recorded replicas. When about to commit or finish the
   * pipeline recovery sort out bad replicas.
   * @param genStamp  The final generation stamp for the block.
   * @return staleReplica's List.
   */
  public List<ReplicaUnderConstruction> setGenerationStampAndVerifyReplicas(
      long genStamp) {
    BlockUnderConstructionFeature uc = getUnderConstructionFeature();
    Preconditions.checkState(uc != null && !isComplete());
    // Set the generation stamp for the block.
    setGenerationStamp(genStamp);

    return uc.getStaleReplicas(genStamp);
  }

  /**
   * Commit block's length and generation stamp as reported by the client.
   * Set block state to {@link BlockUCState#COMMITTED}.
   * @param block - contains client reported block length and generation
   * @return staleReplica's List.
   * @throws IOException if block ids are inconsistent.
   */
  List<ReplicaUnderConstruction> commitBlock(Block block) throws IOException {
    if (getBlockId() != block.getBlockId()) {
      throw new IOException("Trying to commit inconsistent block: id = "
          + block.getBlockId() + ", expected id = " + getBlockId());
    }
    Preconditions.checkState(!isComplete());
    BlockUnderConstructionFeature uc = getUnderConstructionFeature();
    uc.commit();
    this.setNumBytes(block.getNumBytes());
    // Sort out invalid replicas.
    return setGenerationStampAndVerifyReplicas(block.getGenerationStamp());
  }
}
