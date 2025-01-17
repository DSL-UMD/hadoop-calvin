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

import java.io.IOException;
import org.apache.hadoop.ipc.VersionedProtocol;

import org.apache.hadoop.hdfs.server.namenode.INodeFile;
import org.apache.hadoop.hdfs.server.namenode.INodeDirectory;
import org.apache.hadoop.hdfs.server.namenode.FsImageProto.INodeSection;

public interface FSEditLogProtocol extends VersionedProtocol {
    public static final long versionID = 1L;
    public INodeDirectory loadINodeDirectory(INodeSection.INode n);
    public INodeFile loadINodeFile(INodeSection.INode n);
    public void logEdit(byte[] inode) throws IOException;
    public void invalidateAndWriteBackDB(byte[] mpoint) throws IOException;
}
