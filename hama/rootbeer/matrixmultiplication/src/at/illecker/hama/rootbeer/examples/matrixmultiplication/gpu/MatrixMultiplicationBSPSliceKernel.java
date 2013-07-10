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
  // output
  public int aRowId;
  public double[] results = null;

  // debug
  public int[] aRowSharedMemIndex;
  public double[] aRowSharedMemValues;
  public int[] bColsSharedMemIndex;
  public double[] bColsSharedMemValues;
  public int thread_idxx;
  public int block_idxx;
  public double[] bColum;
  public double result;

  public MatrixMultiplicationBSPSliceKernel(int aRowId, double[] aRow,
      double[][] bColumns) {
    this.aRowId = aRowId;
    this.aRow = aRow;
    this.bColumns = bColumns;
  }

  public void gpuMethod() {

    int blockSize = RootbeerGpu.getBlockDimx();
    // int gridSize = RootbeerGpu.getGridDimx();
    block_idxx = RootbeerGpu.getBlockIdxx();
    thread_idxx = RootbeerGpu.getThreadIdxx();
    // int globalThreadIndex = block_idxx * blockSize + thread_idxx;

    // slizeSize defines how much multipliers of row A a thread has to compute
    // and how much cols of row B
    int sliceSize = aRow.length / blockSize;

    // blockIters defines the column slize amount
    // columns of B / blockIters
    int blockIters = 1;
    int columnsSliceSize = this.bColumns.length / blockIters;

    // Shared Memory Start Indexes
    int aRowStartIndex = 0;
    int bColsStartIndex = aRowStartIndex + sliceSize * 8;
    int columnSlizeResultsStartIndex = bColsStartIndex + columnsSliceSize
        * sliceSize * 8;
    // int resultsStartIndex = columnSlizeResultsStartIndex + blockSize * 8;

    // Setup results only within first thread of block
    if (thread_idxx == 0) {
      results = new double[bColumns[0].length];
    }

    // Setup multipliers of row A to shared memory
    aRowSharedMemIndex = new int[sliceSize];
    aRowSharedMemValues = new double[sliceSize];
    for (int i = 0; i < sliceSize; i++) {
      aRowSharedMemIndex[i] = aRowStartIndex + i * 8;

      RootbeerGpu.setSharedDouble(aRowStartIndex + i * 8, this.aRow[thread_idxx
          * sliceSize + i]);

      aRowSharedMemValues[i] = RootbeerGpu
          .getSharedDouble(aRowSharedMemIndex[i]);
    }

    for (int blockIter = 0; blockIter < blockIters; blockIter++) {

      // Setup columns of B to shared memory
      bColsSharedMemIndex = new int[columnsSliceSize * sliceSize];
      bColsSharedMemValues = new double[columnsSliceSize * sliceSize];
      for (int i = 0; i < columnsSliceSize; i++) {

        for (int j = 0; j < sliceSize; j++) {
          bColsSharedMemIndex[i * sliceSize + j] = bColsStartIndex
              + (i * sliceSize * 8) + (j * 8);

          RootbeerGpu.setSharedDouble(bColsStartIndex + (i * sliceSize * 8)
              + (j * 8),
              this.bColumns[(blockIter * columnsSliceSize) + i][thread_idxx
                  * sliceSize + j]);

          bColsSharedMemValues[i * sliceSize + j] = RootbeerGpu
              .getSharedDouble(bColsSharedMemIndex[i * sliceSize + j]);
        }
      }

      // Sync threads, until shared memory is established
      RootbeerGpu.syncthreads();

      // Compute scalar multiplication
      for (int k = 0; k < sliceSize; k++) {

        double multiplier = RootbeerGpu.getSharedDouble(aRowStartIndex + k * 8);

        for (int i = 0; i < columnsSliceSize; i++) {

          double sum = 0;
          for (int j = 0; j < sliceSize; j++) {

            double colBValue = RootbeerGpu.getSharedDouble(bColsStartIndex
                + (i * sliceSize * 8) + (j * 8));

            sum += multiplier * colBValue;
          }
          RootbeerGpu.setSharedDouble(columnSlizeResultsStartIndex
              + thread_idxx, sum);

          // Sync threads, until every kernel has computed the scalar
          // multiplication
          RootbeerGpu.syncthreads();

          // Thread 0 of each block accumulates results within each block
          if (thread_idxx == 0) {
            for (int thread_id = 0; thread_id < blockSize; thread_id++) {
              results[(blockIter * columnsSliceSize) + i] += RootbeerGpu
                  .getSharedDouble(columnSlizeResultsStartIndex + thread_id);
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
    new MatrixMultiplicationBSPSliceKernel(0, null, null);
  }
}
