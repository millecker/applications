/**
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
package at.illecker.hama.hybrid.examples.onlinecf;

import org.trifort.rootbeer.runtime.HamaPeer;
import org.trifort.rootbeer.runtime.Kernel;
import org.trifort.rootbeer.runtime.RootbeerGpu;

/**
 * Collaborative Filtering based on
 * 
 * Singular Value Decomposition for Collaborative Filtering on a GPU
 * http://iopscience.iop.org/1757-899X/10/1/012017/pdf/1757-899X_10_1_012017.pdf
 * 
 */
public class OnlineCFTrainHybridKernel implements Kernel {
  private double[][] m_userItemMatrix;
  private int[][] m_userHelper;
  private int[][] m_itemHelper;
  public double[][] m_usersMatrix;
  public double[][] m_itemsMatrix;
  private int m_N;
  private int m_M;
  private double m_ALPHA;
  private int m_matrixRank;
  private int m_maxIterations;

  private int m_skipCount;
  private int m_peerCount = 0;
  private int m_peerId = 0;
  private String[] m_allPeerNames;

  private GpuIntegerMap m_counterMap;
  private GpuIntegerListMap m_senderMap;

  private GpuIntegerMap m_itemColMap;
  private GpuIntegerMap m_colItemMap;

  public OnlineCFTrainHybridKernel(double[][] userItemMatrix,
      int[][] userHelper, int[][] itemHelper, GpuIntegerMap itemColMap,
      GpuIntegerMap colItemMap, double[][] usersMatrix, double[][] itemsMatrix,
      int n, int m, double alpha, int matrixRank, int maxIterations,
      GpuIntegerMap counterMap, int skipCount, int peerCount, int peerId,
      String[] allPeerNames) {
    this.m_userItemMatrix = userItemMatrix;
    this.m_userHelper = userHelper;
    this.m_itemHelper = itemHelper;
    this.m_itemColMap = itemColMap;
    this.m_colItemMap = colItemMap;
    this.m_usersMatrix = usersMatrix;
    this.m_itemsMatrix = itemsMatrix;
    this.m_N = n;
    this.m_M = m;
    this.m_ALPHA = alpha;
    this.m_matrixRank = matrixRank;
    this.m_maxIterations = maxIterations;
    this.m_skipCount = skipCount;
    this.m_peerCount = peerCount;
    this.m_peerId = peerId;
    this.m_allPeerNames = allPeerNames;
    this.m_counterMap = counterMap;
    this.m_senderMap = new GpuIntegerListMap(m_M);
  }

  public void gpuMethod() {
    int blockSize = RootbeerGpu.getBlockDimx();
    int gridSize = RootbeerGpu.getGridDimx();
    int block_idxx = RootbeerGpu.getBlockIdxx();
    int thread_idxx = RootbeerGpu.getThreadIdxx();

    if (blockSize < m_matrixRank) {
      return;
    }

    int usersPerBlock = divup(m_N, gridSize);
    int itemsPerBlock = divup(m_M, gridSize);
    int reductionStart = roundUpToNextPowerOfTwo(divup(m_matrixRank, 2));

    // SharedMemory per block (max 12 + 1024 * 8 = 8204 bytes)
    // e.g., maxtrixRank 256 => 12 + 256 * 8 = 2060 bytes
    int shmStartPos = 0;
    // multVector: matrixRank x Doubles (m_matrixRank * 8 bytes)
    int shmMultVectorStartPos = shmStartPos;

    // DEBUG
    // if (RootbeerGpu.getThreadId() == 0) {
    // System.out.println("blockSize: " + blockSize);
    // System.out.println("gridSize: " + gridSize);
    // System.out.println("users(N): " + m_N);
    // System.out.println("items(M): " + m_M);
    // System.out.println("usersPerBlock: " + usersPerBlock);
    // System.out.println("itemsPerBlock: " + itemsPerBlock);
    // }

    // Start OnlineCF algorithm
    for (int i = 0; i < m_maxIterations; i++) {

      // **********************************************************************
      // Compute U (Users)
      // **********************************************************************
      // Loop over all usersPerBlock
      for (int u = 0; u < usersPerBlock; u++) {

        int userId = (gridSize * u) + block_idxx;
        if (userId < m_N) {

          // Each user loops over all items which have a rating
          for (int itemIdxx = 1; itemIdxx <= m_userHelper[userId][0]; itemIdxx++) {

            int itemId = m_userHelper[userId][itemIdxx];

            // Each thread within a block computes one multiplication
            if (thread_idxx < m_matrixRank) {
              RootbeerGpu.setSharedDouble(shmMultVectorStartPos + thread_idxx
                  * 8, m_usersMatrix[userId][thread_idxx]
                  * m_itemsMatrix[itemId][thread_idxx]);
            }

            // Sync all threads within a block
            RootbeerGpu.syncthreads();

            // Calculate score by summing up multiplications
            // do reduction in shared memory
            // 1-bit right shift = divide by two to the power 1
            for (int s = reductionStart; s > 0; s >>= 1) {

              if ((thread_idxx < s) && (thread_idxx + s) < m_matrixRank) {
                // sh_mem[tid] += sh_mem[tid + s];
                RootbeerGpu.setSharedDouble(
                    shmMultVectorStartPos + thread_idxx * 8,
                    RootbeerGpu.getSharedDouble(shmMultVectorStartPos
                        + thread_idxx * 8)
                        + RootbeerGpu.getSharedDouble(shmMultVectorStartPos
                            + (thread_idxx + s) * 8));
              }

              // Sync all threads within a block
              RootbeerGpu.syncthreads();
            }

            // Calculate new userVector
            // Each thread does one update operation of vector u
            if (thread_idxx < m_matrixRank) {
              m_usersMatrix[userId][thread_idxx] += m_itemsMatrix[itemId][thread_idxx]
                  * 2
                  * m_ALPHA
                  * (m_userItemMatrix[userId][itemId] - RootbeerGpu
                      .getSharedDouble(shmMultVectorStartPos));
            }

            // Sync all threads within a block
            RootbeerGpu.syncthreads();

          } // loop over all items which have a rating

        } // if userId < m_N

      } // loop over all usersPerBlock

      // Sync all blocks Inter-Block Synchronization
      RootbeerGpu.syncblocks(1);

      // **********************************************************************
      // Compute V (Items)
      // **********************************************************************
      // Loop over all itemsPerBlock
      for (int v = 0; v < itemsPerBlock; v++) {

        int itemId = (gridSize * v) + block_idxx;
        if (itemId < m_M) {

          // Each user loops over all users which have a rating
          for (int userIdxx = 1; userIdxx <= m_itemHelper[itemId][0]; userIdxx++) {

            int userId = m_itemHelper[itemId][userIdxx];

            // Each thread within a block computes one multiplication
            if (thread_idxx < m_matrixRank) {
              RootbeerGpu.setSharedDouble(shmMultVectorStartPos + thread_idxx
                  * 8, m_itemsMatrix[itemId][thread_idxx]
                  * m_usersMatrix[userId][thread_idxx]);
            }

            // Sync all threads within a block
            RootbeerGpu.syncthreads();

            // Calculate score by summing up multiplications
            // do reduction in shared memory
            // 1-bit right shift = divide by two to the power 1
            for (int s = reductionStart; s > 0; s >>= 1) {

              if ((thread_idxx < s) && (thread_idxx + s) < m_matrixRank) {
                // sh_mem[tid] += sh_mem[tid + s];
                RootbeerGpu.setSharedDouble(
                    shmMultVectorStartPos + thread_idxx * 8,
                    RootbeerGpu.getSharedDouble(shmMultVectorStartPos
                        + thread_idxx * 8)
                        + RootbeerGpu.getSharedDouble(shmMultVectorStartPos
                            + (thread_idxx + s) * 8));
              }

              // Sync all threads within a block
              RootbeerGpu.syncthreads();
            }

            // Calculate new userVector
            // Each thread does one update operation of vector u
            if (thread_idxx < m_matrixRank) {
              m_itemsMatrix[itemId][thread_idxx] += m_usersMatrix[userId][thread_idxx]
                  * 2
                  * m_ALPHA
                  * (m_userItemMatrix[userId][itemId] - RootbeerGpu
                      .getSharedDouble(shmMultVectorStartPos));
            }

            // Sync all threads within a block
            RootbeerGpu.syncthreads();

          } // loop over all users which have a rating

        } // if (itemId < m_M)

      } // loop over all itemsPerBlock

      // Sync all blocks Inter-Block Synchronization
      RootbeerGpu.syncblocks(2);

      // **********************************************************************
      // normalizeWithBroadcastingValues
      // **********************************************************************
      // Only global Thread 0
      if ((m_peerCount > 1) && ((i + 1) % m_skipCount == 0)
          && (RootbeerGpu.getThreadId() == 0)) {

        // clear sender map
        m_senderMap.clear();

        // Step 1)
        // send item matrices to selected peers
        for (int itemId = 0; itemId < m_M; itemId++) {

          int realItemId = m_colItemMap.get(itemId);
          int toPeerId = realItemId % m_peerCount;
          // don't send item to itself
          if (toPeerId != m_peerId) {
            // init Counter
            m_counterMap.put(itemId, 0);

            // ItemMessage (senderId,itemId,itemVector)
            // 0,1,0.622676719363376,0.47894004113535393,0.9099409696184495
            StringBuilder message = new StringBuilder();
            message.append(Integer.toString(m_peerId));
            message.append(",");
            message.append(Integer.toString(realItemId));
            message.append(",");
            for (int d = 0; d < m_matrixRank; d++) {
              message.append(Double.toString(m_itemsMatrix[itemId][d]));
              if (d + 1 < m_matrixRank) {
                message.append(",");
              }
            }
            String messageStr = message.toString();

            // System.out.println("sendItem itemId: " + itemId + " toPeerId: "
            // + toPeerId + " value: "
            // + arrayToString(m_itemsMatrix[itemId], m_matrixRank));

            HamaPeer.send(m_allPeerNames[toPeerId], messageStr);

          } else {
            m_counterMap.put(itemId, 1);
          }
        }

        HamaPeer.sync();

        // Step 2)
        // receive item matrices if this peer is selected and normalize them
        String msg;
        while ((msg = HamaPeer.getCurrentStringMessage()) != null) {
          // Parse string message
          // ItemMessage (senderId,itemId,itemVector)
          String[] values = msg.split(",");
          int senderId = Integer.parseInt(values[0]);
          int realItemId = Integer.parseInt(values[1]);
          Integer itemId = m_itemColMap.get(realItemId);
          if (itemId != null) {
            int dim = values.length - 2;
            for (int d = 0; d < dim; d++) {
              m_itemsMatrix[itemId][d] += Double.parseDouble(values[d + 2]);
            }

            m_counterMap.add(itemId, 1);
            m_senderMap.put(itemId, senderId);

            // System.out.println("receiveItem itemId: " + itemId
            // + " fromPeerId: " + senderId + " accumulated value: "
            // + arrayToString(m_itemsMatrix[itemId], dim) + " counter: "
            // + m_counterMap.get(itemId));
          }
        }

      } // RootbeerGpu.getThreadId() == 0

      // Sync all blocks Inter-Block Synchronization
      RootbeerGpu.syncblocks(3);

      // Step 3)
      // normalize (messages with counters)
      // Loop over all itemsPerBlock
      // Each thread within a block in parallel
      for (int v = 0; v < itemsPerBlock; v++) {
        int itemId = (itemsPerBlock * v) + block_idxx;
        Integer counter = m_counterMap.get(itemId);
        if ((itemId < m_M) && (counter != null) && (counter > 1)
            && (thread_idxx < m_matrixRank)) {
          m_itemsMatrix[itemId][thread_idxx] = m_itemsMatrix[itemId][thread_idxx]
              / counter;
        }

        // Sync all threads within a block
        RootbeerGpu.syncthreads();

      } // loop over all itemsPerBlock

      // Sync all blocks Inter-Block Synchronization
      RootbeerGpu.syncblocks(4);

      // Only global Thread 0
      if (RootbeerGpu.getThreadId() == 0) {

        // Step 4)
        // send back normalized values to senders
        for (int itemId = 0; itemId < m_M; itemId++) {

          // only send own items
          int realItemId = m_colItemMap.get(itemId);
          if (m_peerId == realItemId % m_peerCount) {

            // ItemMessage (senderId,itemId,itemVector)
            // e.g.,
            // 0,1,0.622676719363376,0.47894004113535393,0.9099409696184495
            StringBuilder message = new StringBuilder();
            message.append(Integer.toString(m_peerId));
            message.append(",");
            message.append(Integer.toString(realItemId));
            message.append(",");
            for (int d = 0; d < m_matrixRank; d++) {
              message.append(Double.toString(m_itemsMatrix[itemId][d]));
              if (d + 1 < m_matrixRank) {
                message.append(",");
              }
            }
            String messageStr = message.toString();
            // System.out.println(messageStr); // Error will break

            // send to interested peers
            GpuIntIntPair pair = m_senderMap.getList(itemId);
            while (pair != null) {
              int toPeerId = pair.getValue();

              // System.out.println("sendNormalizedBack itemId: " + itemId
              // + " toPeerId: " + toPeerId + " value: "
              // + arrayToString(vector) + "\n");

              HamaPeer.send(m_allPeerNames[toPeerId], messageStr);

              pair = pair.getNext();
            }
          } // if (m_peerId == realItemId % m_peerCount)
        }

        HamaPeer.sync();

        // Step 5)
        // receive already normalized and update data
        String msg;
        while ((msg = HamaPeer.getCurrentStringMessage()) != null) {
          // Parse string message
          // ItemMessage (senderId,itemId,itemVector)
          String[] values = msg.split(",");

          // don't care about the senderId (values[0])
          int realItemId = Integer.parseInt(values[1]);
          Integer itemId = m_itemColMap.get(realItemId);
          if (itemId != null) {
            int dim = values.length - 2;
            for (int d = 0; d < dim; d++) {
              m_itemsMatrix[itemId][d] = Double.parseDouble(values[d + 2]);
            }
            // System.out.println("updateItems itemId: " + itemId + " value: "
            // + arrayToString(vector) + "\n");
          }
        }

      } // if (RootbeerGpu.getThreadId() == 0)

      // Sync all blocks Inter-Block Synchronization
      RootbeerGpu.syncblocks(5);

    } // if (((i + 1) % m_skipCount == 0) && (m_peerCount > 0))
  }

  private int divup(int x, int y) {
    if (x % y != 0) {
      return ((x + y - 1) / y); // round up
    } else {
      return x / y;
    }
  }

  private int roundUpToNextPowerOfTwo(int x) {
    x--;
    x |= x >> 1; // handle 2 bit numbers
    x |= x >> 2; // handle 4 bit numbers
    x |= x >> 4; // handle 8 bit numbers
    x |= x >> 8; // handle 16 bit numbers
    x |= x >> 16; // handle 32 bit numbers
    x++;
    return x;
  }

  private String arrayToString(double[] arr, int dimension) {
    if (arr != null) {
      if (dimension <= 0) {
        dimension = arr.length;
      }
      String result = "";
      for (int i = 0; i < dimension; i++) {
        result += (i + 1 == dimension) ? arr[i] : (arr[i] + ",");
      }
      return result;
    }
    return "null";
  }

  public static void main(String[] args) {
    // Dummy invocation
    // otherwise Rootbeer will remove constructors and methods
    new OnlineCFTrainHybridKernel(null, null, null, null, null, null, null, 0,
        0, 0, 0, 0, null, 0, 0, 0, null);
    new GpuIntegerMap().getList();
  }
}
