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

import edu.syr.pcpratts.rootbeer.runtime.HamaPeer;
import edu.syr.pcpratts.rootbeer.runtime.Kernel;
import edu.syr.pcpratts.rootbeer.runtime.RootbeerGpu;

public class KMeansHybridKernel implements Kernel {

  private long m_superstepCount = 0;
  private long m_converged = 1;

  public double[][] m_inputs = null; // input
  public double[][] m_centers = null; // input
  public int m_maxIterations = 0; // input

  public KMeansHybridKernel(double[][] inputs, double[][] centers,
      int maxIterations) {
    m_inputs = inputs;
    m_centers = centers;
    m_maxIterations = maxIterations;
  }

  public void gpuMethod() {
    int blockSize = RootbeerGpu.getBlockDimx();
    int gridSize = RootbeerGpu.getGridDimx();

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
    int sharedMemoryCenterSize = centerCount * centerDim * 8;
    int sharedMemoryNewCentersStartPos = sharedMemoryCenterSize;
    int sharedMemorySummationCountStartPos = sharedMemoryNewCentersStartPos
        + sharedMemoryCenterSize;
    int sharedMemoryInputVectorsStartPos = sharedMemorySummationCountStartPos
        + (centerCount * 4);
    int sharedMemoryLowestDistantCenter = sharedMemoryInputVectorsStartPos
        + (blockSize * centerDim * 8);
    int sharedMemoryInputIndex = sharedMemoryLowestDistantCenter
        + (blockSize * 4);
    int sharedMemoryInputStartIndex = sharedMemoryInputIndex + 4;
    int sharedMemoryInputHasMoreBoolean = sharedMemoryInputStartIndex + 4;
    // int sharedMemoryEndPos = sharedMemoryInputHasMoreBoolean + 1; // boolean
    // need only 1 byte

    // if (globalThreadId == 0) {
    // System.out.print("SharedMemorySize: ");
    // System.out.print(sharedMemoryEndPos);
    // System.out.println(" bytes");
    // }

    // put into sharedMemory
    // m_maxIterations = 10;
    // long m_superstepCount = 0;
    // long m_converged = 1;

    // Start KMeans clustering algorithm
    while ((m_converged != 0)
        && ((m_maxIterations > 0) && (m_superstepCount <= m_maxIterations))) {

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

      // System.out.println(thread_idxx);

      // loop until input is empty
      // while (inputHasMore == true)
      while (RootbeerGpu.getSharedBoolean(sharedMemoryInputHasMoreBoolean)) {

        // Sync all threads within a block
        RootbeerGpu.syncthreads();

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
                // update inputHasMore
                RootbeerGpu.setSharedBoolean(sharedMemoryInputHasMoreBoolean,
                    false);
                break;
              }
            }
            RootbeerGpu.setSharedInteger(sharedMemoryInputStartIndex, j);
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

          // System.out.println(thread_idxx);

          // getNearestCenter
          int lowestDistantCenter = 0;
          double lowestDistance = Double.MAX_VALUE;

          for (int i = 0; i < centerCount; i++) {

            // measureEuclidianDistance
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

          int lowestDistantCenterIdxx = sharedMemoryLowestDistantCenter
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
        if (thread_idxx == 0) {

          // for each thread in block assignCenters
          for (int i = 0; i < RootbeerGpu
              .getSharedInteger(sharedMemoryInputIndex); i++) {

            int lowestDistantCenterIdxx = sharedMemoryLowestDistantCenter
                + (i * 4);
            int lowestDistantCenter = RootbeerGpu
                .getSharedInteger(lowestDistantCenterIdxx);

            // TODO if summationCount == 0 no addition is needed!
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
            // summationCount[lowestDistantCenter]
            int summationCountIndex = sharedMemorySummationCountStartPos
                + (lowestDistantCenter * 4);

            // summationCount[lowestDistantCenter]++;
            RootbeerGpu.setSharedInteger(summationCountIndex,
                RootbeerGpu.getSharedInteger(summationCountIndex) + 1);

          }
        }

        // Sync all threads within a block
        RootbeerGpu.syncthreads();
      }

      // Sync all threads within a block
      RootbeerGpu.syncthreads();

      // System.out.println(thread_idxx);

      // **********************************************************************
      // sendMessages *********************************************************
      // **********************************************************************
      // Thread 0 of each block
      // Sends messages about the local updates to each other peer
      if (thread_idxx == 0) {
        String[] allPeerNames = HamaPeer.getAllPeerNames();

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

            for (String peerName : allPeerNames) {
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

        // TODO Possible Parallelism?

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

              // TODO m_centers is in global GPU memory
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

                // TODO m_centers is in global GPU memory
                m_centers[i][j] = RootbeerGpu.getSharedDouble(newCenterIndex);
              }
              convergedCounter++;
            }
          }
        }

        // TODO flush global memory
        // m_converged and m_superstepCount are in global GPU memory
        m_converged = convergedCounter;
        m_superstepCount = HamaPeer.getSuperstepCount();
        RootbeerGpu.threadfenceSystem();
      }

      // Sync all blocks Inter-Block Synchronization
      // RootbeerGpu.syncblocks(2);

      // if (thread_idxx == 0) {
      // System.out.print("m_converged: ");
      // System.out.println(m_converged);
      // }

      // TODO remove break
      break;
    }

    // ************************************************************************
    // recalculateAssignmentsAndWrite *****************************************
    // ************************************************************************
/*
    int startIndex = 0;

    if (thread_idxx == 0) {
      // boolean inputHasMore = true;
      RootbeerGpu.setSharedBoolean(sharedMemoryInputHasMoreBoolean, true);
    }

    // Sync all threads within a block
    RootbeerGpu.syncthreads();

    // loop until input is empty
    // while (inputHasMore == true)
    while (RootbeerGpu.getSharedBoolean(sharedMemoryInputHasMoreBoolean)) {

      int i = 0; // amount of threads in block

      // Thread 0 of each block
      // Setup inputs for thread block
      if (thread_idxx == 0) {

        int j = startIndex;

        while (i < blockSize) {
          // System.out.print("get from cache j: ");
          // System.out.println(j);
          DenseDoubleVector vector = m_cache.get(j);

          // TODO skip toArray (vector.get(j))
          double[] inputs = vector.toArray(); // vectorDim = centerDim

          // Update inputs on SharedMemory
          for (int k = 0; k < centerDim; k++) {
            // Init inputs[][]
            int inputIndex = sharedMemoryInputVectorsStartPos
                + ((i * centerDim) + k) * 8;

            RootbeerGpu.setSharedDouble(inputIndex, inputs[k]);
          }

          i++;
          j++;
          if (j == m_cache.getLength()) {
            // update inputHasMore
            RootbeerGpu
                .setSharedBoolean(sharedMemoryInputHasMoreBoolean, false);
            break;
          }
        }
        startIndex = j;
      }

      // Sync all threads within a block
      // input[][] was updated
      RootbeerGpu.syncthreads();

      // #################
      // Parallelism Start
      // #################
      if (thread_idxx < i) {

        int lowestDistantCenter = getNearestCenter(centerCount, centerDim,
            sharedMemoryInputVectorsStartPos);

        // TODO Performance issue
        // Write out own vector and corresponding lowestDistantCenter
        String vectorStr = "";
        for (int k = 0; k < centerDim; k++) {

          int inputIdxx = sharedMemoryInputVectorsStartPos
              + ((thread_idxx * centerDim) + k) * 8;

          vectorStr += Double.toString(RootbeerGpu.getSharedDouble(inputIdxx));

          if (k < centerDim - 1) {
            vectorStr += ", ";
          }
        }

        // TODO Performance issue
        HamaPeer.write(new Integer(lowestDistantCenter), vectorStr);
      }
      // #################
      // Parallelism End
      // #################

      // Sync all threads within a block
      RootbeerGpu.syncthreads();
    }
*/  
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
    new KMeansHybridKernel(null, null, 0);
  }
}
