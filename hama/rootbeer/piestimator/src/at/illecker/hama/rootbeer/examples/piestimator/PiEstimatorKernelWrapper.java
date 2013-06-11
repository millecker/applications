package at.illecker.hama.rootbeer.examples.piestimator;

import java.util.ArrayList;
import java.util.List;

import edu.syr.pcpratts.rootbeer.runtime.Kernel;
import edu.syr.pcpratts.rootbeer.runtime.Rootbeer;
import edu.syr.pcpratts.rootbeer.runtime.StatsRow;
import edu.syr.pcpratts.rootbeer.runtime.util.Stopwatch;

public class PiEstimatorKernelWrapper {
	private int m_kernelCount;
	private long m_iterations;
	private List<Kernel> kernels;

	public PiEstimatorKernelWrapper(int kernelCount, long iterations) {
		m_kernelCount = kernelCount;
		m_iterations = iterations;
		kernels = new ArrayList<Kernel>();
	}

	public void run() {

		Stopwatch watch = new Stopwatch();
		watch.start();

		for (int i = 0; i < m_kernelCount; i++) {
			kernels.add(new PiEstimatorKernel(m_iterations));
		}
		Rootbeer rootbeer = new Rootbeer();
		// rootbeer.setThreadConfig(m_blockSize, m_gridSize);
		rootbeer.runAll(kernels);

		watch.stop();
		System.out.println("gpu time: " + watch.elapsedTimeMillis() + " ms");
		List<StatsRow> stats = rootbeer.getStats();
		for (StatsRow row : stats) {
			System.out.println("  StatsRow:");
			System.out.println("    init time: " + row.getInitTime());
			System.out
					.println("    serial time: " + row.getSerializationTime());
			System.out.println("    exec time: " + row.getExecutionTime());
			System.out.println("    deserial time: "
					+ row.getDeserializationTime());
			System.out.println("    num blocks: " + row.getNumBlocks());
			System.out.println("    num threads: " + row.getNumThreads());
		}
	}

	public List<Kernel> getKernels() {
		return kernels;
	}
}
