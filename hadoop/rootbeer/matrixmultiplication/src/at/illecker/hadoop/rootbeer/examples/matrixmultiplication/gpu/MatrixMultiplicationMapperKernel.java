package at.illecker.hadoop.rootbeer.examples.matrixmultiplication.gpu;

import edu.syr.pcpratts.rootbeer.runtime.Kernel;
import edu.syr.pcpratts.rootbeer.runtime.RootbeerGpu;

public class MatrixMultiplicationMapperKernel implements Kernel {

	public boolean isFirstKernel = false;
	public double[] vector;
	public double multiplier;
	public int blockSize;
	public int block_idxx;
	public int thread_idxx;
	public int setShareIndex;
	public double setShareValue;
	public int getShareIndex;
	public double getShareValue;

	public double[] result = null;
	public int row;

	public MatrixMultiplicationMapperKernel(boolean isFirstKernel,
			double[] vector, double multiplier, int row, int blockSize) {
		this.isFirstKernel = isFirstKernel;
		this.vector = vector;
		this.multiplier = multiplier;
		this.row = row;
		this.result = new double[vector.length];
	}

	public void gpuMethod() {

		// blockIndex is always the same, one block consisting all kernels
		block_idxx = RootbeerGpu.getBlockIdxx();
		thread_idxx = RootbeerGpu.getThreadIdxx();
		// idx = block_idxx * blockSize + thread_idxx;

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
			setShareIndex = i * blockSize + thread_idxx;
			setShareValue = this.vector[i] * this.multiplier;
			RootbeerGpu.setSharedDouble(setShareIndex, setShareValue);
		}

		// Sync all kernels, scalar multiplication has finished
		RootbeerGpu.synchthreads();

		// Masterkernels accumilates all vectors to result
		if (isFirstKernel) {
			for (int i = 0; i < blockSize; i++) {
				for (int j = 0; j < vector.length; i++) {
					getShareIndex = j * blockSize + i;
					getShareValue = RootbeerGpu.getSharedDouble(getShareIndex);
					result[i] += getShareIndex;
				}
			}
		}

	}

	public static void main(String[] args) {
		// Dummy constructor invocation
		// to keep Kernel constructor in
		// rootbeer transformation
		new MatrixMultiplicationMapperKernel(true, null, 0, 0, 0);
	}
}