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
import com.google.caliper.model.ArbitraryMeasurement;
import com.google.caliper.runner.CaliperMain;

public class PiEstimatorHybridBenchmark extends Benchmark {

  // Plot 1 - CPU vs GPU comparison
  // @Param({ "250", "500", "750", "1000", "1250", "1500", "1750", "2000",
  // "2250",
  // "2500", "2750", "3000" })
  private long n = 3000;

  // maximal 4 CPU tasks and 1 GPU task
  private final int maxBspTaskNum = 5;
  @Param({ "1", "2", "3", "4", "5" })
  private int bspTaskNum; // = 5; // = 4; Plot 1

  // GPU percentage of the input data
  // @Param({ "20", "50", "60", "70", "80", "85", "90", "95" })
  private int GPUWorkload = 0;

  // Used only for Plot 1 - CPU vs GPU comparison
  // @Param
  // CalcType type;
  // public enum CalcType {
  // CPU, GPU
  // };

  private static final Path CONF_TMP_DIR = new Path(
      "output/hama/hybrid/examples/piestimator/bench-"
          + System.currentTimeMillis());
  private static final Path CONF_OUTPUT_DIR = new Path(CONF_TMP_DIR, "output");
  private static final int m_blockSize = PiEstimatorHybridBSP.BLOCK_SIZE;
  private static final int m_gridSize = PiEstimatorHybridBSP.GRID_SIZE;

  private Configuration m_conf = null;
  private boolean m_runLocally = false;
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

    // calculate total sampling size
    m_totalIterations = (long) 1024 * (long) 14 * (long) 1000 * n;

    int numGpuBspTask = 0;

    // Used only for Plot 1 - CPU vs GPU comparison
    // if (type == CalcType.GPU) {
    // bspTaskNum = 1;
    // numGpuBspTask = 1;
    // GPUWorkload = 100;
    // }

    // Used only for Plot 2 - CPU + GPU Hybrid benchmark
    if (bspTaskNum == maxBspTaskNum) {
      numGpuBspTask = 1;
      GPUWorkload = 95;
    } else {
      numGpuBspTask = 0;
    }

    // Set CPU tasks
    m_conf.setInt("bsp.peers.num", bspTaskNum);
    // Set GPU tasks
    m_conf.setInt("bsp.peers.gpu.num", numGpuBspTask);

    m_conf.setInt(PiEstimatorHybridBSP.CONF_BLOCKSIZE, m_blockSize);
    m_conf.setInt(PiEstimatorHybridBSP.CONF_GRIDSIZE, m_gridSize);
    m_conf.setLong(PiEstimatorHybridBSP.CONF_ITERATIONS, m_totalIterations);
    m_conf.setInt(PiEstimatorHybridBSP.CONF_GPU_PERCENTAGE, GPUWorkload);
    m_conf.setBoolean(PiEstimatorHybridBSP.CONF_DEBUG, false);
    m_conf.setBoolean(PiEstimatorHybridBSP.CONF_TIME, false);

    // Debug output
    System.out.println("Benchmark PiEstimatorHybridBSP[blockSize="
        + m_blockSize + ",gridSize=" + m_gridSize + "] n=" + n + ",bspTaskNum="
        + bspTaskNum + ",GpuBspTaskNum=" + numGpuBspTask + ",GPUWorkload="
        + GPUWorkload + ",totalSamples=" + m_totalIterations);
    System.out.println("CONF_TMP_DIR: " + CONF_TMP_DIR.toString());
  }

  @Override
  protected void tearDown() throws Exception {

    // printOutput(m_conf, CONF_OUTPUT_DIR);

    // Cleanup
    FileSystem fs = FileSystem.get(m_conf);
    fs.delete(CONF_TMP_DIR, true);
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

  @Macrobenchmark
  public void timeCalculate() {
    timePiBenchmark();
  }

  @ArbitraryMeasurement
  public double arbitraryBenchmark() {
    return arbitaryPiBenchmark();
  }

  private void timePiBenchmark() {
    try {
      ToolRunner.run(new PiEstimatorHybrid(), null);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private double arbitaryPiBenchmark() {
    try {
      PiEstimatorHybrid piEstimatorHybrid = new PiEstimatorHybrid();
      ToolRunner.run(piEstimatorHybrid, null);
      return piEstimatorHybrid.time;
    } catch (Exception e) {
      e.printStackTrace();
    }
    return 0;
  }

  private class PiEstimatorHybrid extends Configured implements Tool {
    public double time = 0;

    public PiEstimatorHybrid() {
    }

    @Override
    public int run(String[] arg0) throws Exception {
      BSPJob job = PiEstimatorHybridBSP.createPiEstimatorHybridConf(
          new HamaConfiguration(m_conf), CONF_OUTPUT_DIR);

      long startTime = System.currentTimeMillis();
      if (job.waitForCompletion(true)) {
        time = System.currentTimeMillis() - startTime;
        System.out.println("Time: " + ((time / 1000.0)) + " sec");
        return 1; // true
      }
      return 0; // false
    }
  }

  public static void main(String[] args) {
    CaliperMain.main(PiEstimatorHybridBenchmark.class, args);
  }
}
