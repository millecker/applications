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

public class MatrixMultiplicationBSPSliceKernelCleaned implements Kernel {

  // input
  private double[][] rowsA;
  private double[][] matrixB;
  private int blockSize;
  private int gridSize;
  // output
  public ResultList resultList;

  public MatrixMultiplicationBSPSliceKernelCleaned(double[][] rowsA,
      double[][] matrixB, int blockSize, int gridSize) {
    this.rowsA = rowsA;
    this.matrixB = matrixB;
    this.blockSize = blockSize;
    this.gridSize = gridSize;
    resultList = new ResultList();
  }

  public void gpuMethod() {

    // int blockSize = RootbeerGpu.getBlockDimx();
    // int gridSize = RootbeerGpu.getGridDimx();
    int block_idxx = RootbeerGpu.getBlockIdxx();
    int thread_idxx = RootbeerGpu.getThreadIdxx();
    // int globalThreadIndex = block_idxx * blockSize + thread_idxx;

    int matrixARows = rowsA.length;
    int matrixAColSize = rowsA[0].length;
    int matrixBRowSize = this.matrixB.length;
    int matrixBColSize = this.matrixB[0].length;

    // Check for wrong matrix sizes
    if (matrixAColSize != matrixBRowSize) {
      return;
    }

    // threadSliceSize defines how much multipliers of row A a thread has to
    // compute with rows of col B
    // TODO constant distribution
    int threadSliceSize = matrixAColSize / blockSize;

    // blockSliceSize defines the column slice amount
    // columns of B per blockIters
    // TODO constant distribution
    int blockSliceSize = matrixBColSize / gridSize;

    // Shared Memory Start Indexes
    int bColsStartIndex = 0;
    int threadSlizeResultsStartIndex = bColsStartIndex
        + (blockSize * blockSliceSize * threadSliceSize * 8);

    // Each thread sets its own shared memory within their blocks
    // Setup columns of matrix B to shared memory
    for (int i = 0; i < blockSliceSize; i++) {
      for (int j = 0; j < threadSliceSize; j++) {

        int sharedMemIndex = bColsStartIndex
            + (thread_idxx * blockSliceSize * threadSliceSize * 8)
            + (i * threadSliceSize * 8) + (j * 8);

        RootbeerGpu.setSharedDouble(sharedMemIndex, this.matrixB[thread_idxx
            * threadSliceSize + j][(block_idxx * blockSliceSize) + i]);
      }
    }

    // Sync threads, until shared memory is established
    RootbeerGpu.syncthreads();

    // Calculate scalar product
    for (int k = 0; k < blockSliceSize; k++) {

      for (int i = 0; i < matrixARows; i++) {

        double sum = 0;
        for (int j = 0; j < threadSliceSize; j++) {

          double multiplier = rowsA[i][(thread_idxx * threadSliceSize) + j];

          sum += multiplier
              * RootbeerGpu.getSharedDouble(bColsStartIndex
                  + (thread_idxx * blockSliceSize * threadSliceSize * 8)
                  + (k * threadSliceSize * 8) + (j * 8));
        }

        int sharedMemIndex = threadSlizeResultsStartIndex
            + (k * matrixARows * 8)
            + (thread_idxx * threadSliceSize * blockSliceSize * matrixARows * 8)
            + (i * 8);

        RootbeerGpu.setSharedDouble(sharedMemIndex, sum);
      }
    }

    // Sync threads, until every thread has finished
    RootbeerGpu.syncthreads();

    // TODO
    // Do parallel scan instead of sequential accumulation

    // Thread 0 of each block accumulates results
    if (thread_idxx == 0) {

      int[] resultColsIndex = new int[blockSliceSize];
      double[][] resultCols = new double[blockSliceSize][matrixARows];

      for (int i = 0; i < blockSliceSize; i++) {

        for (int j = 0; j < matrixARows; j++) {

          double sum = 0;
          // Collect results for each thread
          for (int thread_id = 0; thread_id < blockSize; thread_id++) {

            int sharedMemIndex = threadSlizeResultsStartIndex
                + (i * matrixARows * 8)
                + (thread_id * matrixARows * blockSliceSize * 8) + (j * 8);

            sum += RootbeerGpu.getSharedDouble(sharedMemIndex);
          }

          resultColsIndex[i] = (block_idxx * blockSliceSize) + i;
          resultCols[i][j] = sum;

        }
      }

      // Resulting output
      Result result = new Result();
      result.thread_idxx = thread_idxx;
      result.block_idxx = block_idxx;
      result.threadSliceSize = threadSliceSize;
      result.blockSliceSize = blockSliceSize;
      result.resultColsIndex = resultColsIndex;
      result.resultCols = resultCols;
      resultList.add(result);
    }

  }

  public static void main(String[] args) {
    // Dummy invocations to keep methods via
    // rootbeer transformation
    new MatrixMultiplicationBSPSliceKernelCleaned(null, null, 0, 0);
    new ResultList().getList();
    new Result().toString();
  }
}
