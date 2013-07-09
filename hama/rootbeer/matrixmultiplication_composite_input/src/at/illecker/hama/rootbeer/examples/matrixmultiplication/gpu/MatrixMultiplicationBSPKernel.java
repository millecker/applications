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
  private double[] vector;
  private double multiplier;
  // output
  public int row;
  public double[] results;

  // debug
  public double multiplierVal;
  public double[] vectorVal;
  public int thread_idxx;

  public MatrixMultiplicationBSPKernel(int row, double multiplier,
      double[] vector) {
    this.row = row;
    this.multiplier = multiplier;
    this.vector = vector;
    this.results = new double[this.vector.length];
  }

  public void gpuMethod() {

    // int blockSize = RootbeerGpu.getBlockDimx();
    // int gridSize = RootbeerGpu.getGridDimx();
    // int block_idxx = RootbeerGpu.getBlockIdxx();
    thread_idxx = RootbeerGpu.getThreadIdxx();
    // int globalThreadIndex = block_idxx * blockSize + thread_idxx;

    int vectorStartIndex = 0;

    // shared memory size
    // shared-mem-size = (vector.length * 8)

    // TODO setting up shared memory only within first thread of block does not
    // work!
    // if (thread_idxx == 0) {
    // Put vector to share memory
    for (int i = 0; i < this.vector.length; i++) {
      RootbeerGpu.setSharedDouble(vectorStartIndex + i * 8, this.vector[i]);
    }
    // }

    // Sync all kernels, until shared memory was established
    RootbeerGpu.syncthreads();

    // debug
    multiplierVal = multiplier;
    vectorVal = new double[this.vector.length];

    // Scalar Multiplication (Vector x Element)
    for (int i = 0; i < this.vector.length; i++) {

      double vectorElement = RootbeerGpu.getSharedDouble(vectorStartIndex + i
          * 8);

      vectorVal[i] = vectorElement;
      results[i] = vectorElement * multiplier;
    }

  }

  public static void main(String[] args) {
    // Dummy constructor invocation
    // to keep kernel constructor in
    // rootbeer transformation
    new MatrixMultiplicationBSPKernel(0, 0, null);
  }
}
