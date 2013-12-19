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

import at.illecker.hama.hybrid.examples.kmeans.util.DenseDoubleVector;
import at.illecker.hama.hybrid.examples.kmeans.util.ObjectList;
import edu.syr.pcpratts.rootbeer.runtime.HamaPeer;
import edu.syr.pcpratts.rootbeer.runtime.Kernel;
import edu.syr.pcpratts.rootbeer.runtime.KeyValuePair;
import edu.syr.pcpratts.rootbeer.runtime.RootbeerGpu;

public class KMeansHybridKernel implements Kernel {

  public ObjectList m_cache = null;
  public double[][] m_centers = null;
  public int m_maxIterations;

  public double[][] m_newCenters;
  public int[] m_summationCount;

  public long m_superstepCount;
  public long m_converged;

  public KMeansHybridKernel(double[][] centers, int maxIterations) {
    m_centers = centers;
    m_maxIterations = maxIterations;
  }

  public void gpuMethod() {
    int blockSize = RootbeerGpu.getBlockDimx();
    int gridSize = RootbeerGpu.getGridDimx();
    int threadCount = blockSize * gridSize;
    int block_idxx = RootbeerGpu.getBlockIdxx();
    int thread_idxx = RootbeerGpu.getThreadIdxx();
    int global_thread_idxx = block_idxx * blockSize + thread_idxx;

    while (true) {

      // assignCenters *****************************************************

      // loop until input is empty
      boolean inputHasMore = true;
      int startIndex = 0;

      while (inputHasMore) {

        double[][] inputs = null;
        int i = 0;

        // thread 0 setup inputs for threads
        if (global_thread_idxx == 0) {

          // if cache is empty read from HamaPeer
          if (m_cache == null) {
            m_cache = new ObjectList();

            String nullValue = "";
            String vectorStr = "";
            KeyValuePair keyValuePair = new KeyValuePair(vectorStr, nullValue);

            while ((inputHasMore = HamaPeer.readNext(keyValuePair)) == true) {
              vectorStr = (String) keyValuePair.getKey();

              DenseDoubleVector vector = new DenseDoubleVector(vectorStr);

              if (inputs == null) {
                inputs = new double[threadCount][vector.getLength()];
              }
              inputs[i] = vector.toArray();
              m_cache.add(vector);

              i++;
              if (i >= threadCount) {
                break;
              }
            }

          } else { // fill inputs from m_cache

            for (int j = startIndex; j < m_cache.getLength(); j++) {

              DenseDoubleVector vector = (DenseDoubleVector) m_cache.get(j);

              if (inputs == null) {
                inputs = new double[threadCount][vector.getLength()];
              }
              inputs[i] = vector.toArray();

              if (j + 1 == m_cache.getLength()) {
                inputHasMore = false;
              }
              // check threadCount
              i++;
              if (i >= threadCount) {
                break;
              }
            }
            startIndex = i;
          }

          m_newCenters = new double[m_centers.length][m_centers[0].length];
          m_summationCount = new int[m_centers.length];
        }

        RootbeerGpu.syncthreads();

        // Parallelism Start
        if (global_thread_idxx < i) {
          // Each thread gets his own vector
          double[] vector = inputs[global_thread_idxx];

          // each thread has all centers, if a center has been updated it needs
          // to be broadcasted.
          int lowestDistantCenter = getNearestCenter(vector);

          assignCenters(lowestDistantCenter, vector);
        }

        // Wait for all threads
        RootbeerGpu.syncthreads();
        // Parallelism End

      }

      // sendMessages *****************************************************
      // thread 0 sends messages about the local updates to each other peer
      if (global_thread_idxx == 0) {
        for (int i = 0; i < m_centers.length; i++) {

          if (m_summationCount[i] != 0) {
            for (String peerName : HamaPeer.getAllPeerNames()) {

              // centerIndex:incrementCounter:VectorValue1,VectorValue2,VectorValue3
              String message = i + ":" + m_summationCount[i] + ":";
              for (int j = 0; j < m_newCenters[i].length; j++) {
                message += (j < m_newCenters[i].length - 1) ? m_newCenters[i]
                    + ", " : m_newCenters[i];
              }
              HamaPeer.send(peerName, message);
            }
          }
        }
      }

      // Sync all tasks
      HamaPeer.sync();

      // updateCenters *****************************************************
      double[][] msgCenters = new double[m_centers.length][m_centers[0].length];
      int[] msgIncrementSum = new int[m_centers.length];

      // thread 0 fetch messages
      if (global_thread_idxx == 0) {

        int msgCount = HamaPeer.getNumCurrentMessages();
        for (int i = 0; i < msgCount; i++) {

          // centerIndex:incrementCounter:VectorValue1,VectorValue2,VectorValue3
          String message = HamaPeer.getCurrentStringMessage();

          // parse message
          String[] values = message.split(":", 3);
          int centerIndex = Integer.parseInt(values[0]);
          int incrementCounter = Integer.parseInt(values[1]);
          String[] vectorStr = values[2].split(",");
          int len = vectorStr.length;
          double[] msgVector = new double[len];
          for (int j = 0; j < len; j++) {
            msgVector[j] = Double.parseDouble(vectorStr[j]);
          }

          // Update
          if (msgIncrementSum[centerIndex] == 0) {
            msgCenters[centerIndex] = msgVector;
          } else {
            // msgCenters[centerIndex] = addVector(msgCenters[centerIndex],
            // msgVector);
            for (int j = 0; j < msgCenters[centerIndex].length; j++) {
              msgCenters[centerIndex][j] += msgVector[j];
            }
          }
          msgIncrementSum[centerIndex] += incrementCounter;
        }

        // TODO Possible Parallelism

        // divide by how often we globally summed vectors
        for (int i = 0; i < msgCenters.length; i++) {
          // and only if we really have an update for c
          if (msgIncrementSum[i] != 0) {
            // msgCenters[i] = divideVector(msgCenters[i], msgIncrementSum[i]);
            for (int j = 0; j < msgCenters[i].length; j++) {
              msgCenters[i][j] /= msgIncrementSum[i];
            }
          }
        }

        // finally check for convergence by the absolute difference
        long convergedCounter = 0L;
        for (int i = 0; i < msgCenters.length; i++) {

          if (msgIncrementSum[i] != 0) {
            double calculateError = 0;
            for (int j = 0; j < m_centers[i].length; j++) {
              calculateError += Math.abs(m_centers[i][j] - msgCenters[i][j]);
            }

            if (calculateError > 0.0d) {
              m_centers[i] = msgCenters[i];
              convergedCounter++;
            }
          }
        }
        m_converged = convergedCounter;
        m_superstepCount = HamaPeer.getSuperstepCount();

        HamaPeer.reopenInput();
      }

      RootbeerGpu.syncthreads();

      if (m_converged == 0) {
        break;
      }
      if ((m_maxIterations > 0) && (m_maxIterations < m_superstepCount)) {
        break;
      }

    }

    System.out.println("Finished! Writing the assignments...");

    // recalculateAssignmentsAndWrite *****************************************

    boolean inputHasMore = true;
    int startIndex = 0;

    while (inputHasMore) {

      double[][] inputs = null;
      int i = 0;

      // thread 0 setup inputs for threads
      if (global_thread_idxx == 0) {

        for (int j = startIndex; j < m_cache.getLength(); j++) {

          DenseDoubleVector vector = (DenseDoubleVector) m_cache.get(j);

          if (inputs == null) {
            inputs = new double[threadCount][vector.getLength()];
          }
          inputs[i] = vector.toArray();

          if (j + 1 == m_cache.getLength()) {
            inputHasMore = false;
          }
          // check threadCount
          i++;
          if (i >= threadCount) {
            break;
          }
        }
        startIndex = i;
      }

      // Parallelism Start
      RootbeerGpu.syncthreads();

      if (global_thread_idxx < i) {
        // Each thread gets his own vector
        double[] vector = inputs[global_thread_idxx];

        // each thread has all centers, if a center has been updated it needs
        // to be broadcasted.
        int lowestDistantCenter = getNearestCenter(vector);

        String vectorStr = "";
        HamaPeer.write(new Integer(lowestDistantCenter), vectorStr);
      }

      // Wait for all threads
      RootbeerGpu.syncthreads();
      // Parallelism End
    }

    System.out.println("Done.");
  }

  private synchronized void assignCenters(int lowestDistantCenter,
      double[] vector) {

    if (m_summationCount[lowestDistantCenter] == 0) {
      m_newCenters[lowestDistantCenter] = vector;
    } else {
      // add the vector to the center
      // m_newCenters[lowestDistantCenter] = addVector(
      // m_newCenters[lowestDistantCenter], vector);
      for (int j = 0; j < m_newCenters[lowestDistantCenter].length; j++) {
        m_newCenters[lowestDistantCenter][j] += vector[j];
      }
      m_summationCount[lowestDistantCenter]++;
    }
  }

  private int getNearestCenter(double[] vector) {
    int lowestDistantCenter = 0;
    double lowestDistance = Double.MAX_VALUE;

    for (int i = 0; i < m_centers.length; i++) {
      double estimatedDistance = measureEuclidianDistance(m_centers[i], vector);
      // check if we have a can assign a new center, because we
      // got a lower distance
      if (estimatedDistance < lowestDistance) {
        lowestDistance = estimatedDistance;
        lowestDistantCenter = i;
      }
    }
    return lowestDistantCenter;
  }

  private double measureEuclidianDistance(double[] vector1, double[] vector2) {
    double sum = 0;
    int length = vector1.length;
    for (int i = 0; i < length; i++) {
      double diff = vector2[i] - vector1[i];
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
