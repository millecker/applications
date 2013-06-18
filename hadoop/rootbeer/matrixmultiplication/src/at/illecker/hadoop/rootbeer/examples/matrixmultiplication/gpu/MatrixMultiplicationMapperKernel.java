package at.illecker.hadoop.rootbeer.examples.matrixmultiplication.gpu;

import edu.syr.pcpratts.rootbeer.runtime.Kernel;

public class MatrixMultiplicationMapperKernel implements Kernel {

	private double[] multiplier;
	private int[] multiplierIndex;
	private double[] vector;
	public double[][] results;
	public int[] resultsRow;
	public int resultCount;

	public MatrixMultiplicationMapperKernel(double[] multiplier,
			int[] multiplierIndex, double[] vector) {
		this.multiplier = multiplier;
		this.multiplierIndex = multiplierIndex;
		this.vector = vector;
		this.results = new double[multiplier.length][vector.length];
	}

	public void gpuMethod() {

		for (int i = 0; i < multiplier.length; i++) {

			if (this.multiplier[i] != 0) {

				// Scalar Multiplication (Vector x Element)
				double[] newVector = new double[vector.length];
				for (int j = 0; j < vector.length; j++) {
					newVector[j] = this.vector[i] * this.multiplier[i];
				}

				this.resultsRow[i] = multiplierIndex[i];
				this.results[i] = newVector;
				resultCount++;
			}
		}
	}

	public static void main(String[] args) {
		// Dummy constructor invocation
		// to keep Kernel constructor in
		// rootbeer transformation
		new MatrixMultiplicationMapperKernel(null, null, null);
	}
}
