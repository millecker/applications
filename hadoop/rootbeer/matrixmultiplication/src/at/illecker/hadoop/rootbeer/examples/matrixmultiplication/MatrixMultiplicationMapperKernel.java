package at.illecker.hadoop.rootbeer.examples.matrixmultiplication;

import edu.syr.pcpratts.rootbeer.runtime.Kernel;

public class MatrixMultiplicationMapperKernel implements Kernel {

	public int row;
	private double[] vector;
	private double multiplier;
	public double[] result;

	public MatrixMultiplicationMapperKernel(int row, double[] vector,
			double multiplier) {
		this.row = row;
		this.vector = vector;
		this.multiplier = multiplier;
		this.result = new double[vector.length];
	}

	public void gpuMethod() {
		for (int i = 0; i < vector.length; i++) {
			this.result[i] = this.vector[i] * this.multiplier;
		}
	}

	public static void main(String[] args) {
		// Dummy constructor invocation
		// to keep Kernel constructor in
		// rootbeer transformation
		new MatrixMultiplicationMapperKernel(0, new double[] { 0 }, 0);
	}
}
