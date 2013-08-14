/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package at.illecker.hama.rootbeer.examples.hellorootbeer;

import java.util.List;

import edu.syr.pcpratts.rootbeer.runtime.Kernel;
import edu.syr.pcpratts.rootbeer.runtime.Rootbeer;
import edu.syr.pcpratts.rootbeer.runtime.StatsRow;
import edu.syr.pcpratts.rootbeer.runtime.util.Stopwatch;

public class HelloRootbeerKernel implements Kernel {

  private long m_iterations;
  public double result = 0;

  public HelloRootbeerKernel(long iterations) {
    m_iterations = iterations;
  }

  public void gpuMethod() {
    result = m_iterations;
  }

  public static void main(String[] args) {
    // Dummy constructor invocation
    // to keep kernel constructor in
    // rootbeer transformation
    // new HelloRootbeerKernel(0);

    int blockSize = 512; // threads
    int gridSize = 128; // blocks

    if (args.length > 0) {
      gridSize = Integer.parseInt(args[0]);
    }

    HelloRootbeerKernel kernel = new HelloRootbeerKernel(0);
    Rootbeer rootbeer = new Rootbeer();
    rootbeer.setThreadConfig(blockSize, gridSize, blockSize * gridSize);

    // Run GPU Kernels
    Stopwatch watch = new Stopwatch();
    watch.start();
    rootbeer.runAll(kernel);
    watch.stop();

    System.out.println("HelloRootbeerKernel,GPUTime="
        + watch.elapsedTimeMillis() + "ms\n");

    List<StatsRow> stats = rootbeer.getStats();
    for (StatsRow row : stats) {
      System.out.println("  StatsRow:\n");
      System.out.println("    init time: " + row.getInitTime() + "\n");
      System.out.println("    serial time: " + row.getSerializationTime()
          + "\n");
      System.out.println("    exec time: " + row.getExecutionTime() + "\n");
      System.out.println("    deserial time: " + row.getDeserializationTime()
          + "\n");
      System.out.println("    num blocks: " + row.getNumBlocks() + "\n");
      System.out.println("    num threads: " + row.getNumThreads() + "\n");
    }
  }
}
