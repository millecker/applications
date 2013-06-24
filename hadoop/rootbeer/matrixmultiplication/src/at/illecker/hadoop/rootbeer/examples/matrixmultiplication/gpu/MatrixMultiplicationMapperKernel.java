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

  private double[] vector;
  private double[] multiplier;
  public double[][] results;

  // debug
  public double multiplierVal;
  public double[] vectorVal;
  public int thread_idxx;

  public MatrixMultiplicationMapperKernel(double[] multiplier, double[] vector) {
    this.vector = vector;
    this.multiplier = multiplier;
    this.results = new double[multiplier.length][vector.length];
    for (int i = 0; i < multiplier.length; i++) {
      this.results[i] = null;
    }
  }

  public void gpuMethod() {

    // int blockSize = RootbeerGpu.getBlockDimx();
    // int gridSize = RootbeerGpu.getGridDimx();
    // int block_idxx = RootbeerGpu.getBlockIdxx();
    thread_idxx = RootbeerGpu.getThreadIdxx();
    // int globalThreadIndex = block_idxx * blockSize + thread_idxx;

    int vectorStartIndex = 0;

    // Setup shared memory
    // shared-mem-size = (vector.length * 8) * gridSize
    
    // TODO setting up shared memory only within first thread of block does not work!
    // if (thread_idxx == 0) {
      // Put vector to share memory
      for (int i = 0; i < this.vector.length; i++) {
        RootbeerGpu.setSharedDouble(vectorStartIndex + i * 8, this.vector[i]);
      }
    // }

    // Sync all kernels, until shared memory was established
    RootbeerGpu.syncthreads();

    // Get multiplier depending on thread_index
    double currentMultiplier = multiplier[thread_idxx];

    // debug
    multiplierVal = currentMultiplier;
    vectorVal = new double[this.vector.length];

    // Scalar Multiplication (Vector x Element)
    double[] result = new double[this.vector.length];
    for (int i = 0; i < this.vector.length; i++) {

      double vectorElement = RootbeerGpu.getSharedDouble(vectorStartIndex + i
          * 8);

      vectorVal[i] = vectorElement;
      result[i] = vectorElement * currentMultiplier;

    }

    RootbeerGpu.syncthreads();

    addResult(thread_idxx, result);
  }

  private synchronized void addResult(int row, double[] vector) {
    if (this.results[row] != null) {
      for (int i = 0; i < vector.length; i++) {
        this.results[row][i] += vector[i];
      }
    } else {
      this.results[row] = vector;
    }
  }

  public static void main(String[] args) {
    // Dummy constructor invocation
    // to keep kernel constructor in
    // rootbeer transformation
    new MatrixMultiplicationMapperKernel(null, null);
  }
}
