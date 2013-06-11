package at.illecker.hama.rootbeer.examples.piestimator;

import edu.syr.pcpratts.rootbeer.runtime.Kernel;

public class PiEstimatorKernel implements Kernel {

	private long m_iterations;
	private double result = 0;

	public PiEstimatorKernel(long iterations) {
		m_iterations = iterations;
	}

	public void gpuMethod() {
		int in = 0;
		for (int i = 0; i < m_iterations; i++) {
			double x = 2.0 * Math.random() - 1.0, y = 2.0 * Math.random() - 1.0;
			if ((Math.sqrt(x * x + y * y) < 1.0)) {
				in++;
			}
		}
		result = 4.0 * in / m_iterations;
	}

	public double getResult() {
		return result;
	}
}
