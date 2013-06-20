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

	public int setShareIndex;
	public double setShareValue;

	public int getShareIndex;
	public double getShareValue;

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

		// Every kernels does a scalar Multiplication (Vector x Element)
		for (int i = 0; i < vector.length; i++) {
			setShareIndex = vector.length * index + i;
			setShareValue = this.vector[i] * this.multiplier;
			RootbeerGpu.setSharedDouble(setShareIndex, setShareValue);
		}

		// Sync all kernels, scalar multiplication has finished
		RootbeerGpu.syncthreads();

		// First kernel of each block accumulates vectors within the block
		if (thread_idxx == 0) {

			this.result = new double[vector.length];
			for (int i = 0; i < vector.length; i++) {
				result[i] = 0;
			}

			for (int i = 0; i < blockSize * vector.length; i++) {
				getShareIndex = block_idxx * blockSize * vector.length + i;
				getShareValue = RootbeerGpu.getSharedDouble(getShareIndex);
				result[i % vector.length] += getShareValue;
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