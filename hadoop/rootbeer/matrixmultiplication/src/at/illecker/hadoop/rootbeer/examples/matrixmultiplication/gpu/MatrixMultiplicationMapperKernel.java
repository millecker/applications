package at.illecker.hadoop.rootbeer.examples.matrixmultiplication.gpu;

import edu.syr.pcpratts.rootbeer.runtime.Kernel;

public class MatrixMultiplicationMapperKernel implements Kernel {

	private double[] vector;
	private double multiplier;
	public double[] result;
	public int row;

	public MatrixMultiplicationMapperKernel(double[] vector, double multiplier,
			int row) {
		this.vector = vector;
		this.multiplier = multiplier;
		this.row = row;
		this.result = new double[vector.length];
	}

	// Scalar Multiplication (Vector x Element)
	public void gpuMethod() {
		for (int i = 0; i < vector.length; i++) {
			this.result[i] = this.vector[i] * this.multiplier;
		}
	}

	public static void main(String[] args) {
		// Dummy constructor invocation
		// to keep Kernel constructor in
		// rootbeer transformation
		new MatrixMultiplicationMapperKernel(null, 0, 0);
	}
}