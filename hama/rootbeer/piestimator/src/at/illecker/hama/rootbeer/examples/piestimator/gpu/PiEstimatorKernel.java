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
package at.illecker.hama.rootbeer.examples.piestimator.gpu;

import java.util.List;

import edu.syr.pcpratts.rootbeer.runtime.Kernel;
import edu.syr.pcpratts.rootbeer.runtime.Rootbeer;
import edu.syr.pcpratts.rootbeer.runtime.RootbeerGpu;
import edu.syr.pcpratts.rootbeer.runtime.StatsRow;
import edu.syr.pcpratts.rootbeer.runtime.util.Stopwatch;

public class PiEstimatorKernel implements Kernel {

  private long iterations;
  private long seed;
  public ResultList resultList;

  public PiEstimatorKernel(long iterations, long seed) {
    this.iterations = iterations;
    this.seed = seed;
    this.resultList = new ResultList();
  }

  public void gpuMethod() {

    int threadId = RootbeerGpu.getThreadIdxx() + RootbeerGpu.getBlockIdxx()
        * RootbeerGpu.getBlockDimx();

    LinearCongruentialRandomGenerator lcg = new LinearCongruentialRandomGenerator(
        seed / threadId);

    for (int i = 0; i < iterations; i++) {
      double x = 2.0 * lcg.nextDouble() - 1.0; // value between -1 and 1
      double y = 2.0 * lcg.nextDouble() - 1.0; // value between -1 and 1

      Result result = new Result();
      // result.x = x;
      // result.y = y;
      // result.threadId = threadId;
      if ((Math.sqrt(x * x + y * y) < 1.0)) {
        result.hit = 1;
      } else {
        result.hit = 0;
      }
      resultList.add(result);
    }
  }

  public static void main(String[] args) {

    int calculationsPerThread = 1;
    int blockSize = 512; // threads
    int gridSize = 128; // blocks

    if (args.length > 0) {
      gridSize = Integer.parseInt(args[0]);
    }

    PiEstimatorKernel kernel = new PiEstimatorKernel(calculationsPerThread,
        System.currentTimeMillis());
    Rootbeer rootbeer = new Rootbeer();
    rootbeer.setThreadConfig(blockSize, gridSize, blockSize * gridSize);

    // Run GPU Kernels
    Stopwatch watch = new Stopwatch();
    watch.start();
    rootbeer.runAll(kernel);
    watch.stop();

    System.out.println("PiEstimatorKernel,GPUTime=" + watch.elapsedTimeMillis()
        + "ms\n");
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

    // Get GPU results
    long hits = 0;
    long count = 0;
    List<Result> resultList = kernel.resultList.getList();
    for (Result result : resultList) {
      // System.out.println("Result[" + count + "]: threadId=" + result.threadId
      // + " result.hit=" + result.hit + " x=" + result.x + " y=" + result.y);
      hits += result.hit;
      count++;
    }
    double result = 4.0 * hits / count;

    System.out.println("Pi: " + result);
  }
}
