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

import java.util.List;
import java.util.Random;

import edu.syr.pcpratts.rootbeer.runtime.Kernel;
import edu.syr.pcpratts.rootbeer.runtime.Rootbeer;
import edu.syr.pcpratts.rootbeer.runtime.RootbeerGpu;
import edu.syr.pcpratts.rootbeer.runtime.StatsRow;
import edu.syr.pcpratts.rootbeer.runtime.util.Stopwatch;

public class MatrixMultiplicationBSPKernel implements Kernel {

  // input
  private double[][] rowsA;
  private double[][] matrixB;
  public int threadSliceSize;
  public int blockSliceSize;
  // output
  public ResultMatrix resultMatrix;

  public MatrixMultiplicationBSPKernel(double[][] rowsA, double[][] matrixB,
      int threadSliceSize, int blockSliceSize) {
    this.rowsA = rowsA;
    this.matrixB = matrixB;
    this.threadSliceSize = threadSliceSize;
    this.blockSliceSize = blockSliceSize;
    resultMatrix = new ResultMatrix(rowsA.length,this.matrixB[0].length);
  }

  public void gpuMethod() {

    // int blockSize = RootbeerGpu.getBlockDimx();
    // int gridSize = RootbeerGpu.getGridDimx();
    int block_idxx = RootbeerGpu.getBlockIdxx();
    int thread_idxx = RootbeerGpu.getThreadIdxx();
    // int globalThreadIndex = block_idxx * blockSize + thread_idxx;

    int matrixARowSize = rowsA.length;
    int matrixAColSize = rowsA[0].length;
    int matrixBRowSize = this.matrixB.length;
    int matrixBColSize = this.matrixB[0].length;

    // Check for wrong matrix sizes
    if (matrixAColSize != matrixBRowSize) {
      return;
    }

    // Init intermediateSums shared memory
    RootbeerGpu.setSharedDouble(thread_idxx * 8, 0);
    RootbeerGpu.syncthreads();

    // Check if thread and block is in matrix range
    if ((block_idxx < matrixBColSize * blockSliceSize)
        && (thread_idxx < matrixBRowSize * threadSliceSize)) {

      // cuPrintf("[%d,%d]device_method started. thread_id: %d, block_id: %d\n",
      // thread_idxx, block_idxx, thread_idxx, block_idxx);

      // Setup multipliers of matrix B (slized Matrix)
      double multipliers[][] = new double[blockSliceSize][threadSliceSize];
      for (int k = 0; k < blockSliceSize; k++) {
        for (int j = 0; j < threadSliceSize; j++) {

          if ((k + (blockSliceSize * block_idxx)) < matrixBColSize) {

            multipliers[k][j] = matrixB[(thread_idxx * threadSliceSize + j)][(block_idxx * blockSliceSize)
                + k];

          } else {
            multipliers[k][j] = 0;
          }

          // cuPrintf("[%d,%d]multipliers[%d][%d]: %d\n", thread_idxx,
          // block_idxx, k, j, multipliers[k][j]);
        }
      }

      // Setup columns of matrix A
      double[][] matrixAColumns = new double[threadSliceSize][matrixARowSize];
      for (int k = 0; k < threadSliceSize; k++) {
        for (int i = 0; i < matrixARowSize; i++) {
          matrixAColumns[k][i] = rowsA[i][(thread_idxx * threadSliceSize) + k];

          // cuPrintf("[%d,%d]matrixAColumns setup[%d][%d]: %d\n",
          // thread_idxx, block_idxx, k, i, matrixAColumns[k][i]);
        }
      }

      // Calculate scalar multiplication
      for (int k = 0; k < blockSliceSize; k++) {
        for (int i = 0; i < matrixARowSize; i++) {

          int sum = 0;
          for (int j = 0; j < threadSliceSize; j++) {

            // cuPrintf("[%d,%d]matrixAColumns read[%d][%d]: %d\n",
            // thread_idxx, block_idxx, j, i,
            // matrixAColumns[j][i]);
            // cuPrintf("[%d,%d]multipliers[%d][%d]: %d\n", thread_idxx,
            // block_idxx, k, j, multipliers[k][j]);

            sum += matrixAColumns[j][i] * multipliers[k][j];
          }

          // cuPrintf("[%d,%d]sum: %d ,matrixARow: %d\n", thread_idxx,
          // block_idxx, sum, i);

          RootbeerGpu.setSharedDouble(thread_idxx * 8, sum);
          RootbeerGpu.syncthreads();

          // do reduction in shared memory
          // 1-bit right shift = divide by two to the power 1

          // for (int s = matrixARowSize / 2; s > 0; s >>= 1) { if (thread_idxx
          // < s) { intermediateSums[thread_idxx] +=
          // intermediateSums[thread_idxx + s]; } syncthreads(); }

          if (thread_idxx == 0) {

            for (int t = 1; t < matrixARowSize; t++) {
              sum += RootbeerGpu.getSharedDouble(t * 8);
            }

            // cuPrintf(
            // "[%d,%d]final sum: %d (i:%d,k:%d,blockSliceSize:%d,threadSliceSize:%d)\n",
            // thread_idxx, block_idxx, sum, i, k, blockSliceSize,
            // threadSliceSize);

            if (sum != 0) {

              Result result = new Result();
              result.x = i;
              result.y = (blockSliceSize * block_idxx) + k;
              result.value = sum;
              resultMatrix.add(result);
              
              resultMatrix.set(i, ((blockSliceSize * block_idxx) + k), sum);
            }
          }
        }
      }
    }
  }

  public static void main(String[] args) {

    // nvcc ~/.rootbeer/generated.cu --ptxas-options=-v -arch sm_35
    // ptxas info : Used 39 registers, 40984 bytes smem, 380 bytes cmem[0], 88
    // bytes cmem[2]

    // using -maxrregcount 32
    // using -shared-mem-size 1024*8 + 12 = 8192 + 12 = 8204

    // BlockSize = 1024
    // GridSize = 14

    boolean DEBUG = false;
    int n = 4;
    int blockSize = 1024; // threads
    int gridSize = 14; // blocks

    if (args.length > 0) {
      n = Integer.parseInt(args[0]);
      blockSize = Integer.parseInt(args[1]);
      gridSize = Integer.parseInt(args[2]);
      DEBUG = Boolean.parseBoolean(args[3]);
    }

    System.out.println("MatrixMultiplicationBSPKernel,n=" + n + ",blockSize="
        + blockSize + ",gridSize=" + gridSize);

    // threadSliceSize defines how much multipliers
    // of column B has to be multiplied with column A
    int threadSliceSize = divup(n, blockSize);

    // blockSliceSize defines the column slice amount
    // columns of B per blockIters
    int blockSliceSize = divup(n, gridSize);

    System.out.println("MatrixMultiplicationBSPKernel,threadSliceSize="
        + threadSliceSize + ",blockSliceSize=" + blockSliceSize);

    double[][] matrixA = createRandomArray(n, n, new Random(42L));
    double[][] matrixB = createRandomArray(n, n, new Random(1337L));
    //double[][] matrixC = createConstantArray(n, n, 0);

    if (DEBUG) {
      System.out.println("MatrixA");
      printArray(matrixA, n, n);
      System.out.println("MatrixB");
      printArray(matrixB, n, n);
      //System.out.println("MatrixC");
      //printArray(matrixC, n, n);
    }

    MatrixMultiplicationBSPKernel kernel = new MatrixMultiplicationBSPKernel(
        matrixA, matrixB, threadSliceSize, blockSliceSize);
    Rootbeer rootbeer = new Rootbeer();
    rootbeer.setThreadConfig(blockSize, gridSize, blockSize * gridSize);

    // Run GPU Kernels
    Stopwatch watch = new Stopwatch();
    watch.start();
    rootbeer.runAll(kernel);
    watch.stop();

    System.out.println("MatrixMultiplicationBSPKernel,GPUTime="
        + watch.elapsedTimeMillis() + "ms");
    System.out.println("MatrixMultiplicationBSPKernel,Threads=" + blockSize
        * gridSize);

    List<StatsRow> stats = rootbeer.getStats();
    for (StatsRow row : stats) {
      System.out.println("  StatsRow:\n");
      System.out.println("    init time: " + row.getInitTime() + "\n");
      System.out.println("    serial time: " + row.getSerializationTime()
          + "\n");
      System.out.println("    exec time: " + row.getExecutionTime() + "\n");
      System.out.println("    deserial time: " + row.getDeserializationTime()
          + "\n");
      System.out.println("    num blocks: " + row.getNumBlocks() + "\n");
      System.out.println("    num threads: " + row.getNumThreads() + "\n");
    }

    // Get GPU Result
    double[][] matrixC = kernel.resultMatrix.getMatrix();

    List<Result> resultList = kernel.resultMatrix.getList();
    System.out.println("results: " + resultList.size());

    for (Result result : resultList) {
      if (DEBUG) {
        System.out.println(result.toString());
      }
      matrixC[result.x][result.y] = result.value;
    }

    double[][] matrixD = multiply(matrixA, matrixB, n, n, n);

    boolean verifyResult = verify(matrixC, matrixD, n, n);
    if (verifyResult) {
      System.out.println("Verify PASSED!");
    } else {
      System.out.println("Verify FAILED!");
    }

    if (DEBUG) {
      System.out.println("MatrixC");
      printArray(matrixC, n, n);
      System.out.println("MatrixD");
      printArray(matrixD, n, n);
    }
  }

  static double[][] createConstantArray(int n, int m, double value) {
    final double data[][] = new double[n][m];
    for (int j = 0; j < n; ++j) {
      for (int i = 0; i < m; ++i) {
        data[j][i] = value;
      }
    }
    return data;
  }

  static double[][] createRandomArray(int n, int m, Random rand) {
    final double data[][] = new double[n][m];
    for (int j = 0; j < n; ++j) {
      for (int i = 0; i < m; ++i) {
        // matrix[i][j] = rand.nextDouble();
        data[i][j] = rand.nextInt(9) + 1; // between 1 and 10
      }
    }
    return data;
  }

  static void printArray(double[][] data, int n, int m) {
    for (int j = 0; j < n; ++j) {
      for (int i = 0; i < m; ++i) {
        if (i == m - 1) {
          System.out.println(data[j][i] + "]");
        } else if (i == 0) {
          System.out.print("[" + data[j][i] + ",");
        } else {
          System.out.print(data[j][i] + ",");
        }
      }
    }
    System.out.println();
  }

  static double[][] multiply(double[][] matrixA, double[][] matrixB,
      int a_rows, int a_cols, int b_cols) {
    final double data[][] = new double[a_rows][b_cols];

    for (int k = 0; k < a_cols; k++) {
      for (int i = 0; i < a_rows; i++) {
        for (int j = 0; j < b_cols; j++) {
          data[i][j] += matrixA[i][k] * matrixB[k][j];
        }
      }
    }
    return data;
  }

  static boolean verify(double[][] matrixA, double[][] matrixB, int n, int m) {
    for (int j = 0; j < n; ++j) {
      for (int i = 0; i < m; ++i) {
        if (matrixA[j][i] != matrixB[j][i]) {
          System.out.println("Verify ERROR at [" + j + "," + i + "]");
          return false;
        }
      }
    }
    return true;
  }

  static int divup(int x, int y) {
    if (x % y != 0) {
      // aufrunden
      return ((x + y - 1) / y);
    } else {
      return x / y;
    }
  }
}
