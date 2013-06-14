package at.illecker.hadoop.rootbeer.examples.matrixmultiplication;

import edu.syr.pcpratts.rootbeer.runtime.Kernel;

public class MatrixMultiplicationKernel implements Kernel {

	private long m_iterations;
	public double result = 0;

	public MatrixMultiplicationKernel(long iterations) {
		m_iterations = iterations;
	}

	public void gpuMethod() {

	}

	public static void main(String[] args) {
		// Dummy constructor invocation
		// to keep Kernel constructor in
		// rootbeer transformation
		new MatrixMultiplicationKernel(1);
	}
}
