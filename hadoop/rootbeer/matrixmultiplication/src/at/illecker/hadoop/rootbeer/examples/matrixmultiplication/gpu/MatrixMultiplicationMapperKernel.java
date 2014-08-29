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
package at.illecker.hadoop.rootbeer.examples.matrixmultiplication.gpu;

import org.trifort.rootbeer.runtime.Kernel;
import org.trifort.rootbeer.runtime.RootbeerGpu;

public class MatrixMultiplicationMapperKernel implements Kernel {

  private double[] m_matrixA; // matrix A is transposed
  private double[] m_matrixB;
  private double[] m_matrixC;
  private int m_N;
  private int m_M;
  private int m_L;
  private int m_gridSize;
  private int m_blockSize;
  private int m_tileWidth;
  private int m_subMatricesPerThread;

  public MatrixMultiplicationMapperKernel(double[] transposedmatrixA,
      double[] matrixB, double[] matrixC, int n, int m, int l, int gridSize,
      int blockSize, int tileWidth, int subMatricesPerThread) {
    m_matrixA = transposedmatrixA; // m x n
    m_matrixB = matrixB; // m x l
    m_matrixC = matrixC; // n x l
    m_N = n;
    m_M = m;
    m_L = l;
    m_gridSize = gridSize;
    m_blockSize = blockSize;
    m_tileWidth = tileWidth; // 32 by default
    m_subMatricesPerThread = subMatricesPerThread;
  }

  // SharedMemory per block
  // blockSize = 1024
  // => 12 (needed by Rootbeer) + (2 * 1024 * 8 (double)) = 16396 bytes
  //
  // based on
  // http://www.shodor.org/media/content//petascale/materials/UPModules/matrixMultiplication/moduleDocument.pdf
  //
  public void gpuMethod() {
    // get local blockIdx and threadIdx
    int block_idxx = RootbeerGpu.getBlockIdxx();
    int thread_idxx = RootbeerGpu.getThreadIdxx();

    // store fields into local variables
    // each read from a field hits global ram while a local variable
    // is most likely stored in a register
    // int gridSize = m_gridSize;
    // int blockSize = m_blockSize;
    int N = m_N;
    // int M = m_M;
    int L = m_L;
    int tileWidth = m_tileWidth;
    int subMatricesPerThread = m_subMatricesPerThread;

    // store pointers to arrays in local variable
    double[] matrixA = m_matrixA;
    double[] matrixB = m_matrixB;
    double[] matrixC = m_matrixC;

    // Convert block_idxx to a two dimensional index
    int blockRow = block_idxx / (L / tileWidth);
    int blockCol = block_idxx % (L / tileWidth);

    // Convert thread_idxx to a two dimensional index within submatrix
    int threadRow = thread_idxx / tileWidth;
    int threadCol = thread_idxx % tileWidth;

    // Calculate the index of the destination row and col within submatrix
    int destRow = (blockRow * tileWidth) + threadRow;
    int destCol = (blockCol * tileWidth) + threadCol;

    // print(RootbeerGpu.getThreadId(), colA, colB, 0, 0, 0);

    double sum = 0;

    // Loop over all the sub-matrices of A and B that are
    // required to compute Csub
    // Multiply each pair of sub-matrices together
    // and accumulate the results
    for (int m = 0; m < subMatricesPerThread; m++) {
      int aRowIndex = (m * tileWidth) + threadRow;
      int aColIndex = (blockRow * tileWidth) + threadCol;
      int aValueIndex = (aRowIndex * N) + aColIndex;

      int bRowIndex = (m * tileWidth) + threadRow;
      int bColIndex = destCol;
      int bValueIndex = (bRowIndex * L) + bColIndex;

      double aValue = matrixA[aValueIndex];
      double bValue = matrixB[bValueIndex];

      // store the aValue into shared memory at location
      RootbeerGpu.setSharedDouble(thread_idxx * 8, aValue);
      // store the bValue into shared memory at location
      // 1024 is the offset for the row of matrix A
      RootbeerGpu.setSharedDouble((1024 + thread_idxx * 8), bValue);

      // sync threads within a block to make sure the sub-matrices are loaded
      RootbeerGpu.syncthreads();

      // loop over all of aValues and bValues
      for (int k = 0; k < tileWidth; k++) {
        // read the aValue from shared memory
        aValue = RootbeerGpu.getSharedDouble((k * tileWidth + threadRow) * 8);
        // read the bValue from shared memory
        bValue = RootbeerGpu
            .getSharedDouble((1024 + k * tileWidth + threadCol) * 8);

        // multiply aValue and bValue and accumulate
        sum += aValue * bValue;
      }

      // sync threads within a block to make sure that the preceding
      // computation is done before loading two new
      // sub-matrices of A and B in the next iteration
      RootbeerGpu.syncthreads();
    }

    int cValueIndex = destRow * L + destCol;
    // update the target cValue with the sum
    matrixC[cValueIndex] = sum;
  }

  public static void main(String[] args) {
    // Dummy constructor invocation
    // to keep kernel constructor in
    // rootbeer transformation
    new MatrixMultiplicationMapperKernel(null, null, null, 0, 0, 0, 0, 0, 0, 0);
  }
}
