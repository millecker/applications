package at.illecker.hadoop.rootbeer.examples.matrixmultiplication.gpu;

import edu.syr.pcpratts.rootbeer.runtime.Kernel;

public class MatrixMultiplicationReducerKernel implements Kernel {

	private double[][] vectorArrays;
	public double[] result;
	public int row;

	public MatrixMultiplicationReducerKernel(double[][] vectorArrays, int row) {
		this.vectorArrays = vectorArrays;
		this.row = row;
		this.result = new double[vectorArrays[0].length];
	}

	// Vector Accumulator
	public void gpuMethod() {
		for (int i = 0; i < vectorArrays.length; i++) {
			double[] vector = vectorArrays[i];
			for (int j = 0; j < vector.length; j++) {
				this.result[j] += this.result[j] + vector[j];
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