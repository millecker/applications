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
    int vectorStartIndex = multiplierStartIndex + this.multiplier.length * 8;

    // Put multiplier to shared memory
    for (int i = 0; i < this.multiplier.length; i++) {
      RootbeerGpu.setSharedDouble(multiplierStartIndex + i * 8,
          this.multiplier[i]);
    }

    // Put vector to share memory
    for (int i = 0; i < this.vector.length; i++) {
      RootbeerGpu.setSharedDouble(vectorStartIndex + i * 8, this.vector[i]);
    }

    // Sync all kernels, until shared memory was established
    RootbeerGpu.syncthreads();

    // Get multiplier of individual thread
    double currentMultiplier = RootbeerGpu.getSharedDouble(multiplierStartIndex
        + thread_idxx * 8);

    // Scalar Multiplication (Vector x Element)
    this.result = new double[this.vector.length];
    this.getShareIndex = new int[this.vector.length];
    this.getShareValue = new double[this.vector.length];

    for (int i = 0; i < this.vector.length; i++) {

      double vectorElement = RootbeerGpu.getSharedDouble(vectorStartIndex + i
          * 8);
      double multiplicationResult = vectorElement * currentMultiplier;

      // Store result to shared memory for accumulation
      // RootbeerGpu.setSharedDouble(multiplicationResultsStartIndex +
      // thread_idxx
      // * this.vector.length * 8 + i * 8, multiplicationResult);

      getShareIndex[i] = vectorStartIndex + i * 8;
      getShareValue[i] = vectorElement;

      this.result[i] = multiplicationResult;
    }

  }

  public static void main(String[] args) {
    // Dummy constructor invocation
    // to keep kernel constructor in
    // rootbeer transformation
    new MatrixMultiplicationMapperKernel(null, null);
  }
}
