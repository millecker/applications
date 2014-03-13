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
import org.trifort.rootbeer.runtime.KeyValuePair;
import org.trifort.rootbeer.runtime.RootbeerGpu;

/**
 * Collaborative Filtering based on
 * 
 * Singular Value Decomposition for Collaborative Filtering on a GPU
 * http://iopscience.iop.org/1757-899X/10/1/012017/pdf/1757-899X_10_1_012017.pdf
 * 
 */
public class OnlineCFTrainHybridKernel implements Kernel {

  private GpuUserItemMap m_userItemMap;
  private GpuVectorMap m_usersMatrix;
  private GpuVectorMap m_itemsMatrix;
  private long m_N;
  private long m_M;
  private double m_ALPHA;
  private int m_matrixRank;
  private int m_maxIterations;
  private int m_skipCount;
  private int m_peerCount = 0;
  private int m_peerId = 0;
  private String[] m_allPeerNames;
  private GpuIntegerMap m_counterMap;
  private GpuIntegerListMap m_senderMap;

  public OnlineCFTrainHybridKernel(GpuUserItemMap userItemMap,
      GpuVectorMap usersMatrix, GpuVectorMap itemsMatrix,
      GpuIntegerMap counterMap, long n, long m, double alpha, int matrixRank,
      int maxIterations, int skipCount, int peerCount, int peerId,
      String[] allPeerNames) {
    this.m_userItemMap = userItemMap;
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
    int itemsMatrixSize = m_itemsMatrix.size();
    this.m_counterMap = counterMap;
    this.m_senderMap = new GpuIntegerListMap(itemsMatrixSize);
  }

  public void gpuMethod() {
    int blockSize = RootbeerGpu.getBlockDimx();
    int gridSize = RootbeerGpu.getGridDimx();
    int block_idxx = RootbeerGpu.getBlockIdxx();
    int thread_idxx = RootbeerGpu.getThreadIdxx();

    if (blockSize < m_matrixRank) {
      return; // TODO Error
    }

    long usersPerBlock = divup(m_N, gridSize);
    long itemsPerBlock = divup(m_M, gridSize);

    // SharedMemory per block
    int shmStartPos = 0;
    // userVector: matrixRank x Doubles (m_matrixRank * 8 bytes)
    int shmUserVectorStartPos = shmStartPos;
    // itemVector: matrixRank x Doubles (m_matrixRank * 8 bytes)
    int shmItemVectorStartPos = shmUserVectorStartPos + m_matrixRank * 8;
    // multVector: matrixRank x Doubles (m_matrixRank * 8 bytes)
    int shmMultVectorStartPos = shmItemVectorStartPos + m_matrixRank * 8;
    // 1 x Double (8 bytes)
    int shmExpectedScoreStartPos = shmMultVectorStartPos + m_matrixRank * 8;
    // 1 x Long (8 bytes)
    int shmInputIdStartPos = shmExpectedScoreStartPos + 8;
    // 1 x Boolean (1 byte)
    int shmInputIsNullStartPos = shmInputIdStartPos + 8;

    // DEBUG
    if (RootbeerGpu.getThreadId() == 0) {
      System.out.println("blockSize: " + blockSize);
      System.out.println("gridSize: " + gridSize);
      System.out.println("usersPerBlock: " + usersPerBlock);
      System.out.println("itemsPerBlock: " + itemsPerBlock);
      System.out.println("peerCount: " + m_peerCount);
      System.out.println("peerId: " + m_peerId);
    }

    // Start OnlineCF algorithm
    for (int i = 0; i < m_maxIterations; i++) {

      // **********************************************************************
      // Compute U (Users)
      // **********************************************************************
      // Loop over all usersPerBlock
      for (long u = 0; u < usersPerBlock; u++) {

        // Thread 0 of each block prepare SharedMemory
        if (thread_idxx == 0) {

          long userId = (block_idxx * usersPerBlock) + u + 1; // starting with 1
          RootbeerGpu.setSharedLong(shmInputIdStartPos, userId);

          double[] userVector = m_usersMatrix.get(userId);
          if (userVector != null) {
            RootbeerGpu.setSharedBoolean(shmInputIsNullStartPos, false);

            // Setup userVector
            for (int j = 0; j < m_matrixRank; j++) {
              int userVectorIndex = shmUserVectorStartPos + j * 8;
              RootbeerGpu.setSharedDouble(userVectorIndex, userVector[j]);
            }
            // DEBUG
            // System.out.print("userVector: ");
            // System.out.println(arrayToString(userVector));

            // Init multVector
            // TODO maybe useless
            for (int j = 0; j < m_matrixRank; j++) {
              int multVectorIndex = shmMultVectorStartPos + j * 8;
              RootbeerGpu.setSharedDouble(multVectorIndex, 0);
            }

          } else {
            RootbeerGpu.setSharedBoolean(shmInputIsNullStartPos, true);
          }

        }
        // Sync all threads within a block
        RootbeerGpu.syncthreads();

        // if userVector != null
        if (!RootbeerGpu.getSharedBoolean(shmInputIsNullStartPos)) {

          // Each user loops over all items
          for (long itemId = 1; itemId <= m_M; itemId++) {

            if (thread_idxx == 0) {

              // Setup expectedScore
              Double expectedScore = m_userItemMap.get(
                  RootbeerGpu.getSharedLong(shmInputIdStartPos), itemId);
              if (expectedScore != null) {
                RootbeerGpu.setSharedDouble(shmExpectedScoreStartPos,
                    expectedScore);

                // Setup itemVector on SharedMemory
                double[] itemVector = m_itemsMatrix.get(itemId);
                for (int j = 0; j < m_matrixRank; j++) {
                  int itemVectorIndex = shmItemVectorStartPos + j * 8;
                  RootbeerGpu.setSharedDouble(itemVectorIndex, itemVector[j]);
                }
                // DEBUG
                // System.out.print("itemVector: ");
                // System.out.println(arrayToString(itemVector));
                // System.out.print("expectedScore: ");
                // System.out.println(RootbeerGpu
                // .getSharedDouble(shmExpectedScoreStartPos));

              } else {
                RootbeerGpu.setSharedDouble(shmExpectedScoreStartPos, 0);
              }
            }

            // Sync all threads within a block
            RootbeerGpu.syncthreads();

            // if expectedScore != 0
            if (RootbeerGpu.getSharedDouble(shmExpectedScoreStartPos) != 0) {

              // Each thread within a block computes one multiplication
              if (thread_idxx < m_matrixRank) {

                int userVectorIndex = shmUserVectorStartPos + thread_idxx * 8;
                double userVal = RootbeerGpu.getSharedDouble(userVectorIndex);

                int itemVectorIndex = shmItemVectorStartPos + thread_idxx * 8;
                double itemVal = RootbeerGpu.getSharedDouble(itemVectorIndex);

                int multVectorIndex = shmMultVectorStartPos + thread_idxx * 8;
                RootbeerGpu.setSharedDouble(multVectorIndex, userVal * itemVal);
              }

              // Sync all threads within a block
              RootbeerGpu.syncthreads();

              // Calculate score by summing up multiplications
              // do reduction in shared memory
              // 1-bit right shift = divide by two to the power 1
              int shmMultVectorEndPos = shmMultVectorStartPos + m_matrixRank
                  * 8;
              for (int s = (int) divup(m_matrixRank, 2); s > 0; s >>= 1) {

                if (thread_idxx < s) {
                  // sh_mem[ltid] += sh_mem[ltid + s];
                  int multVectorIndex1 = shmMultVectorStartPos + thread_idxx
                      * 8;
                  int multVectorIndex2 = shmMultVectorStartPos
                      + (thread_idxx + s) * 8;
                  double val1 = RootbeerGpu.getSharedDouble(multVectorIndex1);
                  double val2 = 0;
                  if (multVectorIndex2 < shmMultVectorEndPos) {
                    val2 = RootbeerGpu.getSharedDouble(multVectorIndex2);
                  }
                  RootbeerGpu.setSharedDouble(multVectorIndex1, val1 + val2);
                }
                // Sync all threads within a block
                RootbeerGpu.syncthreads();
              }

              // Calculate new userVector
              // Each thread does one update operation of vector u
              if (thread_idxx < m_matrixRank) {

                int userVectorIndex = shmUserVectorStartPos + thread_idxx * 8;
                double userVal = RootbeerGpu.getSharedDouble(userVectorIndex);

                int itemVectorIndex = shmItemVectorStartPos + thread_idxx * 8;
                double itemVal = RootbeerGpu.getSharedDouble(itemVectorIndex);

                double expectedScore = RootbeerGpu
                    .getSharedDouble(shmExpectedScoreStartPos);

                double calculatedScore = RootbeerGpu
                    .getSharedDouble(shmMultVectorStartPos);

                userVal += 2 * m_ALPHA * itemVal
                    * (expectedScore - calculatedScore);

                RootbeerGpu.setSharedDouble(userVectorIndex, userVal);
              }

              // Sync all threads within a block
              RootbeerGpu.syncthreads();

            } // if expectedScore != 0

          } // loop over all items

          // Thread 0 of each block updates userVector
          if (thread_idxx == 0) {
            // DEBUG
            // System.out.print("Update userVector: ");
            double[] newUserVector = new double[m_matrixRank];
            for (int j = 0; j < m_matrixRank; j++) {
              int userVectorIndex = shmUserVectorStartPos + j * 8;
              newUserVector[j] = RootbeerGpu.getSharedDouble(userVectorIndex);
              // System.out.print(newUserVector[j] + " ");
            }
            // System.out.println();

            m_usersMatrix.put(RootbeerGpu.getSharedLong(shmInputIdStartPos),
                newUserVector);
          }

        } // if userVector != null

      } // loop over all usersPerBlock

      // Sync all blocks Inter-Block Synchronization
      RootbeerGpu.syncblocks(1);

      // **********************************************************************
      // Compute V (Items)
      // **********************************************************************
      // Loop over all itemsPerBlock
      for (long v = 0; v < itemsPerBlock; v++) {

        // Thread 0 of each block prepare SharedMemory
        if (thread_idxx == 0) {

          long itemId = (block_idxx * itemsPerBlock) + v + 1; // starting with 1
          RootbeerGpu.setSharedLong(shmInputIdStartPos, itemId);

          double[] itemVector = m_itemsMatrix.get(itemId);
          if (itemVector != null) {
            RootbeerGpu.setSharedBoolean(shmInputIsNullStartPos, false);

            // Setup itemVector
            for (int j = 0; j < m_matrixRank; j++) {
              int itemVectorIndex = shmItemVectorStartPos + j * 8;
              RootbeerGpu.setSharedDouble(itemVectorIndex, itemVector[j]);
            }
            // DEBUG
            // System.out.print("itemVector: ");
            // System.out.println(arrayToString(itemVector));

            // Init multVector
            // TODO maybe useless
            for (int j = 0; j < m_matrixRank; j++) {
              int multVectorIndex = shmMultVectorStartPos + j * 8;
              RootbeerGpu.setSharedDouble(multVectorIndex, 0);
            }

          } else {
            RootbeerGpu.setSharedBoolean(shmInputIsNullStartPos, true);
          }

        }
        // Sync all threads within a block
        RootbeerGpu.syncthreads();

        // if itemVector != null
        if (!RootbeerGpu.getSharedBoolean(shmInputIsNullStartPos)) {

          // Each user loops over all items
          for (long userId = 1; userId <= m_N; userId++) {

            if (thread_idxx == 0) {

              // Setup expectedScore
              Double expectedScore = m_userItemMap.get(userId,
                  RootbeerGpu.getSharedLong(shmInputIdStartPos));
              if (expectedScore != null) {
                RootbeerGpu.setSharedDouble(shmExpectedScoreStartPos,
                    expectedScore);

                // Setup userVector on SharedMemory
                double[] userVector = m_usersMatrix.get(userId);
                for (int j = 0; j < m_matrixRank; j++) {
                  int userVectorIndex = shmUserVectorStartPos + j * 8;
                  RootbeerGpu.setSharedDouble(userVectorIndex, userVector[j]);
                }
                // DEBUG
                // System.out.print("userVector: ");
                // System.out.println(arrayToString(userVector));
                // System.out.print("expectedScore: ");
                // System.out.println(RootbeerGpu
                // .getSharedDouble(shmExpectedScoreStartPos));

              } else {
                RootbeerGpu.setSharedDouble(shmExpectedScoreStartPos, 0);
              }
            }

            // Sync all threads within a block
            RootbeerGpu.syncthreads();

            // if expectedScore != 0
            if (RootbeerGpu.getSharedDouble(shmExpectedScoreStartPos) != 0) {

              // Each thread within a block computes one multiplication
              if (thread_idxx < m_matrixRank) {

                int itemVectorIndex = shmItemVectorStartPos + thread_idxx * 8;
                double itemVal = RootbeerGpu.getSharedDouble(itemVectorIndex);

                int userVectorIndex = shmUserVectorStartPos + thread_idxx * 8;
                double userVal = RootbeerGpu.getSharedDouble(userVectorIndex);

                int multVectorIndex = shmMultVectorStartPos + thread_idxx * 8;
                RootbeerGpu.setSharedDouble(multVectorIndex, itemVal * userVal);
              }

              // Sync all threads within a block
              RootbeerGpu.syncthreads();

              // Calculate score by summing up multiplications
              // do reduction in shared memory
              // 1-bit right shift = divide by two to the power 1
              int shmMultVectorEndPos = shmMultVectorStartPos + m_matrixRank
                  * 8;
              for (int s = (int) divup(m_matrixRank, 2); s > 0; s >>= 1) {

                if (thread_idxx < s) {
                  // sh_mem[ltid] += sh_mem[ltid + s];
                  int multVectorIndex1 = shmMultVectorStartPos + thread_idxx
                      * 8;
                  int multVectorIndex2 = shmMultVectorStartPos
                      + (thread_idxx + s) * 8;
                  double val1 = RootbeerGpu.getSharedDouble(multVectorIndex1);
                  double val2 = 0;
                  if (multVectorIndex2 < shmMultVectorEndPos) {
                    val2 = RootbeerGpu.getSharedDouble(multVectorIndex2);
                  }
                  RootbeerGpu.setSharedDouble(multVectorIndex1, val1 + val2);
                }
                // Sync all threads within a block
                RootbeerGpu.syncthreads();
              }

              // Calculate new itemVector
              // Each thread does one update operation of vector u
              if (thread_idxx < m_matrixRank) {

                int itemVectorIndex = shmItemVectorStartPos + thread_idxx * 8;
                double itemVal = RootbeerGpu.getSharedDouble(itemVectorIndex);

                int userVectorIndex = shmUserVectorStartPos + thread_idxx * 8;
                double userVal = RootbeerGpu.getSharedDouble(userVectorIndex);

                double expectedScore = RootbeerGpu
                    .getSharedDouble(shmExpectedScoreStartPos);

                double calculatedScore = RootbeerGpu
                    .getSharedDouble(shmMultVectorStartPos);

                itemVal += 2 * m_ALPHA * userVal
                    * (expectedScore - calculatedScore);

                RootbeerGpu.setSharedDouble(itemVectorIndex, itemVal);
              }

              // Sync all threads within a block
              RootbeerGpu.syncthreads();

            } // if expectedScore != 0

          } // loop over all items

          // Thread 0 of each block updates itemVector
          if (thread_idxx == 0) {
            // DEBUG
            // System.out.print("Update itemVector: ");
            double[] newItemVector = new double[m_matrixRank];
            for (int j = 0; j < m_matrixRank; j++) {
              int itemVectorIndex = shmItemVectorStartPos + j * 8;
              newItemVector[j] = RootbeerGpu.getSharedDouble(itemVectorIndex);
              // System.out.print(newItemVector[j] + " ");
            }
            // System.out.println();

            m_itemsMatrix.put(RootbeerGpu.getSharedLong(shmInputIdStartPos),
                newItemVector);
          }

        } // if itemVector != null

      } // loop over all itemsPerBlock

      // Sync all blocks Inter-Block Synchronization
      RootbeerGpu.syncblocks(2);

      // **********************************************************************
      // normalizeWithBroadcastingValues
      // **********************************************************************
      if (((i + 1) % m_skipCount == 0) && (m_peerCount > 1)) {

        // Only global Thread 0
        if (RootbeerGpu.getThreadId() == 0) {

          // clear sender map
          m_senderMap.clear();

          // Step 1)
          // send item matrices to selected peers
          for (long itemId = 1; itemId <= m_M; itemId++) {

            int toPeerId = (int) itemId % m_peerCount;
            // don't send item to itself
            if (toPeerId != m_peerId) {
              // init Counter
              m_counterMap.put(itemId, 0);

              double[] vector = m_itemsMatrix.get(itemId);

              // ItemMessage (senderId,itemId,itemVector)
              // e.g.,
              // 0,1,0.622676719363376,0.47894004113535393,0.9099409696184495
              StringBuilder message = new StringBuilder();
              message.append(Integer.toString(m_peerId));
              message.append(",");
              message.append(Long.toString(itemId));
              message.append(",");
              for (int d = 0; d < m_matrixRank; d++) {
                message.append(Double.toString(vector[d]));
                if (d + 1 < m_matrixRank) {
                  message.append(",");
                }
              }
              String messageStr = message.toString();
              // System.out.println(messageStr); // might cause an error

              // System.out.println("sendItem itemId: " + itemId + " toPeerId: "
              // + toPeerId + " value: "
              // + arrayToString(vector) + "\n");

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
            long itemId = Long.parseLong(values[1]);
            int dim = values.length - 2;
            double[] vector = new double[dim];
            for (int d = 0; d < dim; d++) {
              vector[d] = Double.parseDouble(values[d + 2]);
            }

            // System.out.println("receiveItem itemId: " + itemId
            // + " fromPeerId: " + senderId + " value: "
            // + arrayToString(vector) + "\n");

            m_itemsMatrix.add(itemId, vector);
            m_counterMap.add(itemId, 1);
            m_senderMap.put(itemId, senderId);
          }

        } // RootbeerGpu.getThreadId() == 0

        RootbeerGpu.threadfenceSystem();
        // Sync all blocks Inter-Block Synchronization
        RootbeerGpu.syncblocks(3);

        // Step 3)
        // normalize (messages with counters)
        // Loop over all itemsPerBlock
        for (long v = 0; v < itemsPerBlock; v++) {

          // Thread 0 of each block prepare SharedMemory
          if (thread_idxx == 0) {

            // starting with index 1
            long itemId = (block_idxx * itemsPerBlock) + v + 1;
            RootbeerGpu.setSharedLong(shmInputIdStartPos, itemId);
            // System.out.println("itemId: "+itemId);

            double[] itemVector = m_itemsMatrix.get(itemId);
            int counter = m_counterMap.get(itemId);
            if ((itemVector != null) && (counter > 1)) {
              RootbeerGpu.setSharedBoolean(shmInputIsNullStartPos, false);

              // Setup itemVector
              for (int j = 0; j < m_matrixRank; j++) {
                int itemVectorIndex = shmItemVectorStartPos + j * 8;
                RootbeerGpu.setSharedDouble(itemVectorIndex, itemVector[j]);
              }
              // System.out.print("message itemVector: ");
              // System.out.println(arrayToString(itemVector));

              // Init shmExpectedScoreStartPos for counter
              RootbeerGpu.setSharedDouble(shmExpectedScoreStartPos, counter);
              // System.out.print("message counter: ");
              // System.out.println(counter);

            } else {
              RootbeerGpu.setSharedBoolean(shmInputIsNullStartPos, true);
            }

          } // if (thread_idxx == 0)

          // Sync all threads within a block
          RootbeerGpu.syncthreads();

          // if itemVector != null
          if (!RootbeerGpu.getSharedBoolean(shmInputIsNullStartPos)) {

            // Each thread within a block
            if (thread_idxx < m_matrixRank) {

              int itemVectorIndex = shmItemVectorStartPos + thread_idxx * 8;
              double itemVal = RootbeerGpu.getSharedDouble(itemVectorIndex);

              double count = RootbeerGpu
                  .getSharedDouble(shmExpectedScoreStartPos);
              RootbeerGpu.setSharedDouble(itemVectorIndex, itemVal / count);
            }

            // Sync all threads within a block
            RootbeerGpu.syncthreads();

            // Thread 0 of each block updates itemVector
            if (thread_idxx == 0) {
              // DEBUG
              // System.out.print("Update Normalized itemVector: ");
              double[] newItemVector = new double[m_matrixRank];
              for (int j = 0; j < m_matrixRank; j++) {
                int itemVectorIndex = shmItemVectorStartPos + j * 8;
                newItemVector[j] = RootbeerGpu.getSharedDouble(itemVectorIndex);
                // System.out.print(newItemVector[j] + " ");
              }
              // System.out.println();

              m_itemsMatrix.put(RootbeerGpu.getSharedLong(shmInputIdStartPos),
                  newItemVector);
            }

            // Sync all threads within a block
            RootbeerGpu.syncthreads();

          } // if itemVector != null

        } // loop over all itemsPerBlock

        // Sync all blocks Inter-Block Synchronization
        RootbeerGpu.syncblocks(4);

        // Only global Thread 0
        if (RootbeerGpu.getThreadId() == 0) {

          // Step 4)
          // send back normalized values to senders
          for (long itemId = 1; itemId <= m_M; itemId++) {

            // only send own items
            if (m_peerId == itemId % m_peerCount) {

              double[] vector = m_itemsMatrix.get(itemId);
              if (vector != null) {
                // ItemMessage (senderId,itemId,itemVector)
                // e.g.,
                // 0,1,0.622676719363376,0.47894004113535393,0.9099409696184495
                StringBuilder message = new StringBuilder();
                message.append(Integer.toString(m_peerId));
                message.append(",");
                message.append(Long.toString(itemId));
                message.append(",");
                for (int d = 0; d < m_matrixRank; d++) {
                  message.append(Double.toString(vector[d]));
                  if (d + 1 < m_matrixRank) {
                    message.append(",");
                  }
                }
                String messageStr = message.toString();
                // System.out.println(messageStr); // Error will break

                // send to interested peers
                KeyValuePair pair = m_senderMap.getList(itemId);
                while (pair != null) {
                  int toPeerId = (Integer) pair.getValue();

                  // System.out.println("sendNormalizedBack itemId: " + itemId
                  // + " toPeerId: " + toPeerId + " value: "
                  // + arrayToString(vector) + "\n");

                  HamaPeer.send(m_allPeerNames[toPeerId], messageStr);

                  pair = pair.getNext();
                }
              }
            }
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
            long itemId = Long.parseLong(values[1]);
            int dim = values.length - 2;
            double[] vector = new double[dim];
            for (int d = 0; d < dim; d++) {
              vector[d] = Double.parseDouble(values[d + 2]);
            }

            m_itemsMatrix.put(itemId, vector);

            // System.out.println("updateItems itemId: " + itemId + " value: "
            // + arrayToString(vector) + "\n");
          }

        } // if (RootbeerGpu.getThreadId() == 0)

        RootbeerGpu.threadfenceSystem();
        // Sync all blocks Inter-Block Synchronization
        RootbeerGpu.syncblocks(5);

      } // if (((i + 1) % m_skipCount == 0) && (m_peerCount > 0))

    }
  }

  private long divup(long x, long y) {
    if (x % y != 0) {
      return ((x + y - 1) / y); // round up
    } else {
      return x / y;
    }
  }

  private String arrayToString(double[] arr) {
    if (arr != null) {
      String result = "";
      for (int i = 0; i < arr.length; i++) {
        result += (i + 1 == arr.length) ? arr[i] : (arr[i] + ",");
      }
      return result;
    }
    return "null";
  }

  public static void main(String[] args) {
    // Dummy invocation
    // otherwise Rootbeer will remove constructors and methods
    new OnlineCFTrainHybridKernel(null, null, null, null, 0, 0, 0, 0, 0, 0, 0,
        0, null);
    new GpuUserItemMap().put(0, 0, 0);
    new GpuVectorMap().put(0, null);
    new GpuIntegerMap();
  }
}
