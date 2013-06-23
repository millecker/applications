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

import edu.syr.pcpratts.rootbeer.runtime.Kernel;
import edu.syr.pcpratts.rootbeer.runtime.RootbeerGpu;

public class MatrixMultiplicationMapperKernel implements Kernel {

  public double[] vector;
  public double[] multiplier;
  public double[] result;

  public int block_idxx;
  public int thread_idxx;
  public int blockSize;
  public int gridSize;
  public int globalThreadIndex;

  // debug values
  public int[] setShareIndex;
  public double[] setShareValue;
  public int[] getShareIndex;
  public double[] getShareValue;

  public MatrixMultiplicationMapperKernel(double[] multiplier, double[] vector) {
    this.vector = vector;
    this.multiplier = multiplier;
  }

  public void gpuMethod() {

    blockSize = RootbeerGpu.getBlockDimx();
    gridSize = RootbeerGpu.getGridDimx();
    block_idxx = RootbeerGpu.getBlockIdxx();
    thread_idxx = RootbeerGpu.getThreadIdxx();

    globalThreadIndex = block_idxx * blockSize + thread_idxx;

    int multiplierStartIndex = 128;
    int vectorStartIndex = multiplierStartIndex + multiplier.length * 8;
    int multiplicationResultsStartIndex = vectorStartIndex + vector.length * 8;

    // First kernel of each block builds up shared memory for its own block
    if (thread_idxx == 0) {

      // Put multiplier to shared memory
      for (int i = 0; i < multiplier.length; i++) {
        RootbeerGpu
            .setSharedDouble(multiplierStartIndex + i * 8, multiplier[i]);
      }

      // Put vector to share memory
      for (int i = 0; i < vector.length; i++) {
        RootbeerGpu.setSharedDouble(vectorStartIndex + i * 8, vector[i]);
      }
    }

    // Sync all kernels, until shared memory was established
    RootbeerGpu.syncthreads();

    
    double currentMultiplier = RootbeerGpu.getSharedDouble(multiplierStartIndex
        + thread_idxx * 8);

    // Scalar Multiplication (Vector x Element)
    for (int i = 0; i < vector.length; i++) {

      double vectorElement = RootbeerGpu.getSharedDouble(vectorStartIndex + i
          * 8);
      double multiplicationResult = vectorElement * currentMultiplier;

      // Store result to shared memory for accumulation
      RootbeerGpu.setSharedDouble(multiplicationResultsStartIndex + thread_idxx
          * vector.length * 8 + i * 8, multiplicationResult);
    }

    // Sync all kernels, wait for scalar multiplications to end
    RootbeerGpu.syncthreads();

    // Parallel scan for accumulation
    // last thread of block is useless
    if ((thread_idxx + 1 < blockSize) && (thread_idxx % 2 == 0)) {

      for (int i = (thread_idxx * vector.length); i < ((thread_idxx + 1) * vector.length); ++i) {
        double a = RootbeerGpu.getSharedDouble(multiplicationResultsStartIndex
            + i * 8);
        double b = RootbeerGpu.getSharedDouble(multiplicationResultsStartIndex
            + i * 8 + (vector.length * 8));

        double c = a + b;
        RootbeerGpu.setSharedDouble(multiplicationResultsStartIndex + i * 8, c);
      }
    }

    // Sync all kernels, wait for accumulations
    RootbeerGpu.syncthreads();

    // First kernel gets final results of accumulations
    if (thread_idxx == 0) {
      this.result = new double[vector.length];

      for (int i = 1; i < vector.length; i++) {
        result[i] += RootbeerGpu
            .getSharedDouble(multiplicationResultsStartIndex + i * 8);
      }
    }

  }

  public static void main(String[] args) {
    // Dummy constructor invocation
    // to keep kernel constructor in
    // rootbeer transformation
    new MatrixMultiplicationMapperKernel(null, null);
  }
}
