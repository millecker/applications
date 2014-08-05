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
package at.illecker.hama.hybrid.examples.piestimator;

import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.hama.HamaConfiguration;
import org.apache.hama.bsp.BSPJob;

import com.google.caliper.Benchmark;
import com.google.caliper.Param;
import com.google.caliper.api.Macrobenchmark;
import com.google.caliper.runner.CaliperMain;

public class PiEstimatorHybridBenchmark extends Benchmark {

  @Param({ "500000" })
  private long n;

  // maximal 8 cpu tasks and 1 gpu task
  // @Param({ "1", "2", "3", "4", "5", "6", "7", "8", "9" })
  private int bspTaskNum = 9;

  private final int maxBspTaskNum = 9;

  // GPU percentage of the input data
  @Param({ "12", "50", "70", "90", "95", "99" })
  private int GPUPercentage;

  private static final String OUTPUT_DIR = "output/hama/rootbeer/examples/piestimator/bench";

  private Path m_OUTPUT_DIR_PATH;
  private Configuration m_conf = null;
  private boolean m_runLocally = false;

  private int m_blockSize;
  private int m_gridSize;
  private long m_totalIterations;

  @Override
  protected void setUp() throws Exception {
    m_conf = new Configuration();

    // Try to load Hadoop configuration
    String HADOOP_HOME = System.getenv("HADOOP_HOME");
    String HADOOP_INSTALL = System.getenv("HADOOP_INSTALL");
    if ((HADOOP_HOME != null) || (HADOOP_INSTALL != null) && (!m_runLocally)) {
      String HADOOP = ((HADOOP_HOME != null) ? HADOOP_HOME : HADOOP_INSTALL);

      m_conf.addResource(new Path(HADOOP, "src/core/core-default.xml"));
      m_conf.addResource(new Path(HADOOP, "src/hdfs/hdfs-default.xml"));
      m_conf.addResource(new Path(HADOOP, "src/mapred/mapred-default.xml"));
      m_conf.addResource(new Path(HADOOP, "conf/core-site.xml"));
      m_conf.addResource(new Path(HADOOP, "conf/hdfs-site.xml"));
      m_conf.addResource(new Path(HADOOP, "conf/mapred-site.xml"));
      // System.out.println("Loaded Hadoop configuration from " + HADOOP);

      try {
        // Connect to HDFS Filesystem
        FileSystem.get(m_conf);
      } catch (Exception e) {
        // HDFS not reachable run Benchmark locally
        m_conf = new Configuration();
        m_runLocally = true;
      }
      // System.out.println("Run Benchmark local: " + m_runLocally);
    }

    // Try to load Hama configuration
    String HAMA_HOME = System.getenv("HAMA_HOME");
    String HAMA_INSTALL = System.getenv("HAMA_INSTALL");
    if ((HAMA_HOME != null) || (HAMA_INSTALL != null) && (!m_runLocally)) {
      String HAMA = ((HAMA_HOME != null) ? HAMA_HOME : HAMA_INSTALL);

      m_conf.addResource(new Path(HAMA, "conf/hama-default.xml"));
      m_conf.addResource(new Path(HAMA, "conf/hama-site.xml"));
      // System.out.println("Loaded Hama configuration from " + HAMA);
    }

    // Setup outputs
    m_OUTPUT_DIR_PATH = new Path(OUTPUT_DIR + "/bench_"
        + System.currentTimeMillis());
    System.out.println("OUTPUT_DIR_PATH: " + m_OUTPUT_DIR_PATH);

    m_blockSize = PiEstimatorHybridBSP.blockSize;
    m_gridSize = PiEstimatorHybridBSP.gridSize;
    m_totalIterations = (long) m_blockSize * (long) m_gridSize * n;

    System.out.println("Benchmark PiEstimatorHybridBSP[blockSize="
        + m_blockSize + ",gridSize=" + m_gridSize + "] n=" + n + "bspTaskNum="
        + bspTaskNum + ", totalSamples=" + m_totalIterations);
  }

  @Override
  protected void tearDown() throws Exception {

    printOutput(m_conf, m_OUTPUT_DIR_PATH);

    // Cleanup
    FileSystem fs = FileSystem.get(m_conf);
    fs.delete(m_OUTPUT_DIR_PATH, true);
  }

  static void printOutput(Configuration conf, Path path) throws IOException {
    System.out.println("printOutput: " + path);
    FileSystem fs = FileSystem.get(conf);
    FileStatus[] files = fs.listStatus(path);
    if (files != null) {
      for (int i = 0; i < files.length; i++) {
        if (files[i].getLen() > 0) {
          System.out.println("File " + files[i].getPath());
          FSDataInputStream in = fs.open(files[i].getPath());
          IOUtils.copyBytes(in, System.out, conf, false);
          in.close();
        }
      }
    } else {
      System.out.println("No directory available: " + path);
    }
    // fs.delete(FileOutputFormat.getOutputPath(job), true);
  }

  // Microbenchmark
  // Uncomment Macro to use Micro
  public void timeCalculate(int reps) {
    int sum = 0;
    for (int rep = 0; rep < reps; rep++) {
      sum = doBenchmark(sum);
    }
    System.out.println(sum);
  }

  @Macrobenchmark
  public void timeCalculate() {
    doBenchmark(0);
  }

  public int doBenchmark(int sum) {
    return piEstimatorHybrid(sum);
  }

  private class PiEstimatorHybrid extends Configured implements Tool {

    public PiEstimatorHybrid() {
    }

    @Override
    public int run(String[] arg0) throws Exception {

      BSPJob job = PiEstimatorHybridBSP.createPiEstimatorHybridConf(
          new HamaConfiguration(m_conf), m_OUTPUT_DIR_PATH);

      job.set(PiEstimatorHybridBSP.CONF_BLOCKSIZE, "" + m_blockSize);
      job.set(PiEstimatorHybridBSP.CONF_GRIDSIZE, "" + m_gridSize);
      job.set(PiEstimatorHybridBSP.CONF_ITERATIONS, "" + m_totalIterations);
      job.set(PiEstimatorHybridBSP.CONF_GPU_PERCENTAGE, "" + GPUPercentage);
      job.setBoolean(PiEstimatorHybridBSP.CONF_DEBUG, false);

      job.setNumBspTask(bspTaskNum);
      if (bspTaskNum == maxBspTaskNum) {
        job.setNumBspGpuTask(1);
      } else {
        job.setNumBspGpuTask(0);
      }

      return (job.waitForCompletion(true) ? 1 : 0);
    }
  }

  private int piEstimatorHybrid(int sum) {
    try {
      sum += ToolRunner.run(new PiEstimatorHybrid(), null);
    } catch (Exception e) {
      e.printStackTrace();
    }
    return sum;
  }

  public static void main(String[] args) {
    CaliperMain.main(PiEstimatorHybridBenchmark.class, args);
  }

}
