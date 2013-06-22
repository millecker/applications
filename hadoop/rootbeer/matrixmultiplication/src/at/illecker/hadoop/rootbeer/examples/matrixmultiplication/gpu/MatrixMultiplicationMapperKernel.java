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
	public double multiplier;
	public int block_idxx;
	public int thread_idxx;
	public int blockSize;
	public int gridSize;
	public int globalThreadIndex;
	public double[] result;
	public int row;

	// debug values
	public int[] setShareIndex;
	public double[] setShareValue;
	public int[] getShareIndex;
	public double[] getShareValue;

	public MatrixMultiplicationMapperKernel(double[] vector, double multiplier,
			int row) {
		this.vector = vector;
		this.multiplier = multiplier;
		this.row = row;
		result = null;
	}

	public void gpuMethod() {

		blockSize = RootbeerGpu.getBlockDimx();
		gridSize = RootbeerGpu.getGridDimx();

		block_idxx = RootbeerGpu.getBlockIdxx();
		thread_idxx = RootbeerGpu.getThreadIdxx();

		globalThreadIndex = block_idxx * blockSize + thread_idxx;

		// Shared memory is shared between threads in a block
		// multiplied consists all multiplications within a blocks
		int blockElements = blockSize * vector.length;
		double[] sharedBlockResults = new double[blockElements];

		// debug variables
		setShareIndex = new int[vector.length];
		setShareValue = new double[blockElements];

		// Every kernels within a block does a scalar Multiplication 
		// (Vector x Element)
		for (int i = 0; i < vector.length; i++) {

			int index = (vector.length * globalThreadIndex + i) % blockElements;

			sharedBlockResults[index] = this.vector[i] * this.multiplier;

			RootbeerGpu.setSharedDouble(index, sharedBlockResults[index]);

			// debug values
			setShareIndex[i] = index;
			setShareValue[index] = sharedBlockResults[index];
		}

		// Sync all kernels, wait for scalar multiplication
		RootbeerGpu.syncthreads();

		// First kernel of each block accumulates vectors within the block
		if (thread_idxx == 0) {

			this.result = new double[vector.length];
			for (int i = 0; i < vector.length; i++) {
				result[i] = 0;
			}

			getShareIndex = new int[blockElements];
			getShareValue = new double[blockElements];

			for (int i = 0; i < blockElements; i++) {

				int index = (block_idxx * blockElements + i) % blockElements;

				sharedBlockResults[index] = RootbeerGpu.getSharedDouble(index);

				// debug values
				getShareIndex[i] = index;
				getShareValue[index] = sharedBlockResults[index];

				result[i % vector.length] += sharedBlockResults[index];
			}
		}
	}

	public static void main(String[] args) {
		// Dummy constructor invocation
		// to keep Kernel constructor in
		// rootbeer transformation
		new MatrixMultiplicationMapperKernel(null, 0, 0);
	}
}