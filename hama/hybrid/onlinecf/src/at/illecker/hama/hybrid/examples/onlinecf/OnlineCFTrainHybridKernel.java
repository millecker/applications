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
  private String[] m_allPeerNames;

  public OnlineCFTrainHybridKernel(UserItemMap userItemMap,
      VectorMap usersMatrix, VectorMap itemsMatrix, int n, int m, double alpha,
      int matrixRank, int maxIterations, String[] allPeerNames) {
    this.m_userItemMap = userItemMap;
    this.m_usersMatrix = usersMatrix;
    this.m_itemsMatrix = itemsMatrix;
    this.m_N = n;
    this.m_M = m;
    this.m_ALPHA = alpha;
    this.m_matrixRank = matrixRank;
    this.m_maxIterations = maxIterations;
    this.m_allPeerNames = allPeerNames;
  }

  public void gpuMethod() {
    int blockSize = RootbeerGpu.getBlockDimx();
    int gridSize = RootbeerGpu.getGridDimx();
    int block_idxx = RootbeerGpu.getBlockIdxx();
    int thread_idxx = RootbeerGpu.getThreadIdxx();

    int userInputSize = divup(m_M, gridSize);
    int itemInputSize = divup(m_N, gridSize);

    // SharedMemory per block
    int shmStartPos = 0;
    // userVector: matrixRank x Doubles (m_matrixRank * 8 bytes)
    int shmUserVectorStartPos = shmStartPos;
    // itemVector: matrixRank x Doubles (m_matrixRank * 8 bytes)
    int shmItemVectorStartPos = shmUserVectorStartPos + m_matrixRank * 8;
    // multVector: matrixRank x Doubles (m_matrixRank * 8 bytes)
    int shmMultVectorStartPos = shmItemVectorStartPos + m_matrixRank * 8;
    // 1 x Double (8 bytes)
    // TODO not needed, calcScore is located in shmMultVector at index 0
    int shmCalcScoreStartPos = shmMultVectorStartPos + m_matrixRank * 8;
    // 1 x Double (8 bytes)
    int shmEstScoreStartPos = shmCalcScoreStartPos + 8;

    // Start OnlineCF algorithm
    for (int i = 0; i < m_maxIterations; i++) {

      // Thread 0 of each block prepare SharedMemory
      if (thread_idxx == 0) {

        // Setup userVector
        // TODO FIX block_idxx + 1 (multiple users per block)
        double[] userVector = m_usersMatrix.get(block_idxx + 1);
        for (int j = 0; j < m_matrixRank; j++) {
          int userVectorIndex = shmUserVectorStartPos + j * 8;
          RootbeerGpu.setSharedDouble(userVectorIndex, userVector[j]);
        }

        // Init multVector
        // TODO MAYBE UNUSED
        for (int j = 0; j < m_matrixRank; j++) {
          int multVectorIndex = shmMultVectorStartPos + j * 8;
          RootbeerGpu.setSharedDouble(multVectorIndex, 0);
        }

        // Init calculatedScore
        // TODO UNUSED
        RootbeerGpu.setSharedDouble(shmCalcScoreStartPos, 0);
      }
      // Sync all threads within a block
      RootbeerGpu.syncthreads();

      // **********************************************************************
      // Compute U (Users)
      // **********************************************************************
      for (int itemId = 1; itemId <= m_M; itemId++) {

        if (thread_idxx == 0) {
          // Setup itemVector on SharedMemory
          double[] itemVector = m_itemsMatrix.get(itemId);
          for (int j = 0; j < m_matrixRank; j++) {
            int itemVectorIndex = shmItemVectorStartPos + j * 8;
            RootbeerGpu.setSharedDouble(itemVectorIndex, itemVector[j]);
          }

          // Setup expectedScore
          // TODO FIX block_idxx + 1 (multiple users per block)
          double expectedScore = m_userItemMap.get(block_idxx + 1, itemId);
          RootbeerGpu.setSharedDouble(shmEstScoreStartPos, expectedScore);
        }
        // Sync all threads within a block
        RootbeerGpu.syncthreads();

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
        for (int s = blockSize / 2; s > 0; s >>= 1) {

          if (thread_idxx < s) {
            // sh_mem[ltid] += sh_mem[ltid + s];
            int multVectorIndex1 = shmMultVectorStartPos + thread_idxx * 8;
            int multVectorIndex2 = shmMultVectorStartPos + (thread_idxx + s)
                * 8;

            double val1 = RootbeerGpu.getSharedDouble(multVectorIndex1);
            double val2 = RootbeerGpu.getSharedDouble(multVectorIndex2);
            RootbeerGpu.setSharedDouble(multVectorIndex1, val1 + val2);
          }
          // Sync all threads within a block
          RootbeerGpu.syncthreads();
        }

        if (thread_idxx == 0) {
          double calculatedScore = RootbeerGpu.getSharedDouble(0);
          System.out.println("calculatedScore: " + calculatedScore);
          RootbeerGpu.setSharedDouble(shmCalcScoreStartPos, calculatedScore);
        }

        // Sync all threads within a block
        RootbeerGpu.syncthreads();

        // Update U
        // Each thread does one update operation of vector u
        if (thread_idxx < m_matrixRank) {

          int userVectorIndex = shmUserVectorStartPos + thread_idxx * 8;
          double userVal = RootbeerGpu.getSharedDouble(userVectorIndex);

          int itemVectorIndex = shmItemVectorStartPos + thread_idxx * 8;
          double itemVal = RootbeerGpu.getSharedDouble(itemVectorIndex);

          double calculatedScore = RootbeerGpu
              .getSharedDouble(shmCalcScoreStartPos);

          double expectedScore = RootbeerGpu
              .getSharedDouble(shmEstScoreStartPos);

          userVal += 2 * m_ALPHA * itemVal * (expectedScore - calculatedScore);

          RootbeerGpu.setSharedDouble(userVectorIndex, userVal);
        }

        // Sync all threads within a block
        RootbeerGpu.syncthreads();

      }

      // Sync all blocks Inter-Block Synchronization
      RootbeerGpu.syncblocks(1);

      // **********************************************************************
      // Compute V (Items)
      // **********************************************************************

      // Sync all blocks Inter-Block Synchronization
      RootbeerGpu.syncblocks(2);

      // **********************************************************************
      // normalizeWithBroadcastingValues
      // **********************************************************************

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
    new OnlineCFTrainHybridKernel(null, null, null, 0, 0, 0, 0, 0, null);
    new UserItemMap().put(0, 0, 0);
    new VectorMap().put(0, null);
  }
}
