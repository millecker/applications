package at.illecker.hadoop.rootbeer.examples.matrixmultiplication.gpu;

import edu.syr.pcpratts.rootbeer.runtime.Kernel;
import edu.syr.pcpratts.rootbeer.runtime.RootbeerGpu;

public class MatrixMultiplicationMapperKernel implements Kernel {

	public boolean isFirstKernel = false;
	public double[] vector;
	public double multiplier;
	
	public int block_idxx;
	public int thread_idxx;
	public int blockSize;
	public int index;
	
	public int setShareIndex;
	public double setShareValue;
	public int getShareIndex;
	public double getShareValue;

	public double[] result;
	public int row;

	public MatrixMultiplicationMapperKernel(boolean isFirstKernel,
			double[] vector, double multiplier, int row) {
		this.isFirstKernel = isFirstKernel;
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

		// Masterkernels copies data to share memory
		// if (isFirstKernel) {
		// for (int i = 0; i < vector.length; i++) {
		// RootbeerGpu.setSharedDouble(i*block_idxx, vector[i]);
		// }
		// }

		// Sync all kernels, data is in shared memory
		// RootbeerGpu.synchthreads();

		// Every kernels does a scalar Multiplication (Vector x Element)
		for (int i = 0; i < vector.length; i++) {
			setShareIndex = i * thread_idxx;
			setShareValue = this.vector[i] * this.multiplier;
			RootbeerGpu.setSharedDouble(setShareIndex, setShareValue);
		}

		// Sync all kernels, scalar multiplication has finished
		RootbeerGpu.syncthreads();

		// Masterkernels accumilates all vectors to result
		/*
		if (isFirstKernel) {
			this.result = new double[vector.length];
			for (int i = 0; i < RootbeerGpu.getBlockDimx(); i++) {
				for (int j = 0; j < vector.length; i++) {
					getShareIndex = j * i;
					getShareValue = RootbeerGpu.getSharedDouble(getShareIndex);
					result[i] += getShareValue;
				}
			}
		}
		*/

	}

	public static void main(String[] args) {
		// Dummy constructor invocation
		// to keep Kernel constructor in
		// rootbeer transformation
		new MatrixMultiplicationMapperKernel(true, null, 0, 0);
	}
}