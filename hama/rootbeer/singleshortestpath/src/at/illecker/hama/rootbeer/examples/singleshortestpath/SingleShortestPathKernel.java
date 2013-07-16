package at.illecker.hama.rootbeer.examples.singleshortestpath;

import edu.syr.pcpratts.rootbeer.runtime.Kernel;

public class SingleShortestPathKernel implements Kernel {

  private long m_iterations;
  public double result = 0;

  public SingleShortestPathKernel(long iterations) {
    m_iterations = iterations;
  }

  public void gpuMethod() {
    long in = 0;
    for (long i = 0; i < m_iterations; i++) {
      double x = 2.0 * Math.random() - 1.0, y = 2.0 * Math.random() - 1.0;
      if ((Math.sqrt(x * x + y * y) < 1.0)) {
        in++;
      }
    }
    result = 4.0 * in / m_iterations;
  }

  public static void main(String[] args) {
    // Dummy constructor invocation
    // to keep Kernel constructor in
    // rootbeer transformation
    new SingleShortestPathKernel(1);
  }
}
