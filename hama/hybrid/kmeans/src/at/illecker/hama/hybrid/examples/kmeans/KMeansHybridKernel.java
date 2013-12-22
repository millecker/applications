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
import edu.syr.pcpratts.rootbeer.runtime.KeyValuePair;
import edu.syr.pcpratts.rootbeer.runtime.RootbeerGpu;

public class KMeansHybridKernel implements Kernel {

  public DenseDoubleVectorList m_cache = null;
  public double[][] m_centers = null;
  public int m_maxIterations = 0;
  public long m_superstepCount = 0;
  public long m_converged = 0;

  public KMeansHybridKernel(double[][] centers, int maxIterations) {
    m_centers = centers;
    m_maxIterations = maxIterations;
    m_superstepCount = 0;
    m_converged = 0;
  }

  public void gpuMethod() {
    int blockSize = RootbeerGpu.getBlockDimx();
    // int gridSize = RootbeerGpu.getGridDimx();

    // int block_idxx = RootbeerGpu.getBlockIdxx();
    int thread_idxx = RootbeerGpu.getThreadIdxx();

    // globalThreadId = blockIdx.x * blockDim.x + threadIdx.x;
    int globalThreadId = RootbeerGpu.getThreadId();

    int centerCount = m_centers.length;
    int centerDim = m_centers[0].length;

    // SharedMemory per thread block
    int sharedMemoryNewCentersStartPos = centerCount * centerDim * 8;
    int sharedMemorySummationCountStartPos = sharedMemoryNewCentersStartPos
        + (centerCount * centerDim * 8);
    int sharedMemoryInputVectorsStartPos = sharedMemorySummationCountStartPos
        + (centerCount * 4);
    int sharedMemoryInputVectorsEndPos = sharedMemoryInputVectorsStartPos
        + (blockSize * centerDim * 8);

    System.out.print("SharedMemorySize: ");
    System.out.print(sharedMemoryInputVectorsEndPos);
    System.out.println(" bytes");

    // Start KMeans clustering algorithm
    while (true) {

      // TODO without the following statement
      // Rootbeer misses code from GarabageCollector -> SIGSEGV
      System.out.println("Start loop...");

      // Setup SharedMemory
      // Thread 0 of each block
      // Load centers into SharedMemory
      // Init newCenters and summationCount in SharedMemory
      if (thread_idxx == 0) {

        for (int i = 0; i < centerCount; i++) {
          for (int j = 0; j < centerDim; j++) {

            // centers[][]
            int centerIndex = ((i * centerDim) + j) * 8;
            RootbeerGpu.setSharedDouble(centerIndex, m_centers[i][j]);

            // newCenters[][]
            int newCenterIndex = sharedMemoryNewCentersStartPos
                + (((i * centerDim) + j) * 8);
            RootbeerGpu.setSharedDouble(newCenterIndex, 0);
          }
          // summationCount[]
          int summationCountIndex = sharedMemorySummationCountStartPos
              + (i * 4);
          RootbeerGpu.setSharedInteger(summationCountIndex, -1);
        }
      }

      // Sync all threads within a block
      RootbeerGpu.syncthreads();

      // assignCenters *****************************************************
      boolean inputHasMore = true;
      boolean fillCache = false;
      int startIndex = 0;

      // loop until input is empty
      while (inputHasMore) {

        int i = 0; // amount of threads

        // Thread 0 of each block
        // Setup inputs for thread block
        if (thread_idxx == 0) {

          // if cache is empty read from HamaPeer
          if ((m_cache == null) || (fillCache)) {

            if (m_cache == null) {
              m_cache = new DenseDoubleVectorList();
              fillCache = true;
            }

            String vectorStr = "";
            KeyValuePair keyValuePair = new KeyValuePair(vectorStr, null);

            while (i < blockSize) {
              inputHasMore = HamaPeer.readNext(keyValuePair);
              fillCache = inputHasMore;
              if (!inputHasMore) {
                break;
              }

              vectorStr = (String) keyValuePair.getKey();

              DenseDoubleVector vector = new DenseDoubleVector(vectorStr);
              m_cache.add(vector);

              double[] inputs = vector.toArray(); // inputs.len = centerDim

              // Update inputs on SharedMemory
              for (int j = 0; j < centerDim; j++) {
                // inputs[][]
                int inputIndex = sharedMemoryInputVectorsStartPos
                    + ((i * centerDim) + j) * 8;
                RootbeerGpu.setSharedDouble(inputIndex, inputs[j]);
              }

              i++;
            }

          } else { // fill inputs from m_cache

            int j = startIndex;
            while (i < blockSize) {
              // System.out.print("get from cache j: ");
              // System.out.println(j);
              DenseDoubleVector vector = m_cache.get(j);

              double[] inputs = vector.toArray(); // inputs.len = centerDim

              // Update inputs on SharedMemory
              for (int k = 0; k < centerDim; k++) {
                // inputs[][]
                int inputIndex = sharedMemoryInputVectorsStartPos
                    + ((i * centerDim) + k) * 8;
                RootbeerGpu.setSharedDouble(inputIndex, inputs[k]);
              }

              i++;
              j++;
              if (j == m_cache.getLength()) {
                inputHasMore = false;
                break;
              }
            }
            startIndex = j;
          }
        }

        // Sync all threads within a block
        // input[][] was updated
        RootbeerGpu.syncthreads();

        // Parallelism Start
        if (thread_idxx < i) {
          int lowestDistantCenter = getNearestCenter(centerCount, centerDim,
              sharedMemoryInputVectorsStartPos);

          assignCenters(lowestDistantCenter, centerDim,
              sharedMemoryNewCentersStartPos,
              sharedMemorySummationCountStartPos,
              sharedMemoryInputVectorsStartPos);
        }
        // Parallelism End

        // Sync all threads within a block
        RootbeerGpu.syncthreads();
      }

      // sendMessages *****************************************************

      // Thread 0 of each block
      // Sends messages about the local updates to each other peer
      if (thread_idxx == 0) {
        String[] allPeerNames = HamaPeer.getAllPeerNames();

        // centerCount = newCenters.length
        for (int i = 0; i < centerCount; i++) {

          int summationCountIndex = sharedMemorySummationCountStartPos
              + (i * 4);
          // summationCount[i]
          int summationCount = RootbeerGpu
              .getSharedInteger(summationCountIndex);

          if (summationCount != -1) {

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

            System.out.print("send message: '");
            System.out.print(message);
            System.out.println("'");

            for (String peerName : allPeerNames) {
              HamaPeer.send(peerName, message);
            }
          }
        }
      }

      // Global Thread 0 of each blocks
      // Sync all tasks
      if (globalThreadId == 0) {
        HamaPeer.sync();
      }

      // Sync all threads within a block
      RootbeerGpu.syncthreads();

      // updateCenters *****************************************************

      // Global Thread 0 of each blocks
      // Fetch messages
      if (globalThreadId == 0) {

        // Reinit SharedMemory
        // use newCenters for msgCenters
        // use summationCount for msgIncrementSum
        for (int i = 0; i < centerCount; i++) {
          for (int j = 0; j < centerDim; j++) {
            // newCenters[][]
            int newCenterIndex = sharedMemoryNewCentersStartPos
                + (((i * centerDim) + j) * 8);
            RootbeerGpu.setSharedDouble(newCenterIndex, 0);
          }
          // summationCount[]
          int summationCountIndex = sharedMemorySummationCountStartPos
              + (i * 4);
          RootbeerGpu.setSharedInteger(summationCountIndex, 0);
        }

        int msgCount = HamaPeer.getNumCurrentMessages();
        for (int i = 0; i < msgCount; i++) {

          // centerIndex:incrementCounter:VectorValue1,VectorValue2,VectorValue3
          String message = HamaPeer.getCurrentStringMessage();

          System.out.print("got message: '");
          System.out.print(message);
          System.out.println("'");

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

            // msgCenters[centerIndex] =
            // addVector(msgCenters[centerIndex],msgVector);
            for (int j = 0; j < centerDim; j++) {
              // VectorAdd
              // msgCenters[centerIndex][j] += messageVector[j];

              int newCenterIndex = sharedMemoryNewCentersStartPos
                  + (((centerIndex * centerDim) + j) * 8);

              RootbeerGpu.setSharedDouble(newCenterIndex,
                  RootbeerGpu.getSharedDouble(newCenterIndex)
                      + messageVector[j]);
            }
          }
          // msgIncrementSum[centerIndex] += incrementCounter;
          RootbeerGpu.setSharedInteger(summationCountIndex, summationCount
              + incrementCounter);
        }

        // TODO Possible Parallelism

        // divide by how often we globally summed vectors
        for (int i = 0; i < centerCount; i++) {

          // msgIncrementSum[i]
          int summationCountIndex = sharedMemorySummationCountStartPos
              + (i * 4);
          int summationCount = RootbeerGpu
              .getSharedInteger(summationCountIndex);

          // and only if we really have an update for center
          if (summationCount != 0) {

            // msgCenters[i] = divideVector(msgCenters[i], msgIncrementSum[i]);
            for (int j = 0; j < centerDim; j++) {

              // msgCenters[i][j] /= msgIncrementSum[i];
              int newCenterIndex = sharedMemoryNewCentersStartPos
                  + (((i * centerDim) + j) * 8);

              RootbeerGpu.setSharedDouble(newCenterIndex,
                  RootbeerGpu.getSharedDouble(newCenterIndex) / summationCount);
            }
          }
        }

        // finally check for convergence by the absolute difference
        long convergedCounter = 0L;
        for (int i = 0; i < centerCount; i++) {

          // msgIncrementSum[i]
          int summationCountIndex = sharedMemorySummationCountStartPos
              + (i * 4);
          int summationCount = RootbeerGpu
              .getSharedInteger(summationCountIndex);

          if (summationCount != 0) {

            double calculateError = 0;
            for (int j = 0; j < centerDim; j++) {

              // msgCenters[i][j]
              int newCenterIndex = sharedMemoryNewCentersStartPos
                  + (((i * centerDim) + j) * 8);

              calculateError += Math.abs(m_centers[i][j]
                  - RootbeerGpu.getSharedDouble(newCenterIndex));
            }

            System.out.print("calculateError: ");
            System.out.println(calculateError);

            if (calculateError > 0.0d) {

              // m_centers[i] = msgCenters[i];
              for (int j = 0; j < centerDim; j++) {

                int newCenterIndex = sharedMemoryNewCentersStartPos
                    + (((i * centerDim) + j) * 8);

                m_centers[i][j] = RootbeerGpu.getSharedDouble(newCenterIndex);
              }
              convergedCounter++;
            }
          }
        }
        m_converged = convergedCounter;
        m_superstepCount = HamaPeer.getSuperstepCount();

        // Not used, all inputs in cache
        // HamaPeer.reopenInput();
      }

      // Sync all threads within a block
      RootbeerGpu.syncthreads();

      System.out.print("m_converged: ");
      System.out.println(m_converged);

      if (m_converged == 0) {
        break;
      }
      if ((m_maxIterations > 0) && (m_maxIterations < m_superstepCount)) {
        break;
      }

    }

    System.out.println("Finished! Writing the assignments...");

    // recalculateAssignmentsAndWrite *****************************************
/*
    boolean inputHasMore = true;
    int startIndex = 0;

    while (inputHasMore) {

      double[][] inputs = null; // TODO put in SharedMemory
      int i = 0;

      // thread 0 setup inputs for threads
      if (global_thread_idxx == 0) {

        int j = startIndex;
        while (i < threadCount) {
          System.out.print("get from cache j: ");
          System.out.println(j);

          DenseDoubleVector vector = m_cache.get(j);

          if (inputs == null) {
            inputs = new double[threadCount][vector.getLength()];
          }
          inputs[i] = vector.toArray();

          i++;
          j++;
          if (j == m_cache.getLength()) {
            inputHasMore = false;
            break;
          }
        }
        startIndex = j;
      }

      RootbeerGpu.syncthreads();

      // Parallelism Start
      if (global_thread_idxx < i) {

        // Each thread gets his own vector
        double[] vector = inputs[global_thread_idxx];

        // each thread has all centers, if a center has been updated it needs
        // to be broadcasted.
        int lowestDistantCenter = getNearestCenter(vector);
        
        String vectorStr = "";
        for (int j = 0; j < vector.length; j++) {
          vectorStr += Double.toString(vector[j]);
          if (j < vector.length - 1) {
            vectorStr += ", ";
          }
        }

        // HamaPeer.write(new Integer(1), "1,2,3");
      }
      // Parallelism End

      // Wait for all threads
      RootbeerGpu.syncthreads();
    }
*/  
    System.out.println("Done.");
  }

  private synchronized void assignCenters(int lowestDistantCenter,
      int dimension, int sharedMemoryNewCentersStartPos,
      int sharedMemorySummationCountStartPos, int sharedMemoryInputStartPos) {
    // SharedMemory
    // m_summationCount[lowestDistantCenter]
    // Each thread has its own input vector
    // double[] vector = inputs[thread_idxx]

    // summationCount[lowestDistantCenter]
    int summationCountIndex = sharedMemorySummationCountStartPos
        + (lowestDistantCenter * 4);
    int summationCount = RootbeerGpu.getSharedInteger(summationCountIndex);

    // add own input vector to newCenters
    // m_newCenters[lowestDistantCenter] =
    // addVector(m_newCenters[lowestDistantCenter], vector);

    // dimenstion = m_newCenters[lowestDistantCenter].length
    for (int i = 0; i < dimension; i++) {

      int newCenterIndex = sharedMemoryNewCentersStartPos
          + (((lowestDistantCenter * dimension) + i) * 8);

      int inputIndex = sharedMemoryInputStartPos
          + ((RootbeerGpu.getThreadIdxx() * dimension) + i) * 8;

      // m_newCenters[lowestDistantCenter][j] += vector[j];

      RootbeerGpu.setSharedDouble(
          newCenterIndex,
          RootbeerGpu.getSharedDouble(newCenterIndex)
              + RootbeerGpu.getSharedDouble(inputIndex));
    }

    // summationCount[lowestDistantCenter]++;
    RootbeerGpu.setSharedInteger(summationCountIndex, summationCount + 1);
  }

  private int getNearestCenter(int centerCount, int centersDim,
      int sharedMemoryInputStartPos) {
    int lowestDistantCenter = 0;
    double lowestDistance = Double.MAX_VALUE;

    for (int i = 0; i < centerCount; i++) {
      double estimatedDistance = measureEuclidianDistance(i, centersDim,
          sharedMemoryInputStartPos);
      // System.out.print("estimatedDistance: ");
      // System.out.println(estimatedDistance);

      // check if we have a can assign a new center, because we
      // got a lower distance
      if (estimatedDistance < lowestDistance) {
        lowestDistance = estimatedDistance;
        lowestDistantCenter = i;
      }
    }
    // System.out.print("lowestDistantCenter: ");
    // System.out.println(lowestDistantCenter);
    return lowestDistantCenter;
  }

  private double measureEuclidianDistance(int centerId, int dimension,
      int sharedMemoryInputStartPos) {
    // Measure Distance between center and own input vector
    // double[] vector1 = m_centers[centerId]
    // Each thread has its own input vector
    // double[] vector2 = inputs[thread_idxx]

    double sum = 0;
    for (int i = 0; i < dimension; i++) {

      int inputIdxx = sharedMemoryInputStartPos
          + ((RootbeerGpu.getThreadIdxx() * dimension) + i) * 8;
      int centerIdxx = ((centerId * dimension) + i) * 8;

      // double diff = vector2[i] - vector1[i];
      double diff = RootbeerGpu.getSharedDouble(inputIdxx)
          - RootbeerGpu.getSharedDouble(centerIdxx);

      // multiplication is faster than Math.pow() for ^2.
      sum += (diff * diff);
    }
    return Math.sqrt(sum);
  }

  public static void main(String[] args) {
    // Dummy constructor invocation
    // to keep kernel constructor in
    // rootbeer transformation
    new KMeansHybridKernel(null, 0);
  }
}
