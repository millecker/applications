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
package at.illecker.hama.hybrid.examples.kmeans;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.hama.bsp.BSPJob;

import com.google.caliper.Benchmark;
import com.google.caliper.Param;
import com.google.caliper.api.Macrobenchmark;
import com.google.caliper.runner.CaliperMain;

public class KMeansHybridBenchmark extends Benchmark {

  // Plot 1
  // @Param({ "250000", "500000", "750000", "1000000", "1250000", "1500000",
  // "1750000", "2000000" })
  // private long n = 2000000;

  // Plot 2
  // @Param({ "50", "100", "150", "200", "250", "300", "350", "400", "450",
  // "500" })
  private int k = 500;

  // @Param
  // CalcType type;

  public enum CalcType {
    CPU, GPU
  };

  // Plot 3
  // maximal 8 cpu tasks and 1 gpu task
  @Param({ "1", "2", "3", "4", "5" })
  private int bspTaskNum; // = 2;
  // 2 threads only, because more threads would consume more than 16G RAM
  private long n = 1000000;

  private int vectorDimension = 3;
  private int maxIteration = 10;

  private static final Path CONF_TMP_DIR = new Path(
      "output/hama/hybrid/examples/kmeans/bench-" + System.currentTimeMillis());
  private static final Path CONF_INPUT_DIR = new Path(CONF_TMP_DIR, "input");
  private static final Path CONF_OUTPUT_DIR = new Path(CONF_TMP_DIR, "output");
  private static final Path CONF_CENTER_DIR = new Path(CONF_TMP_DIR, "centers");

  private Configuration m_conf = null;
  private boolean m_runLocally = false;

  // gridSize = amount of blocks and multiprocessors
  public static final int GRID_SIZE = 14;
  // blockSize = amount of threads
  public static final int BLOCK_SIZE = 1024;

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

    // Setup KMeans config variables
    m_conf.setBoolean(KMeansHybridBSP.CONF_DEBUG, false);
    m_conf.setBoolean("hama.pipes.logging", false);

    // Set GPU blockSize and gridSize
    m_conf.set(KMeansHybridBSP.CONF_BLOCKSIZE, "" + BLOCK_SIZE);
    m_conf.set(KMeansHybridBSP.CONF_GRIDSIZE, "" + GRID_SIZE);
    // Set maxIterations for KMeans
    m_conf.setInt(KMeansHybridBSP.CONF_MAX_ITERATIONS, maxIteration);
    // Set n for KMeans
    m_conf.setLong(KMeansHybridBSP.CONF_N, n);

    Path centerIn = new Path(CONF_CENTER_DIR, "center_in.seq");
    Path centerOut = new Path(CONF_CENTER_DIR, "center_out.seq");
    m_conf.set(KMeansHybridBSP.CONF_CENTER_IN_PATH, centerIn.toString());
    m_conf.set(KMeansHybridBSP.CONF_CENTER_OUT_PATH, centerOut.toString());

    // CPU vs GPU benchmark
    // Plot 1 and 2
    int numGpuBspTask = 0;
    // if (type == CalcType.GPU) {
    // bspTaskNum = 1;
    // numGpuBspTask = 1;
    // }

    // CPU + GPU Hybrid benchmark
    // Plot 3
    if (bspTaskNum == 5) {
      numGpuBspTask = 1;
    } else {
      numGpuBspTask = 0;
    }

    // Set CPU tasks
    m_conf.setInt("bsp.peers.num", bspTaskNum);
    // Set GPU tasks
    m_conf.setInt("bsp.peers.gpu.num", numGpuBspTask);

    KMeansHybridBSP.prepareInputData(m_conf, FileSystem.get(m_conf),
        CONF_INPUT_DIR, centerIn, bspTaskNum, n, k, vectorDimension, null);

    // System.out.println("CalcType: " + type);
    System.out.println("CONF_TMP_DIR: " + CONF_TMP_DIR.toString());
    System.out.println("n: " + n + " k: " + k + " vectorDimension: "
        + vectorDimension);
    System.out.println("NumBspTask: " + m_conf.getInt("bsp.peers.num", 0));
    System.out.println("NumGpuBspTask: "
        + m_conf.getInt("bsp.peers.gpu.num", 0));
  }

  @Override
  protected void tearDown() throws Exception {
    verify();

    FileSystem fs = FileSystem.get(m_conf);
    fs.delete(CONF_TMP_DIR, true);
  }

  private void verify() throws Exception {

  }

  @Macrobenchmark
  public void timeCalculate() {
    doBenchmark();
  }

  public void doBenchmark() {
    try {
      ToolRunner.run(new KMeans(), null);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private class KMeans extends Configured implements Tool {
    public KMeans() {
    }

    @Override
    public int run(String[] arg0) throws Exception {
      BSPJob job = KMeansHybridBSP.createKMeansHybridBSPConf(m_conf,
          CONF_INPUT_DIR, CONF_OUTPUT_DIR);

      long startTime = System.currentTimeMillis();
      if (job.waitForCompletion(true)) {
        System.out.println("Job Finished in "
            + (System.currentTimeMillis() - startTime) / 1000.0 + " seconds");
      }

      return 0;
    }
  }

  public static void main(String[] args) {
    CaliperMain.main(KMeansHybridBenchmark.class, args);
  }
}
