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

  @Param({ "1000000" })
  private long n;

  @Param({ "10", "30", "50", "70", "90" })
  private int k;

  @Param
  CalcType type;

  public enum CalcType {
    CPU, GPU
  };

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
  public static final int BLOCK_SIZE = 384; // 1024;

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

    KMeansHybridBSP.prepareInputData(m_conf, FileSystem.get(m_conf),
        CONF_INPUT_DIR, centerIn, 1, n, k, vectorDimension, null);

    System.out.println("CONF_TMP_DIR: " + CONF_TMP_DIR.toString());
    System.out.println("n: " + n + " k: " + k);
  }

  @Override
  protected void tearDown() throws Exception {

    verify();

    FileSystem fs = FileSystem.get(m_conf);
    fs.delete(CONF_TMP_DIR, true);
  }

  private void verify() throws Exception {

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
        sum = kmeansHamaCPU(sum);
        break;
      case GPU:
        sum = kmeansHamaGPU(sum);
        break;
      default:
        break;
    }
    return sum;
  }

  private class KMeans extends Configured implements Tool {
    private boolean useGPU;

    public KMeans(boolean useGPU) {
      this.useGPU = useGPU;
    }

    @Override
    public int run(String[] arg0) throws Exception {

      if (useGPU) {
        // Set CPU tasks
        m_conf.setInt("bsp.peers.num", 1);
        // Set GPU tasks
        m_conf.setInt("bsp.peers.gpu.num", 1);
      } else {
        // Set CPU tasks
        m_conf.setInt("bsp.peers.num", 1);
        // Set GPU tasks
        m_conf.setInt("bsp.peers.gpu.num", 0);
      }

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

  private int kmeansHamaCPU(int sum) {
    try {
      sum += ToolRunner.run(new KMeans(false), null);
    } catch (Exception e) {
      e.printStackTrace();
    }
    return sum;
  }

  private int kmeansHamaGPU(int sum) {
    try {
      sum += ToolRunner.run(new KMeans(true), null);
    } catch (Exception e) {
      e.printStackTrace();
    }
    return sum;
  }

  public static void main(String[] args) {
    CaliperMain.main(KMeansHybridBenchmark.class, args);
  }

}
