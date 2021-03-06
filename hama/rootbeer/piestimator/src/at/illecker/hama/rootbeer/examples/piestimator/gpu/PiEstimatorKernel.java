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

import org.trifort.rootbeer.runtime.Context;
import org.trifort.rootbeer.runtime.Kernel;
import org.trifort.rootbeer.runtime.Rootbeer;
import org.trifort.rootbeer.runtime.RootbeerGpu;
import org.trifort.rootbeer.runtime.StatsRow;
import org.trifort.rootbeer.runtime.ThreadConfig;
import org.trifort.rootbeer.runtime.util.Stopwatch;

public class PiEstimatorKernel implements Kernel {

  private long iterations; // input
  private long seed; // input
  public ResultList resultList; // output

  public PiEstimatorKernel(long iterations, long seed) {
    this.iterations = iterations;
    this.seed = seed;
    this.resultList = new ResultList();
  }

  public void gpuMethod() {
    int thread_idxx = RootbeerGpu.getThreadIdxx();
    int globalThreadId = RootbeerGpu.getThreadIdxx()
        + RootbeerGpu.getBlockIdxx() * RootbeerGpu.getBlockDimx();
    int reductionStart = roundUpToNextPowerOfTwo(divup(
        RootbeerGpu.getBlockDimx(), 2));

    LinearCongruentialRandomGenerator lcg = new LinearCongruentialRandomGenerator(
        seed / globalThreadId);

    long hits = 0;
    for (int i = 0; i < iterations; i++) {
      double x = 2.0 * lcg.nextDouble() - 1.0; // value between -1 and 1
      double y = 2.0 * lcg.nextDouble() - 1.0; // value between -1 and 1
      if ((x * x + y * y) <= 1.0) {
        hits++;
      }
    }

    // write to shared memory
    RootbeerGpu.setSharedLong(thread_idxx * 8, hits);
    RootbeerGpu.syncthreads();

    // do reduction in shared memory
    // 1-bit right shift = divide by two to the power 1
    for (int s = reductionStart; s > 0; s >>= 1) {
      if (thread_idxx < s) {
        // sh_mem[tid] += sh_mem[tid + s];
        RootbeerGpu.setSharedLong(
            thread_idxx * 8,
            RootbeerGpu.getSharedLong(thread_idxx * 8)
                + RootbeerGpu.getSharedLong((thread_idxx + s) * 8));
      }
      // Sync all threads within a block
      RootbeerGpu.syncthreads();
    }

    if (thread_idxx == 0) {
      Result result = new Result();
      result.hits = RootbeerGpu.getSharedLong(thread_idxx * 8);
      resultList.add(result);
    }

    RootbeerGpu.syncblocks(1);
  }

  private int divup(int x, int y) {
    if (x % y != 0) {
      return ((x + y - 1) / y); // round up
    } else {
      return x / y;
    }
  }

  private int roundUpToNextPowerOfTwo(int x) {
    x--;
    x |= x >> 1; // handle 2 bit numbers
    x |= x >> 2; // handle 4 bit numbers
    x |= x >> 4; // handle 8 bit numbers
    x |= x >> 8; // handle 16 bit numbers
    x |= x >> 16; // handle 32 bit numbers
    x++;
    return x;
  }

  public static void main(String[] args) {
    long calculationsPerThread = 100000;
    int blockSize = 1024; // threads
    int gridSize = 14; // blocks

    if (args.length > 0) {
      calculationsPerThread = Integer.parseInt(args[0]);
      blockSize = Integer.parseInt(args[1]);
      gridSize = Integer.parseInt(args[2]);
    }

    PiEstimatorKernel kernel = new PiEstimatorKernel(calculationsPerThread,
        System.currentTimeMillis());
    Rootbeer rootbeer = new Rootbeer();

    // Run GPU Kernels
    Context context = rootbeer.createDefaultContext();
    Stopwatch watch = new Stopwatch();
    watch.start();
    rootbeer.run(kernel, new ThreadConfig(blockSize, gridSize, blockSize
        * gridSize), context);
    watch.stop();

    System.out.println("PiEstimatorKernel,GPUTime=" + watch.elapsedTimeMillis()
        + "ms");
    System.out.println("PiEstimatorKernel,Samples=" + calculationsPerThread
        * blockSize * gridSize);

    List<StatsRow> stats = context.getStats();
    for (StatsRow row : stats) {
      System.out.println("  StatsRow:\n");
      System.out.println("    serial time: " + row.getSerializationTime()
          + "\n");
      System.out.println("    exec time: " + row.getExecutionTime() + "\n");
      System.out.println("    deserial time: " + row.getDeserializationTime()
          + "\n");
      System.out.println("    num blocks: " + row.getNumBlocks() + "\n");
      System.out.println("    num threads: " + row.getNumThreads() + "\n");
    }

    // Get GPU results
    long totalHits = 0;
    long resultCounter = 0;
    Result[] resultList = kernel.resultList.getList();
    for (Result result : resultList) {
      if (result == null) { // break at end of list
        break;
      }
      totalHits += result.hits;
      resultCounter++;
    }

    double result = 4.0 * totalHits
        / (calculationsPerThread * blockSize * gridSize);

    System.out.println("Pi: " + result);
    System.out.println("totalHits: " + totalHits);
    System.out.println("calculationsPerThread: " + calculationsPerThread);
    System.out.println("results: " + resultCounter);
    System.out.println("calculationsTotal: " + calculationsPerThread
        * blockSize * gridSize);
  }
}
