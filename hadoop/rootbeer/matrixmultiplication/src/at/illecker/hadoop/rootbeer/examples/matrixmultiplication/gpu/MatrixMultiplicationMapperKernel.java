package at.illecker.hadoop.rootbeer.examples.matrixmultiplication.gpu;

import edu.syr.pcpratts.rootbeer.runtime.Kernel;

public class MatrixMultiplicationMapperKernel implements Kernel {

	private Multiplier[] multiplier;
	private double[] vector;
	public Result[] results;

	public MatrixMultiplicationMapperKernel(Multiplier[] multiplier,
			double[] vector) {
		this.multiplier = multiplier;
		this.vector = vector;
		this.results = new Result[multiplier.length];
	}

	public void gpuMethod() {

		for (int i = 0; i < multiplier.length; i++) {

			// Scalar Multiplication (Vector x Element)
			double[] newVector = new double[vector.length];
			for (int j = 0; j < vector.length; j++) {
				newVector[j] = this.vector[i] * this.multiplier[i].value;
			}

			this.results[i] = new Result(multiplier[i].index, newVector);
		}
	}

	public static void main(String[] args) {
		// Dummy constructor invocation
		// to keep Kernel constructor in
		// rootbeer transformation
		// new MatrixMultiplicationMapperKernel(0, new double[] { 0 }, 0);
	}
}
