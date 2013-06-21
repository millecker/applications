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
	public int index;

	public int[] setShareIndex;
	public double[] setShareValue;

	public int[] getShareIndex;
	public double[] getShareValue;

	public double[] result;
	public int row;

	public MatrixMultiplicationMapperKernel(double[] vector, double multiplier,
			int row) {
		this.vector = vector;
		this.multiplier = multiplier;
		this.row = row;
		result = null;
	}

	public void gpuMethod() {

		// blockIndex is always the same, one block consisting all kernels
		blockSize = RootbeerGpu.getBlockDimx();

		block_idxx = RootbeerGpu.getBlockIdxx();
		thread_idxx = RootbeerGpu.getThreadIdxx();

		index = block_idxx * blockSize + thread_idxx;

		// Set row information
		// RootbeerGpu.setSharedInteger(index, row);

		setShareIndex = new int[vector.length];
		setShareValue = new double[vector.length];
		// Every kernels does a scalar Multiplication (Vector x Element)
		for (int i = 0; i < vector.length; i++) {
			setShareIndex[i] = vector.length * index + i;
			setShareValue[i] = this.vector[i] * this.multiplier;
			RootbeerGpu.setSharedDouble(setShareIndex[i], setShareValue[i]);
		}

		// Sync all kernels, scalar multiplication has finished
		RootbeerGpu.syncthreads();

		// First kernel of each block accumulates vectors within the block
		if (thread_idxx == 0) {

			this.result = new double[vector.length];
			for (int i = 0; i < vector.length; i++) {
				result[i] = 0;
			}

			getShareIndex = new int[blockSize * vector.length];
			getShareValue = new double[blockSize * vector.length];
			for (int i = 0; i < blockSize * vector.length; i++) {
				getShareIndex[i] = block_idxx * blockSize * vector.length + i;
				getShareValue[i] = RootbeerGpu
						.getSharedDouble(getShareIndex[i]);
				result[i % vector.length] += getShareValue[i];
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