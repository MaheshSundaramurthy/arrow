/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.arrow.vector.schema;

import static org.apache.arrow.vector.schema.FBSerializables.writeAllStructsToVector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.arrow.flatbuf.RecordBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.flatbuffers.FlatBufferBuilder;

import io.netty.buffer.ArrowBuf;

public class ArrowRecordBatch implements FBSerializable, AutoCloseable {
  private static final Logger LOGGER = LoggerFactory.getLogger(ArrowRecordBatch.class);

  /** number of records */
  private final int length;

  /** Nodes correspond to the pre-ordered flattened logical schema */
  private final List<ArrowFieldNode> nodes;

  private final List<ArrowBuf> buffers;

  private final List<ArrowBuffer> buffersLayout;

  private boolean closed = false;

  public ArrowRecordBatch(int length, List<ArrowFieldNode> nodes, List<ArrowBuf> buffers) {
    this(length, nodes, buffers, true);
  }

  /**
   * @param length how many rows in this batch
   * @param nodes field level info
   * @param buffers will be retained until this recordBatch is closed
   * @param align whether to align buffers out 8 byte boundaries
   */
  public ArrowRecordBatch(int length, List<ArrowFieldNode> nodes, List<ArrowBuf> buffers, boolean align) {
    super();
    this.length = length;
    this.nodes = nodes;
    this.buffers = buffers;
    List<ArrowBuffer> arrowBuffers = new ArrayList<>();
    long offset = 0;
    for (ArrowBuf arrowBuf : buffers) {
      arrowBuf.retain();
      long size = arrowBuf.readableBytes();
      arrowBuffers.add(new ArrowBuffer(0, offset, size));
      LOGGER.debug(String.format("Buffer in RecordBatch at %d, length: %d", offset, size));
      offset += size;
      if (align && offset % 8 != 0) { // align on 8 byte boundaries
        offset += 8 - (offset % 8);
      }
    }
    this.buffersLayout = Collections.unmodifiableList(arrowBuffers);
  }

  public int getLength() {
    return length;
  }

  /**
   * @return the FieldNodes corresponding to the schema
   */
  public List<ArrowFieldNode> getNodes() {
    return nodes;
  }

  /**
   * @return the buffers containing the data
   */
  public List<ArrowBuf> getBuffers() {
    if (closed) {
      throw new IllegalStateException("already closed");
    }
    return buffers;
  }

  /**
   * @return the serialized layout if we send the buffers on the wire
   */
  public List<ArrowBuffer> getBuffersLayout() {
    return buffersLayout;
  }

  @Override
  public int writeTo(FlatBufferBuilder builder) {
    RecordBatch.startNodesVector(builder, nodes.size());
    int nodesOffset = writeAllStructsToVector(builder, nodes);
    RecordBatch.startBuffersVector(builder, buffers.size());
    int buffersOffset = writeAllStructsToVector(builder, buffersLayout);
    RecordBatch.startRecordBatch(builder);
    RecordBatch.addLength(builder, length);
    RecordBatch.addNodes(builder, nodesOffset);
    RecordBatch.addBuffers(builder, buffersOffset);
    return RecordBatch.endRecordBatch(builder);
  }

  /**
   * releases the buffers
   */
  public void close() {
    if (!closed) {
      closed = true;
      for (ArrowBuf arrowBuf : buffers) {
        arrowBuf.release();
      }
    }
  }

}
