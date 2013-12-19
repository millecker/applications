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

import com.google.caliper.Benchmark;
import com.google.caliper.Param;
import com.google.caliper.api.Macrobenchmark;
import com.google.caliper.runner.CaliperMain;

public class KMeansHybridBenchmark extends Benchmark {

  @Param({ "512", "1024", "2048", "3072", "4096" })
  private int n;

  @Param
  CalcType type;

  public enum CalcType {
    CPU, GPU
  };

  private static final String OUTPUT_DIR = "output/hama/rootbeer/examples/matrixmultiplication/bench";

  private Path m_OUTPUT_DIR_PATH;

  private Configuration m_conf = null;
  private boolean m_runLocally = false;

  private int m_blockSize;
  private int m_gridSize;

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

    // TODO

  }

  @Override
  protected void tearDown() throws Exception {

    verify();

    printOutput(m_conf);
  }

  private void verify() throws Exception {

  }

  static void printOutput(Configuration conf) throws IOException {
    FileSystem fs = FileSystem.get(conf);
    FileStatus[] files = fs.listStatus(new Path(OUTPUT_DIR));
    for (int i = 0; i < files.length; i++) {
      if (files[i].getLen() > 0) {
        System.out.println("File " + files[i].getPath());
        FSDataInputStream in = fs.open(files[i].getPath());
        IOUtils.copyBytes(in, System.out, conf, false);
        in.close();
      }
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
