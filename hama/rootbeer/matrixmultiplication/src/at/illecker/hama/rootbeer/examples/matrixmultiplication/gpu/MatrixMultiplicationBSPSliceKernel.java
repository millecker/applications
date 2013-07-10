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
package at.illecker.hama.rootbeer.examples.matrixmultiplication.gpu;

import edu.syr.pcpratts.rootbeer.runtime.Kernel;
import edu.syr.pcpratts.rootbeer.runtime.RootbeerGpu;

public class MatrixMultiplicationBSPSliceKernel implements Kernel {

  // input
  private double[] aRow;
  private double[][] bColumns;
  public int blockSize;
  public int threadIters;
  // output
  public int aRowId;
  public double[] results = null;

  // debug
  public int[][] bColsSharedMemIndex;
  public double[][] bColsSharedMemValues;
  public double[] multipliers;
  public double[][] currBColumns;
  public int[] sumResultsSetSharedMemIndex;
  public double[] sumResultsSetSharedMemValues;
  public int[] sumResultsGetSharedMemIndex;
  public double[] sumResultsGetSharedMemValues;

  public int thread_idxx;
  public int block_idxx;

  public int sliceSize;
  public int columnsSliceSize;

  public MatrixMultiplicationBSPSliceKernel(int aRowId, double[] aRow,
      double[][] bColumns, int blockSize, int threadIterations) {
    this.aRowId = aRowId;
    this.aRow = aRow;
    this.bColumns = bColumns;
    this.blockSize = blockSize;
    this.threadIters = threadIterations;
  }

  public void gpuMethod() {

    // int blockSize = RootbeerGpu.getBlockDimx();
    // int gridSize = RootbeerGpu.getGridDimx();
    block_idxx = RootbeerGpu.getBlockIdxx();
    thread_idxx = RootbeerGpu.getThreadIdxx();
    // int globalThreadIndex = block_idxx * blockSize + thread_idxx;

    // slizeSize defines how much multipliers of row A a thread has to compute
    // and how much cols of row B
    sliceSize = aRow.length / blockSize;

    // blockIters defines the column slize amount
    // columns of B / blockIters
    columnsSliceSize = this.bColumns.length / threadIters;

    // Shared Memory Start Indexes
    int aRowStartIndex = 0;
    int bColsStartIndex = 0; // aRowStartIndex + sliceSize * 8;
    int columnSlizeResultsStartIndex = bColsStartIndex + columnsSliceSize
        * sliceSize * 8;
    // int resultsStartIndex = columnSlizeResultsStartIndex + blockSize * 8;

    // Setup results only within first thread of block
    if (thread_idxx == 0) {
      results = new double[bColumns[0].length];
    }


    bColsSharedMemIndex = new int[threadIters][columnsSliceSize * sliceSize];
    bColsSharedMemValues = new double[threadIters][columnsSliceSize * sliceSize];

    for (int threadIter = 0; threadIter < threadIters; threadIter++) {

      // Setup columns of B to shared memory
      for (int i = 0; i < columnsSliceSize; i++) {

        for (int j = 0; j < sliceSize; j++) {
          bColsSharedMemIndex[threadIter][i * sliceSize + j] = bColsStartIndex
              + (i * sliceSize * 8) + (j * 8);

          RootbeerGpu.setSharedDouble(bColsStartIndex + (i * sliceSize * 8)
              + (j * 8),
              this.bColumns[(threadIter * columnsSliceSize) + i][thread_idxx
                  * sliceSize + j]);

          bColsSharedMemValues[threadIter][i * sliceSize + j] = RootbeerGpu
              .getSharedDouble(bColsSharedMemIndex[threadIter][i * sliceSize
                  + j]);
        }
      }

      // Sync threads, until shared memory is established
      RootbeerGpu.syncthreads();

      multipliers = new double[sliceSize];
      currBColumns = new double[columnsSliceSize][sliceSize];
      sumResultsSetSharedMemIndex = new int[columnsSliceSize];
      sumResultsSetSharedMemValues = new double[columnsSliceSize];
      sumResultsGetSharedMemIndex = new int[blockSize];
      sumResultsGetSharedMemValues = new double[blockSize];

      // Compute scalar multiplication
      for (int k = 0; k < sliceSize; k++) {

        // double multiplier = RootbeerGpu.getSharedDouble(aRowStartIndex + k *
        // 8);
        double multiplier = this.aRow[thread_idxx * sliceSize + k];
        multipliers[k] = multiplier;

        for (int i = 0; i < columnsSliceSize; i++) {

          double sum = 0;
          for (int j = 0; j < sliceSize; j++) {

            double colBValue = RootbeerGpu.getSharedDouble(bColsStartIndex
                + (i * sliceSize * 8) + (j * 8));

            currBColumns[i][j] = colBValue;

            sum += multiplier * colBValue;
          }

          sumResultsSetSharedMemIndex[i] = columnSlizeResultsStartIndex
              + thread_idxx;
          sumResultsSetSharedMemValues[i] = sum;

          RootbeerGpu.setSharedDouble(columnSlizeResultsStartIndex
              + thread_idxx, sum);

          // Sync threads, until every kernel has computed the scalar
          // multiplication
          RootbeerGpu.syncthreads();

          // Thread 0 of each block accumulates results within each block
          if (thread_idxx == 0) {
            for (int thread_id = 0; thread_id < blockSize; thread_id++) {

              results[(threadIter * columnsSliceSize) + i] += RootbeerGpu
                  .getSharedDouble(columnSlizeResultsStartIndex + thread_id);

              sumResultsGetSharedMemIndex[thread_id] = columnSlizeResultsStartIndex
                  + thread_id;
              sumResultsGetSharedMemValues[thread_id] = results[(threadIter * columnsSliceSize)
                  + i];

            }
          }

          // Sync thread, until thread 0 has collected all results
          RootbeerGpu.syncthreads();
        }
      }
    }

  }

  public static void main(String[] args) {
    // Dummy constructor invocation
    // to keep kernel constructor in
    // rootbeer transformation
    new MatrixMultiplicationBSPSliceKernel(0, null, null, 0, 0);
  }
}
