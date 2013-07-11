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
  private double[][] rowsA;
  private double[][] matrixB;
  public int blockSize;
  public int gridSize;
  // output
  public int[] rowAId;
  public double[] results = null;

  // debug
  /*
  public double[] multipliers;
  public double[][] currBColumns;
  public int[] sumResultsSetSharedMemIndex;
  public double[] sumResultsSetSharedMemValues;
  public int[] sumResultsGetSharedMemIndex;
  public double[] sumResultsGetSharedMemValues;
  */
  public ResultList resultList;
  

  public MatrixMultiplicationBSPSliceKernel(int[] rowAId, double[][] rowsA,
      double[][] matrixB, int blockSize, int gridSize) {
    this.rowAId = rowAId;
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

    int matrixAColSize = rowsA[0].length;
    int matrixBRowSize = this.matrixB.length;
    int matrixBColSize = this.matrixB[0].length;
    
    // Check for wrong matrix sizes
    if (matrixAColSize != matrixBRowSize) {
      return;
    }

    // threadSliceSize defines how much multipliers of row A a thread has to
    // compute with rows of col B
    int threadSliceSize = matrixAColSize / blockSize;

    // blockSliceSize defines the column slice amount
    // columns of B per blockIters
    int blockSliceSize = matrixBColSize / gridSize;

    // Setup results only within first thread of block
    if (thread_idxx == 0) {
      results = new double[matrixBColSize];
    }

    // Shared Memory Start Indexes
    int aRowStartIndex = 0;
    int bColsStartIndex = 0; // aRowStartIndex + sliceSize * 8;
    // int columnSlizeResultsStartIndex = bColsStartIndex + columnsSliceSize
    // * sliceSize * 8;
    // int resultsStartIndex = columnSlizeResultsStartIndex + blockSize * 8;

    int[] bColsSharedMemIndex = new int[blockSliceSize * threadSliceSize];
    double[] bColsSharedMemValues = new double[blockSliceSize * threadSliceSize];

      // Setup columns of matrix B to shared memory
      for (int i = 0; i < blockSliceSize; i++) {
        for (int j = 0; j < threadSliceSize; j++) {
          
          int sharedMemIndex = bColsStartIndex 
              + (thread_idxx * blockSliceSize * threadSliceSize * 8) 
              + (i * threadSliceSize * 8) + (j * 8);
          
          bColsSharedMemIndex[(i * threadSliceSize) + j] = sharedMemIndex;

          RootbeerGpu.setSharedDouble(sharedMemIndex,
              this.matrixB[(blockSliceSize * threadSliceSize) + i][thread_idxx * threadSliceSize + j]);

          bColsSharedMemValues[(i * threadSliceSize) + j] = RootbeerGpu
              .getSharedDouble(sharedMemIndex);
        }
      }

      // Sync threads, until shared memory is established
      RootbeerGpu.syncthreads();
      
      Result result = new Result();          
      result.thread_idxx = thread_idxx;
      result.block_idxx = block_idxx;
      result.threadSliceSize = threadSliceSize;
      result.blockSliceSize = blockSliceSize;
      result.bColsSharedMemIndex = bColsSharedMemIndex;
      result.bColsSharedMemValues = bColsSharedMemValues;
      resultList.add(result);
      
      
      /*
      
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
*/
  }

  public static void main(String[] args) {
    // Dummy invocations to keep methods via
    // rootbeer transformation
    new MatrixMultiplicationBSPSliceKernel(null, null, null, 0, 0);
    new ResultList().getList();
    new Result().toString();
  }
}
