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
package at.illecker.hama.hybrid.examples.kmeans;

import org.trifort.rootbeer.runtime.HamaPeer;
import org.trifort.rootbeer.runtime.Kernel;
import org.trifort.rootbeer.runtime.RootbeerGpu;

public class KMeansHybridKernel implements Kernel {

  private long m_superstepCount;
  private long m_converged;

  public double[][] m_inputs; // input
  public double[][] m_centers; // input and output
  public int m_maxIterations; // input
  public String[] m_allPeerNames; // input
  public int[] m_input_centers; // output

  public KMeansHybridKernel(double[][] inputs, double[][] centers,
      int maxIterations, String[] allPeerNames) {
    m_inputs = inputs;
    m_centers = centers;
    m_maxIterations = maxIterations;
    m_allPeerNames = allPeerNames;
    m_input_centers = new int[inputs.length];
  }

  public void gpuMethod() {
    // Check maxIterations
    if (m_maxIterations <= 0) {
      return;
    }

    int blockSize = RootbeerGpu.getBlockDimx();
    int gridSize = (int) RootbeerGpu.getGridDimx();

    int block_idxx = RootbeerGpu.getBlockIdxx();
    int thread_idxx = RootbeerGpu.getThreadIdxx();

    // globalThreadId = blockIdx.x * blockDim.x + threadIdx.x;
    int globalThreadId = RootbeerGpu.getThreadId();

    int centerCount = m_centers.length;
    int centerDim = m_centers[0].length;

    int inputCount = m_inputs.length;
    int blockInputSize = divup(inputCount, gridSize);
    // System.out.print("blockInputSize: ");
    // System.out.println(blockInputSize);

    // SharedMemory per block
    // centerCount x centerDim x Doubles (centerCount x centerDim * 8 bytes)
    int sharedMemoryCenterSize = centerCount * centerDim * 8;
    // centerCount x centerDim x Doubles (centerCount x centerDim * 8 bytes)
    int sharedMemoryNewCentersStartPos = sharedMemoryCenterSize;
    // centerCount x Integers (centerCount * 4 bytes)
    int sharedMemorySummationCountStartPos = sharedMemoryNewCentersStartPos
        + sharedMemoryCenterSize;
    // blockSize x centerDim x Doubles (blockSize x centerDim * 8 bytes)
    int sharedMemoryInputVectorsStartPos = sharedMemorySummationCountStartPos
        + (centerCount * 4);
    // blockSize x Integers (blockSize * 4 bytes)
    int sharedMemoryLowestDistantCenterStartPos = sharedMemoryInputVectorsStartPos
        + (blockSize * centerDim * 8);

    // 1 x Integer (4 bytes)
    int sharedMemoryInputIndex = sharedMemoryLowestDistantCenterStartPos
        + (blockSize * 4);
    // 1 x Integer (4 bytes)
    int sharedMemoryInputStartIndex = sharedMemoryInputIndex + 4;
    // 1 x Boolean (1 byte)
    int sharedMemoryInputHasMoreBoolean = sharedMemoryInputStartIndex + 4;

    // 1 x Long (8 bytes)
    int sharedMemorySuperstepCount = sharedMemoryInputHasMoreBoolean + 1;
    // 1 x Long (8 bytes)
    int sharedMemoryConverged = sharedMemorySuperstepCount + 8;

    // int sharedMemoryEndPos = sharedMemoryConverged + 8;

    // if (globalThreadId == 0) {
    // System.out.print("SharedMemorySize: ");
    // System.out.print(sharedMemoryEndPos);
    // System.out.println(" bytes");
    // }

    // Start KMeans clustering algorithm
    do {
      // Thread 0 of each block
      // Setup SharedMemory
      // Load centers into SharedMemory
      // Init newCenters and summationCount in SharedMemory
      if (thread_idxx == 0) {

        for (int i = 0; i < centerCount; i++) {
          for (int j = 0; j < centerDim; j++) {
            // Init centers[][]
            int centerIndex = ((i * centerDim) + j) * 8;
            RootbeerGpu.setSharedDouble(centerIndex, m_centers[i][j]);

            // Init newCenters[][]
            int newCenterIndex = sharedMemoryNewCentersStartPos + centerIndex;
            RootbeerGpu.setSharedDouble(newCenterIndex, 0);
          }
          // Init summationCount[]
          int summationCountIndex = sharedMemorySummationCountStartPos
              + (i * 4);
          RootbeerGpu.setSharedInteger(summationCountIndex, 0);
        }

        // boolean inputHasMore = true;
        RootbeerGpu.setSharedBoolean(sharedMemoryInputHasMoreBoolean, true);

        // int startIndex = 0; // used by thread 0 of each block
        RootbeerGpu.setSharedInteger(sharedMemoryInputStartIndex, 0);
      }

      // Sync all threads within a block
      RootbeerGpu.syncthreads();

      // **********************************************************************
      // assignCenters ********************************************************
      // **********************************************************************

      // loop until input is empty
      // while (inputHasMore == true)
      while (RootbeerGpu.getSharedBoolean(sharedMemoryInputHasMoreBoolean)) {

        // Thread 0 of each block
        // Setup inputs for thread block
        if (thread_idxx == 0) {

          // int i = 0; // amount of threads in block
          RootbeerGpu.setSharedInteger(sharedMemoryInputIndex, 0);

          // check if block has some input
          if ((block_idxx * blockInputSize) < inputCount) {

            int j = RootbeerGpu.getSharedInteger(sharedMemoryInputStartIndex);

            while (RootbeerGpu.getSharedInteger(sharedMemoryInputIndex) < blockSize) {
              // System.out.print("get from cache j: ");
              // System.out.println((block_idxx * blockInputSize) + j);

              // Update inputs on SharedMemory
              for (int k = 0; k < centerDim; k++) {
                // Init inputs[][]
                int inputIndex = sharedMemoryInputVectorsStartPos
                    + ((RootbeerGpu.getSharedInteger(sharedMemoryInputIndex) * centerDim) + k)
                    * 8;
                RootbeerGpu.setSharedDouble(inputIndex,
                    m_inputs[(block_idxx * blockInputSize) + j][k]);
              }

              // i++;
              RootbeerGpu.setSharedInteger(sharedMemoryInputIndex,
                  RootbeerGpu.getSharedInteger(sharedMemoryInputIndex) + 1);

              j++;
              if ((j == blockInputSize)
                  || ((block_idxx * blockInputSize) + j == inputCount)) {
                // Set inputHasMore to false
                RootbeerGpu.setSharedBoolean(sharedMemoryInputHasMoreBoolean,
                    false);
                break;
              }
            }
            RootbeerGpu.setSharedInteger(sharedMemoryInputStartIndex, j);

          } else { // block has no inputs
            // System.out.println(block_idxx);
            // Set inputHasMore to false
            RootbeerGpu
                .setSharedBoolean(sharedMemoryInputHasMoreBoolean, false);
          }

          // System.out.println("SharedMemory init finished.");
        }

        // Sync all threads within a block
        // input[][] was updated
        RootbeerGpu.syncthreads();

        // System.out.println(RootbeerGpu.getSharedInteger(sharedMemoryIndex));
        // System.out.println(thread_idxx);

        // #################
        // Parallelism Start
        // #################
        if (thread_idxx < RootbeerGpu.getSharedInteger(sharedMemoryInputIndex)) {

          // getNearestCenter
          int lowestDistantCenter = 0;
          double lowestDistance = Double.MAX_VALUE;

          for (int i = 0; i < centerCount; i++) {

            // measure Euclidean Distance
            double sum = 0;
            for (int j = 0; j < centerDim; j++) {

              int inputIdxx = sharedMemoryInputVectorsStartPos
                  + ((thread_idxx * centerDim) + j) * 8;

              int centerIdxx = ((i * centerDim) + j) * 8;

              // double diff = vector2[i] - vector1[i];
              double diff = RootbeerGpu.getSharedDouble(inputIdxx)
                  - RootbeerGpu.getSharedDouble(centerIdxx);

              // multiplication is faster than Math.pow() for ^2.
              sum += (diff * diff);
            }
            double estimatedDistance = Math.sqrt(sum);

            // System.out.print("estimatedDistance: ");
            // System.out.println(estimatedDistance);

            // check if we have a can assign a new center, because we
            // got a lower distance
            if (estimatedDistance < lowestDistance) {
              lowestDistance = estimatedDistance;
              lowestDistantCenter = i;
            }
          }

          int lowestDistantCenterIdxx = sharedMemoryLowestDistantCenterStartPos
              + (thread_idxx * 4);
          RootbeerGpu.setSharedInteger(lowestDistantCenterIdxx,
              lowestDistantCenter);
          // System.out.print("lowestDistantCenter: ");
          // System.out.println(lowestDistantCenter);

        }
        // #################
        // Parallelism End
        // #################

        // Sync all threads within a block
        RootbeerGpu.syncthreads();

        // assignCenters
        // synchronized because it has to write into SharedMemory
        if ((thread_idxx == 0)
            && (RootbeerGpu.getSharedInteger(sharedMemoryInputIndex) > 0)) {

          // for each thread in block assignCenters
          for (int i = 0; i < RootbeerGpu
              .getSharedInteger(sharedMemoryInputIndex); i++) {

            int lowestDistantCenterIdxx = sharedMemoryLowestDistantCenterStartPos
                + (i * 4);
            int lowestDistantCenter = RootbeerGpu
                .getSharedInteger(lowestDistantCenterIdxx);

            // summationCount[lowestDistantCenter]
            int summationCountIndex = sharedMemorySummationCountStartPos
                + (lowestDistantCenter * 4);

            // if summationCount == 0 addition is not needed!
            if (RootbeerGpu.getSharedInteger(summationCountIndex) == 0) {

              for (int j = 0; j < centerDim; j++) {

                int newCenterIndex = sharedMemoryNewCentersStartPos
                    + (((lowestDistantCenter * centerDim) + j) * 8);

                int inputIndex = sharedMemoryInputVectorsStartPos
                    + ((i * centerDim) + j) * 8;

                // newCenters[lowestDistantCenter][j] == vector[j];
                RootbeerGpu.setSharedDouble(newCenterIndex,
                    RootbeerGpu.getSharedDouble(inputIndex));
              }

              // Update newCenter counter
              // summationCount[lowestDistantCenter]++;
              RootbeerGpu.setSharedInteger(summationCountIndex, 1);

            } else { // do vector addition

              for (int j = 0; j < centerDim; j++) {

                int newCenterIndex = sharedMemoryNewCentersStartPos
                    + (((lowestDistantCenter * centerDim) + j) * 8);

                int inputIndex = sharedMemoryInputVectorsStartPos
                    + ((i * centerDim) + j) * 8;

                // newCenters[lowestDistantCenter][j] += vector[j];
                RootbeerGpu.setSharedDouble(
                    newCenterIndex,
                    RootbeerGpu.getSharedDouble(newCenterIndex)
                        + RootbeerGpu.getSharedDouble(inputIndex));
              }

              // Update newCenter counter
              // summationCount[lowestDistantCenter]++;
              RootbeerGpu.setSharedInteger(summationCountIndex,
                  RootbeerGpu.getSharedInteger(summationCountIndex) + 1);
            }
          }
        }

        // Sync all threads within a block
        RootbeerGpu.syncthreads();
      }

      // **********************************************************************
      // sendMessages *********************************************************
      // **********************************************************************
      // Thread 0 of each block
      // Sends messages about the local updates to each other peer
      if ((thread_idxx == 0)
          && (RootbeerGpu.getSharedInteger(sharedMemoryInputIndex) > 0)) {

        for (int i = 0; i < centerCount; i++) {

          int summationCountIndex = sharedMemorySummationCountStartPos
              + (i * 4);

          int summationCount = RootbeerGpu
              .getSharedInteger(summationCountIndex);

          if (summationCount > 0) {

            // centerIndex:incrementCounter:VectorValue1,VectorValue2,VectorValue3
            String message = "";
            message += Integer.toString(i);
            message += ":";
            message += Integer.toString(summationCount);
            message += ":";

            // centerDim = m_newCenters[i].length
            for (int j = 0; j < centerDim; j++) {

              int newCenterIndex = sharedMemoryNewCentersStartPos
                  + (((i * centerDim) + j) * 8);

              // newCenters[i][j]
              message += Double.toString(RootbeerGpu
                  .getSharedDouble(newCenterIndex));

              // add ", " if not last element
              if (j < centerDim - 1) {
                message += ", ";
              }
            }

            // System.out.println(message);

            for (String peerName : m_allPeerNames) {
              HamaPeer.send(peerName, message);
            }
          }
        }
      }

      // Sync all blocks Inter-Block Synchronization
      RootbeerGpu.syncblocks(1);

      // Global Thread 0 of each blocks
      if (globalThreadId == 0) {

        // Sync all peers
        HamaPeer.sync();

        // ********************************************************************
        // updateCenters ******************************************************
        // ********************************************************************
        // Fetch messages

        // Reinit SharedMemory
        // use newCenters for msgCenters
        // use summationCount for msgIncrementSum
        for (int i = 0; i < centerCount; i++) {
          for (int j = 0; j < centerDim; j++) {
            // Reinit newCenters[][]
            int newCenterIndex = sharedMemoryNewCentersStartPos
                + (((i * centerDim) + j) * 8);
            RootbeerGpu.setSharedDouble(newCenterIndex, 0);
          }
          // Reinit summationCount[]
          int summationCountIndex = sharedMemorySummationCountStartPos
              + (i * 4);
          RootbeerGpu.setSharedInteger(summationCountIndex, 0);
        }

        // Fetch messages
        int msgCount = HamaPeer.getNumCurrentMessages();
        for (int i = 0; i < msgCount; i++) {

          // centerIndex:incrementCounter:VectorValue1,VectorValue2,VectorValue3
          String message = HamaPeer.getCurrentStringMessage();
          System.out.println(message);

          // parse message
          String[] values = message.split(":", 3);
          int centerIndex = Integer.parseInt(values[0]);
          int incrementCounter = Integer.parseInt(values[1]);

          String[] vectorStr = values[2].split(",");
          int len = vectorStr.length;
          double[] messageVector = new double[len];
          for (int j = 0; j < len; j++) {
            messageVector[j] = Double.parseDouble(vectorStr[j]);
          }

          // msgIncrementSum[centerIndex]
          int summationCountIndex = sharedMemorySummationCountStartPos
              + (centerIndex * 4);
          int summationCount = RootbeerGpu
              .getSharedInteger(summationCountIndex);

          // Update
          if (summationCount == 0) {

            // Set messageVector to msgCenters
            // msgCenters[centerIndex] = messageVector;
            for (int j = 0; j < centerDim; j++) {

              int newCenterIndex = sharedMemoryNewCentersStartPos
                  + (((centerIndex * centerDim) + j) * 8);

              RootbeerGpu.setSharedDouble(newCenterIndex, messageVector[j]);
            }

          } else {
            // VectorAdd
            // msgCenters[centerIndex] =
            // addVector(msgCenters[centerIndex],msgVector);
            for (int j = 0; j < centerDim; j++) {

              int newCenterIndex = sharedMemoryNewCentersStartPos
                  + (((centerIndex * centerDim) + j) * 8);

              // msgCenters[centerIndex][j] += messageVector[j];
              RootbeerGpu.setSharedDouble(newCenterIndex,
                  RootbeerGpu.getSharedDouble(newCenterIndex)
                      + messageVector[j]);
            }
          }
          // msgIncrementSum[centerIndex] += incrementCounter;
          RootbeerGpu.setSharedInteger(summationCountIndex, summationCount
              + incrementCounter);
        }

        // ********************************************************************
        // divide by how often we globally summed vectors
        // ********************************************************************
        for (int i = 0; i < centerCount; i++) {

          // msgIncrementSum[i]
          int summationCountIndex = sharedMemorySummationCountStartPos
              + (i * 4);
          int summationCount = RootbeerGpu
              .getSharedInteger(summationCountIndex);

          // and only if we really have an update for center
          if (summationCount > 0) {

            // msgCenters[i] = divideVector(msgCenters[i], msgIncrementSum[i]);
            for (int j = 0; j < centerDim; j++) {

              int newCenterIndex = sharedMemoryNewCentersStartPos
                  + (((i * centerDim) + j) * 8);

              // msgCenters[i][j] /= msgIncrementSum[i];
              RootbeerGpu.setSharedDouble(newCenterIndex,
                  RootbeerGpu.getSharedDouble(newCenterIndex) / summationCount);
            }
          }
        }

        // ********************************************************************
        // finally check for convergence by the absolute difference
        // ********************************************************************
        long convergedCounter = 0L;
        for (int i = 0; i < centerCount; i++) {

          // msgIncrementSum[i]
          int summationCountIndex = sharedMemorySummationCountStartPos
              + (i * 4);
          int summationCount = RootbeerGpu
              .getSharedInteger(summationCountIndex);

          if (summationCount > 0) {

            double calculateError = 0;
            for (int j = 0; j < centerDim; j++) {

              // msgCenters[i][j]
              int newCenterIndex = sharedMemoryNewCentersStartPos
                  + (((i * centerDim) + j) * 8);

              // m_centers is stored in global GPU memory
              calculateError += Math.abs(m_centers[i][j]
                  - RootbeerGpu.getSharedDouble(newCenterIndex));
            }

            // System.out.print("calculateError: ");
            // System.out.println(calculateError);

            // Update center if calculateError > 0
            if (calculateError > 0.0d) {

              // m_centers[i] = msgCenters[i];
              for (int j = 0; j < centerDim; j++) {

                int newCenterIndex = sharedMemoryNewCentersStartPos
                    + (((i * centerDim) + j) * 8);

                // m_centers is stored in global GPU memory
                m_centers[i][j] = RootbeerGpu.getSharedDouble(newCenterIndex);
              }
              convergedCounter++;
            }
          }
        }

        // m_converged and m_superstepCount are stored in global GPU memory
        m_converged = convergedCounter;
        m_superstepCount = HamaPeer.getSuperstepCount();
      }

      // Sync all blocks Inter-Block Synchronization
      RootbeerGpu.syncblocks(2);

      // Thread 0 of each block
      // Update SharedMemory superstepCount and converged
      if (thread_idxx == 0) {
        RootbeerGpu.setSharedLong(sharedMemorySuperstepCount, m_superstepCount);
        RootbeerGpu.setSharedLong(sharedMemoryConverged, m_converged);
      }

      // Sync all threads within a block
      RootbeerGpu.syncthreads();

    } while ((RootbeerGpu.getSharedLong(sharedMemoryConverged) != 0)
        && (RootbeerGpu.getSharedLong(sharedMemorySuperstepCount) <= m_maxIterations));

    // ************************************************************************
    // recalculateAssignmentsAndWrite *****************************************
    // ************************************************************************
    // Thread 0 of each block
    // Setup SharedMemory
    // Load centers into SharedMemory
    if (thread_idxx == 0) {

      for (int i = 0; i < centerCount; i++) {
        for (int j = 0; j < centerDim; j++) {
          // Init centers[][]
          int centerIndex = ((i * centerDim) + j) * 8;
          RootbeerGpu.setSharedDouble(centerIndex, m_centers[i][j]);
        }
      }
      // boolean inputHasMore = true;
      RootbeerGpu.setSharedBoolean(sharedMemoryInputHasMoreBoolean, true);

      // int startIndex = 0; // used by thread 0 of each block
      RootbeerGpu.setSharedInteger(sharedMemoryInputStartIndex, 0);
    }

    // Sync all threads within a block
    RootbeerGpu.syncthreads();

    // loop until input is empty
    while (RootbeerGpu.getSharedBoolean(sharedMemoryInputHasMoreBoolean)) {

      // Thread 0 of each block
      // Setup inputs for thread block
      if (thread_idxx == 0) {

        // int i = 0; // amount of threads in block
        RootbeerGpu.setSharedInteger(sharedMemoryInputIndex, 0);

        // check if block has some input
        if ((block_idxx * blockInputSize) < inputCount) {

          int j = RootbeerGpu.getSharedInteger(sharedMemoryInputStartIndex);

          while (RootbeerGpu.getSharedInteger(sharedMemoryInputIndex) < blockSize) {
            // System.out.print("get from cache j: ");
            // System.out.println((block_idxx * blockInputSize) + j);

            // Update inputs on SharedMemory
            for (int k = 0; k < centerDim; k++) {
              int inputIndex = sharedMemoryInputVectorsStartPos
                  + ((RootbeerGpu.getSharedInteger(sharedMemoryInputIndex) * centerDim) + k)
                  * 8;
              RootbeerGpu.setSharedDouble(inputIndex,
                  m_inputs[(block_idxx * blockInputSize) + j][k]);
            }

            // Store input_id in SharedMemory
            int inputId = sharedMemoryLowestDistantCenterStartPos
                + (RootbeerGpu.getSharedInteger(sharedMemoryInputIndex) * 4);
            RootbeerGpu.setSharedInteger(inputId, (block_idxx * blockInputSize)
                + j);

            // i++;
            RootbeerGpu.setSharedInteger(sharedMemoryInputIndex,
                RootbeerGpu.getSharedInteger(sharedMemoryInputIndex) + 1);

            j++;
            if ((j == blockInputSize)
                || ((block_idxx * blockInputSize) + j == inputCount)) {
              // Set inputHasMore to false
              RootbeerGpu.setSharedBoolean(sharedMemoryInputHasMoreBoolean,
                  false);
              break;
            }
          }
          RootbeerGpu.setSharedInteger(sharedMemoryInputStartIndex, j);

        } else { // block has no inputs
          // System.out.println(block_idxx);
          // Set inputHasMore to false
          RootbeerGpu.setSharedBoolean(sharedMemoryInputHasMoreBoolean, false);
        }
      }

      // Sync all threads within a block
      // input[][] was updated
      RootbeerGpu.syncthreads();

      // #################
      // Parallelism Start
      // #################
      if (thread_idxx < RootbeerGpu.getSharedInteger(sharedMemoryInputIndex)) {

        // getNearestCenter
        int lowestDistantCenterId = 0;
        double lowestDistance = Double.MAX_VALUE;

        for (int i = 0; i < centerCount; i++) {
          // measure Euclidean Distance
          double sum = 0;
          for (int j = 0; j < centerDim; j++) {

            int inputIdxx = sharedMemoryInputVectorsStartPos
                + ((thread_idxx * centerDim) + j) * 8;

            int centerIdxx = ((i * centerDim) + j) * 8;

            // double diff = vector2[i] - vector1[i];
            double diff = RootbeerGpu.getSharedDouble(inputIdxx)
                - RootbeerGpu.getSharedDouble(centerIdxx);

            // multiplication is faster than Math.pow() for ^2.
            sum += (diff * diff);
          }
          double estimatedDistance = Math.sqrt(sum);

          // check if we have a can assign a new center, because we
          // got a lower distance
          if (estimatedDistance < lowestDistance) {
            lowestDistance = estimatedDistance;
            lowestDistantCenterId = i;
          }
        }

        // Update m_input_centers with lowestDistantCenterId at inputId
        int inputId = sharedMemoryLowestDistantCenterStartPos
            + (thread_idxx * 4);
        m_input_centers[RootbeerGpu.getSharedInteger(inputId)] = lowestDistantCenterId;
      }
      // #################
      // Parallelism End
      // #################

      // Sync all threads within a block
      RootbeerGpu.syncthreads();
    }
  }

  private int divup(int x, int y) {
    if (x % y != 0) {
      return ((x + y - 1) / y); // round up
    } else {
      return x / y;
    }
  }

  public static void main(String[] args) {
    // Dummy constructor invocation
    // to keep kernel constructor in
    // rootbeer transformation
    new KMeansHybridKernel(null, null, 0, null);
  }
}
