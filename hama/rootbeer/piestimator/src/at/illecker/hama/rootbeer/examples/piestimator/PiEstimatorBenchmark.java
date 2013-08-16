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
package at.illecker.hama.rootbeer.examples.piestimator;

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

import at.illecker.hama.rootbeer.examples.piestimator.cpu.PiEstimatorCpuBSP;
import at.illecker.hama.rootbeer.examples.piestimator.gpu.PiEstimatorGpuBSP;

import com.google.caliper.Benchmark;
import com.google.caliper.Param;
import com.google.caliper.api.Macrobenchmark;
import com.google.caliper.runner.CaliperMain;

public class PiEstimatorBenchmark extends Benchmark {

  @Param({ "10000", "25000", "50000", "60000", "70000", "80000", "90000",
      "100000", "120000", "140000", "160000", "180000", "200000", "220000",
      "240000", "260000" })
  private long n;

  @Param
  CalcType type;

  public enum CalcType {
    CPU, GPU
  };

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

    m_blockSize = PiEstimatorGpuBSP.blockSize;
    m_gridSize = PiEstimatorGpuBSP.gridSize;
    m_totalIterations = (long) m_blockSize * (long) m_gridSize * n;

    System.out.println("Benchmark PiEstimator[blockSize=" + m_blockSize
        + ",gridSize=" + m_gridSize + "] n=" + n + ", totalSamples="
        + m_totalIterations);
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
    switch (type) {
      case CPU:
        sum = piEstimatorHamaCPU(sum);
        break;
      case GPU:
        sum = piEstimatorHamaGPU(sum);
        break;
      default:
        break;
    }
    return sum;
  }

  private class PiEstimator extends Configured implements Tool {
    private boolean useGPU;

    public PiEstimator(boolean useGPU) {
      this.useGPU = useGPU;
    }

    @Override
    public int run(String[] arg0) throws Exception {

      BSPJob job;

      if (useGPU) {
        job = PiEstimatorGpuBSP.createPiEstimatorGpuBSPConf(
            new HamaConfiguration(m_conf), m_OUTPUT_DIR_PATH);

        job.set(PiEstimatorGpuBSP.CONF_BLOCKSIZE, "" + m_blockSize);
        job.set(PiEstimatorGpuBSP.CONF_GRIDSIZE, "" + m_gridSize);
        job.setNumBspTask(1);
        job.set(PiEstimatorGpuBSP.CONF_ITERATIONS, "" + m_totalIterations);
        job.setBoolean(PiEstimatorGpuBSP.CONF_DEBUG, false);

      } else {
        job = PiEstimatorCpuBSP.createPiEstimatorCpuBSPConf(
            new HamaConfiguration(m_conf), m_OUTPUT_DIR_PATH);

        job.setNumBspTask(8);
        job.set(PiEstimatorCpuBSP.CONF_ITERATIONS, "" + m_totalIterations);
      }

      return (job.waitForCompletion(true) ? 1 : 0);
    }
  }

  private int piEstimatorHamaCPU(int sum) {
    try {
      sum += ToolRunner.run(new PiEstimator(false), null);
    } catch (Exception e) {
      e.printStackTrace();
    }
    return sum;
  }

  private int piEstimatorHamaGPU(int sum) {
    try {
      sum += ToolRunner.run(new PiEstimator(true), null);
    } catch (Exception e) {
      e.printStackTrace();
    }
    return sum;
  }

  public static void main(String[] args) {
    CaliperMain.main(PiEstimatorBenchmark.class, args);
  }

}
