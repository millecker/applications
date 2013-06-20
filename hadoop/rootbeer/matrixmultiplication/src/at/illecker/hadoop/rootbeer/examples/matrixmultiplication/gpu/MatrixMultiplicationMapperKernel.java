package at.illecker.hadoop.rootbeer.examples.matrixmultiplication.gpu;

import edu.syr.pcpratts.rootbeer.runtime.Kernel;
import edu.syr.pcpratts.rootbeer.runtime.RootbeerGpu;

public class MatrixMultiplicationMapperKernel implements Kernel {

	private boolean isFirstKernel = false;
	private double[] vector;
	private double multiplier;
	private int blockSize;
	
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
		int block_idxx = RootbeerGpu.getBlockIdxx();
		int thread_idxx = RootbeerGpu.getThreadIdxx();
		int idx = block_idxx * blockSize + thread_idxx;

		System.out.println("blockSize: " + blockSize);
		System.out.println("block_idxx: " + block_idxx);
		System.out.println("thread_idxx: " + thread_idxx);
		System.out.println("idx: " + idx);

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
			RootbeerGpu.setSharedDouble(i * blockSize + thread_idxx,
					this.vector[i] * this.multiplier);
			System.out.println("Index: i * blockSize + thread_idxx: " + i
					* blockSize + thread_idxx);
			System.out.println("Value: this.vector[i] * this.multiplier: "
					+ this.vector[i] * this.multiplier);
		}

		// Sync all kernels, scalar multiplication has finished
		RootbeerGpu.synchthreads();

		// Masterkernels accumilates all vectors to result
		if (isFirstKernel) {
			System.out.println("isFirstKernel: " + thread_idxx);
			for (int i = 0; i < blockSize; i++) {
				for (int j = 0; j < vector.length; i++) {

					System.out.println("Index: j*blockSize+i: " + j * blockSize
							+ i);
					System.out.println("Value: "
							+ RootbeerGpu.getSharedDouble(j * blockSize + i));

					result[i] += RootbeerGpu.getSharedDouble(j * blockSize + i);
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