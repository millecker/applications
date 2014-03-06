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

import org.trifort.rootbeer.runtime.Kernel;
import org.trifort.rootbeer.runtime.RootbeerGpu;

public class OnlineCFTrainHybridKernel implements Kernel {

  private UserItemMap m_userItemMap;
  private VectorMap m_usersMatrix;
  private VectorMap m_itemsMatrix;
  private int m_N;
  private int m_M;
  private double m_ALPHA;
  private int m_matrixRank;
  private int m_maxIterations;
  private int m_skipCount;
  private String[] m_allPeerNames;

  public OnlineCFTrainHybridKernel(UserItemMap userItemMap,
      VectorMap usersMatrix, VectorMap itemsMatrix, int n, int m, double alpha,
      int matrixRank, int maxIterations, int skipCount, String[] allPeerNames) {
    this.m_userItemMap = userItemMap;
    this.m_usersMatrix = usersMatrix;
    this.m_itemsMatrix = itemsMatrix;
    this.m_N = n;
    this.m_M = m;
    this.m_ALPHA = alpha;
    this.m_matrixRank = matrixRank;
    this.m_maxIterations = maxIterations;
    this.m_skipCount = skipCount;
    this.m_allPeerNames = allPeerNames;
  }

  public void gpuMethod() {
    int blockSize = RootbeerGpu.getBlockDimx();
    int gridSize = RootbeerGpu.getGridDimx();
    int block_idxx = RootbeerGpu.getBlockIdxx();
    int thread_idxx = RootbeerGpu.getThreadIdxx();

    int usersPerBlock = divup(m_N, gridSize);
    int itemsPerBlock = divup(m_M, gridSize);

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
    // 1 x Integer (4 bytes)
    int shmInputIdStartPos = shmExpectedScoreStartPos + 8;
    // 1 x Boolean (1 byte)
    int shmInputIsNullStartPos = shmInputIdStartPos + 4;

    // DEBUG
    if (RootbeerGpu.getThreadId() == 0) {
      System.out.println("blockSize: " + blockSize);
      System.out.println("gridSize: " + gridSize);
      System.out.println("usersPerBlock: " + usersPerBlock);
      System.out.println("itemsPerBlock: " + itemsPerBlock);
    }

    // Start OnlineCF algorithm
    for (int i = 0; i < m_maxIterations; i++) {

      // **********************************************************************
      // Compute U (Users)
      // **********************************************************************
      // Loop over all usersPerBlock
      for (int u = 0; u < usersPerBlock; u++) {

        // Thread 0 of each block prepare SharedMemory
        if (thread_idxx == 0) {

          int userId = (block_idxx * usersPerBlock) + u + 1; // starting with 1
          RootbeerGpu.setSharedInteger(shmInputIdStartPos, userId);

          double[] userVector = m_usersMatrix.get(userId);
          if (userVector != null) {
            RootbeerGpu.setSharedBoolean(shmInputIsNullStartPos, false);

            // Setup userVector
            // TODO Maybe setup userVector by all threads?
            for (int j = 0; j < m_matrixRank; j++) {
              int userVectorIndex = shmUserVectorStartPos + j * 8;
              RootbeerGpu.setSharedDouble(userVectorIndex, userVector[j]);
            }
            System.out.print("userVector: ");
            System.out.println(arrayToString(userVector));

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
          for (int itemId = 1; itemId <= m_M; itemId++) {

            if (thread_idxx == 0) {

              // Setup expectedScore
              Double expectedScore = m_userItemMap.get(
                  RootbeerGpu.getSharedInteger(shmInputIdStartPos), itemId);
              if (expectedScore != null) {
                RootbeerGpu.setSharedDouble(shmExpectedScoreStartPos,
                    expectedScore);

                // Setup itemVector on SharedMemory
                double[] itemVector = m_itemsMatrix.get(itemId);
                for (int j = 0; j < m_matrixRank; j++) {
                  int itemVectorIndex = shmItemVectorStartPos + j * 8;
                  RootbeerGpu.setSharedDouble(itemVectorIndex, itemVector[j]);
                }
                System.out.print("itemVector: ");
                System.out.println(arrayToString(itemVector));
                System.out.print("expectedScore: ");
                System.out.println(RootbeerGpu
                    .getSharedDouble(shmExpectedScoreStartPos));

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
              for (int s = divup(m_matrixRank, 2); s > 0; s >>= 1) {

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
            System.out.print("Update userVector: ");
            double[] newUserVector = new double[m_matrixRank];
            for (int j = 0; j < m_matrixRank; j++) {
              int userVectorIndex = shmUserVectorStartPos + j * 8;
              newUserVector[j] = RootbeerGpu.getSharedDouble(userVectorIndex);
              System.out.print(newUserVector[j] + " ");
            }
            System.out.println();

            m_usersMatrix.put(RootbeerGpu.getSharedInteger(shmInputIdStartPos),
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
      for (int v = 0; v < itemsPerBlock; v++) {

        // Thread 0 of each block prepare SharedMemory
        if (thread_idxx == 0) {

          int itemId = (block_idxx * itemsPerBlock) + v + 1; // starting with 1
          RootbeerGpu.setSharedInteger(shmInputIdStartPos, itemId);

          double[] itemVector = m_itemsMatrix.get(itemId);
          if (itemVector != null) {
            RootbeerGpu.setSharedBoolean(shmInputIsNullStartPos, false);

            // Setup itemVector
            // TODO Maybe setup itemVector by all threads?
            for (int j = 0; j < m_matrixRank; j++) {
              int itemVectorIndex = shmItemVectorStartPos + j * 8;
              RootbeerGpu.setSharedDouble(itemVectorIndex, itemVector[j]);
            }
            System.out.print("itemVector: ");
            System.out.println(arrayToString(itemVector));

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
          for (int userId = 1; userId <= m_N; userId++) {

            if (thread_idxx == 0) {

              // Setup expectedScore
              Double expectedScore = m_userItemMap.get(userId,
                  RootbeerGpu.getSharedInteger(shmInputIdStartPos));
              if (expectedScore != null) {
                RootbeerGpu.setSharedDouble(shmExpectedScoreStartPos,
                    expectedScore);

                // Setup userVector on SharedMemory
                double[] userVector = m_usersMatrix.get(userId);
                for (int j = 0; j < m_matrixRank; j++) {
                  int userVectorIndex = shmUserVectorStartPos + j * 8;
                  RootbeerGpu.setSharedDouble(userVectorIndex, userVector[j]);
                }
                System.out.print("userVector: ");
                System.out.println(arrayToString(userVector));
                System.out.print("expectedScore: ");
                System.out.println(RootbeerGpu
                    .getSharedDouble(shmExpectedScoreStartPos));

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
              for (int s = divup(m_matrixRank, 2); s > 0; s >>= 1) {

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

          // Thread 0 of each block updates userVector
          if (thread_idxx == 0) {
            System.out.print("Update itemVector: ");
            double[] newItemVector = new double[m_matrixRank];
            for (int j = 0; j < m_matrixRank; j++) {
              int itemVectorIndex = shmItemVectorStartPos + j * 8;
              newItemVector[j] = RootbeerGpu.getSharedDouble(itemVectorIndex);
              System.out.print(newItemVector[j] + " ");
            }
            System.out.println();

            m_itemsMatrix.put(RootbeerGpu.getSharedInteger(shmInputIdStartPos),
                newItemVector);
          }

        } // if userVector != null

      } // loop over all usersPerBlock

      // Sync all blocks Inter-Block Synchronization
      RootbeerGpu.syncblocks(2);

      // **********************************************************************
      // TODO normalizeWithBroadcastingValues
      // **********************************************************************
      if ( (RootbeerGpu.getThreadId() == 0) && ((i + 1) % m_skipCount == 0) ) {
/*
        // Step 1)
        // send item factorized matrices to selected peers
        int peerCount = HamaPeer.getNumPeers();
        // item factorized values should be normalized
        int peerId = HamaPeer.getPeerIndex();

        for (Map.Entry<Long, PipesVectorWritable> item : m_itemsMatrix
            .entrySet()) {
          peer.send(peer.getPeerName(item.getKey().hashCode() % peerCount),
              new ItemMessage(peerId, item.getKey().longValue(), item
                  .getValue().getVector()));
        }
        HamaPeer.sync();

        // Step 2)
        // receive item factorized matrices if this peer is selected and
        // normalize
        // them
        HashMap<Long, LinkedList<Integer>> senderList = new HashMap<Long, LinkedList<Integer>>();
        HashMap<Long, DoubleVector> normalizedValues = new HashMap<Long, DoubleVector>();
        HashMap<Long, Integer> normalizedValueCount = new HashMap<Long, Integer>();

        ItemMessage msg;
        while ((msg = peer.getCurrentMessage()) != null) {
          int senderId = msg.getSenderId();
          long itemId = msg.getItemId();
          DoubleVector vector = msg.getVector();

          if (normalizedValues.containsKey(itemId) == false) {
            normalizedValues.put(itemId, new DenseDoubleVector(m_matrixRank,
                0.0));
            normalizedValueCount.put(itemId, 0);
            senderList.put(itemId, new LinkedList<Integer>());
          }

          normalizedValues
              .put(itemId, normalizedValues.get(itemId).add(vector));
          normalizedValueCount
              .put(itemId, normalizedValueCount.get(itemId) + 1);
          senderList.get(itemId).add(senderId);
        }

        // normalize
        for (Map.Entry<Long, DoubleVector> e : normalizedValues.entrySet()) {
          double count = normalizedValueCount.get(e.getKey());
          e.setValue(e.getValue().multiply(1.0 / count));
        }

        // Step 3)
        // send back normalized values to senders
        for (Map.Entry<Long, DoubleVector> e : normalizedValues.entrySet()) {
          msg = new ItemMessage(peerId, e.getKey(), e.getValue());

          // send to interested peers
          Iterator<Integer> iter = senderList.get(e.getKey()).iterator();
          while (iter.hasNext()) {
            peer.send(peer.getPeerName(iter.next()), msg);
          }
        }
        peer.sync();

        // Step 4)
        // receive already normalized and synced data
        while ((msg = peer.getCurrentMessage()) != null) {
          m_itemsMatrix.put(msg.getItemId(),
              new PipesVectorWritable(msg.getVector()));
        }
*/
      }
    }
  }

  private int divup(int x, int y) {
    if (x % y != 0) {
      return ((x + y - 1) / y); // round up
    } else {
      return x / y;
    }
  }

  private String arrayToString(double[] arr) {
    if (arr != null) {
      String result = "[";
      for (int i = 0; i < arr.length; i++) {
        result += (i + 1 == arr.length) ? arr[i] : (arr[i] + ",");
      }
      result += "]";
      return result;
    }
    return "null";
  }

  public static void main(String[] args) {
    // Dummy constructor invocation
    // to keep kernel constructor in
    // rootbeer transformation
    new OnlineCFTrainHybridKernel(null, null, null, 0, 0, 0, 0, 0, 0, null);
    new UserItemMap().put(0, 0, 0);
    new VectorMap().put(0, null);
  }
}
