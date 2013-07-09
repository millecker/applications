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

public class MatrixMultiplicationBSPKernel implements Kernel {

  // input
  private double[] aRow;
  private double[][] bColumns;
  private int bColumnSize;
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

  public MatrixMultiplicationBSPKernel(int aRowId, double[] aRow,
      double[][] bColumns) {
    this.aRowId = aRowId;
    this.aRow = aRow;
    this.bColumns = bColumns;
    this.bColumnSize = bColumns[0].length;
  }

  public void gpuMethod() {

    // int blockSize = RootbeerGpu.getBlockDimx();
    // int gridSize = RootbeerGpu.getGridDimx();
    block_idxx = RootbeerGpu.getBlockIdxx();
    thread_idxx = RootbeerGpu.getThreadIdxx();
    // int globalThreadIndex = block_idxx * blockSize + thread_idxx;

    int aRowStartIndex = 0;
    int bColsStartIndex = aRowStartIndex + this.aRow.length * 8;
    int resultsStartIndex = bColsStartIndex + this.bColumns.length
        * this.bColumnSize * 8;

    // shared memory size
    // shared-mem-size = (vector.length * 8)

    // TODO setting up shared memory only within first thread of block does not
    // work!
    // if (thread_idxx == 0) {

    // Put aRow to shared memory
    aRowSharedMemIndex = new int[this.aRow.length];
    aRowSharedMemValues = new double[this.aRow.length];
    for (int i = 0; i < this.aRow.length; i++) {
      aRowSharedMemIndex[i] = aRowStartIndex + i * 8;
      RootbeerGpu.setSharedDouble(aRowStartIndex + i * 8, this.aRow[i]);
      aRowSharedMemValues[i] = RootbeerGpu
          .getSharedDouble(aRowSharedMemIndex[i]);
    }

    // Put bColumns to shared memory
    bColsSharedMemIndex = new int[this.bColumns.length * this.bColumnSize];
    bColsSharedMemValues = new double[this.bColumns.length * this.bColumnSize];

    for (int i = 0; i < this.bColumns.length; i++) {

      for (int j = 0; j < bColumnSize; j++) {
        bColsSharedMemIndex[i * this.bColumnSize + j] = bColsStartIndex
            + (i * this.bColumnSize * 8) + (j * 8);

        RootbeerGpu.setSharedDouble(bColsStartIndex
            + (i * this.bColumnSize * 8) + (j * 8), this.bColumns[i][j]);

        bColsSharedMemValues[i * this.bColumnSize + j] = RootbeerGpu
            .getSharedDouble(bColsSharedMemIndex[i * this.bColumnSize + j]);
      }
    }
    // }

    // Sync threads, until shared memory is established
    RootbeerGpu.syncthreads();

    // Prepare right column for current thread
    bColum = new double[this.bColumnSize];
    for (int i = 0; i < this.bColumnSize; i++) {
      bColum[i] = RootbeerGpu.getSharedDouble(bColsStartIndex
          + (thread_idxx * this.bColumnSize * 8) + (i * 8));
    }

    // Scalar Product of aRow and bColum
    for (int i = 0; i < this.bColumnSize; i++) {
      result += aRow[i] * bColum[i];
    }
    RootbeerGpu.setSharedDouble(resultsStartIndex + (thread_idxx * 8), result);

    // Sync threads, until every kernel has computed the Scalar Product
    RootbeerGpu.syncthreads();

    // Thread 0 of each block builds results
    if (thread_idxx == 0) {
      results = new double[this.bColumnSize];
      for (int i = 0; i < this.bColumnSize; i++) {
        results[i] = RootbeerGpu.getSharedDouble(resultsStartIndex + (i * 8));
      }
    }
  }

  public static void main(String[] args) {
    // Dummy constructor invocation
    // to keep kernel constructor in
    // rootbeer transformation
    new MatrixMultiplicationBSPKernel(0, null, null);
  }
}
