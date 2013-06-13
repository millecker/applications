package at.illecker.hama.rootbeer.examples.piestimator;

public class PiEstimatorCpuThread implements Runnable {
	private Thread m_thread;
	private long m_iterations;
	public double result;

	public PiEstimatorCpuThread(long iterations) {
		m_iterations = iterations;

		m_thread = new Thread(this);
		m_thread.setDaemon(true);
		m_thread.start();
	}

	@Override
	public void run() {
		long in = 0;
		for (long i = 0; i < m_iterations; i++) {
			double x = 2.0 * Math.random() - 1.0, y = 2.0 * Math.random() - 1.0;
			if ((Math.sqrt(x * x + y * y) < 1.0)) {
				in++;
			}
		}
		result = 4.0 * in / m_iterations;
	}

	public void join() {
		try {
			m_thread.join();
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}
	
}
