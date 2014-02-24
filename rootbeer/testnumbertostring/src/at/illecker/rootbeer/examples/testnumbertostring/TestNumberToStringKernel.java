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
package at.illecker.rootbeer.examples.testnumbertostring;

import java.util.List;

import org.trifort.rootbeer.runtime.Context;
import org.trifort.rootbeer.runtime.Kernel;
import org.trifort.rootbeer.runtime.Rootbeer;
import org.trifort.rootbeer.runtime.RootbeerGpu;
import org.trifort.rootbeer.runtime.StatsRow;
import org.trifort.rootbeer.runtime.ThreadConfig;
import org.trifort.rootbeer.runtime.util.Stopwatch;

public class TestNumberToStringKernel implements Kernel {
  // gridSize = amount of blocks and multiprocessors
  public static final int GRID_SIZE = 1; // 14;
  // blockSize = amount of threads
  public static final int BLOCK_SIZE = 1; // 1024;

  @Override
  public void gpuMethod() {
    if (RootbeerGpu.getThreadIdxx() == 0) {
      System.out.println("Integer.MIN_VALUE:");
      System.out.println(Integer.MIN_VALUE);
      System.out.println(Integer.toString(Integer.MIN_VALUE));
      System.out.println("Integer.MAX_VALUE:");
      System.out.println(Integer.MAX_VALUE);
      System.out.println(Integer.toString(Integer.MAX_VALUE));

      System.out.println("Long.MIN_VALUE:");
      System.out.println(Long.MIN_VALUE);
      System.out.println(Long.toString(Long.MIN_VALUE));
      System.out.println("Long.MAX_VALUE:");
      System.out.println(Long.MAX_VALUE);
      System.out.println(Long.toString(Long.MAX_VALUE));

      System.out.println("Float.MIN_VALUE:");
      System.out.println(Float.MIN_VALUE);
      System.out.println(Float.toString(Float.MIN_VALUE));
      System.out.println("Float.MAX_VALUE:");
      System.out.println(Float.MAX_VALUE);
      System.out.println(Float.toString(Float.MAX_VALUE));

      System.out.println("Double.MIN_VALUE:");
      System.out.println(Double.MIN_VALUE);
      System.out.println(Double.toString(Double.MIN_VALUE));
      System.out.println("Double.MAX_VALUE:");
      System.out.println(Double.MAX_VALUE);
      System.out.println(Double.toString(Double.MAX_VALUE));
    }
  }

  public static void main(String[] args) {

    int blockSize = BLOCK_SIZE;
    int gridSize = GRID_SIZE;

    // parse arguments
    if (args.length > 0) {

      if (args.length == 2) {
        blockSize = Integer.parseInt(args[0]);
        gridSize = Integer.parseInt(args[1]);
      } else {
        System.out.println("Wrong argument size!");
        System.out.println("    Argument1=blockSize");
        System.out.println("    Argument2=gridSize");
        return;
      }
    }

    System.out.println("blockSize: " + blockSize);
    System.out.println("gridSize: " + gridSize);

    Rootbeer rootbeer = new Rootbeer();
    TestNumberToStringKernel kernel = new TestNumberToStringKernel();

    // Run GPU Kernels
    Context context = rootbeer.createDefaultContext();
    Stopwatch watch = new Stopwatch();
    watch.start();
    rootbeer.run(kernel, new ThreadConfig(blockSize, gridSize, blockSize
        * gridSize), context);
    watch.stop();

    // Logging
    List<StatsRow> stats = context.getStats();
    for (StatsRow row : stats) {
      System.out.println("  StatsRow:");
      System.out.println("    serial time: " + row.getSerializationTime());
      System.out.println("    exec time: " + row.getExecutionTime());
      System.out.println("    deserial time: " + row.getDeserializationTime());
      System.out.println("    num blocks: " + row.getNumBlocks());
      System.out.println("    num threads: " + row.getNumThreads());
      System.out.println("GPUTime: " + watch.elapsedTimeMillis() + " ms");
    }
  }
}
