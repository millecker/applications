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
package at.illecker.hama.hybrid.examples.matrixmultiplication2;

import java.io.IOException;
import java.util.Random;

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

public class MatrixMultiplicationHybridBenchmark extends Benchmark {

  @Param({ "512", "1024", "2048", "3072", "4096" })
  private int n;

  @Param
  CalcType type;

  public enum CalcType {
    CPU, GPU
  };

  private static final String OUTPUT_DIR = "output/hama/hybrid/examples/matrixmultiplication/bench";

  private Path m_OUTPUT_DIR_PATH;
  private Path m_MATRIX_A_PATH;
  private Path m_MATRIX_B_PATH;
  private Path m_MATRIX_C_PATH;
  private Path m_MATRIX_D_PATH;

  private Configuration m_conf = null;
  private boolean m_runLocally = false;

  private int m_blockSize;
  private int m_gridSize;

  private DistributedRowMatrix m_matrixA;
  private DistributedRowMatrix m_matrixB;

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

    m_MATRIX_A_PATH = new Path(m_OUTPUT_DIR_PATH + "/MatrixA.seq");
    m_MATRIX_B_PATH = new Path(m_OUTPUT_DIR_PATH + "/MatrixB.seq");
    m_MATRIX_C_PATH = new Path(m_OUTPUT_DIR_PATH + "/MatrixC.seq");
    m_MATRIX_D_PATH = new Path(m_OUTPUT_DIR_PATH + "/MatrixD.seq");

    System.out.println("Benchmark MatrixMultiplication " + type
        + " [blockSize=" + m_blockSize + ",gridSize=" + m_gridSize + "] " + n
        + " x " + n + " matrix");

    // Create random DistributedRowMatrix
    DistributedRowMatrix.createRandomDistributedRowMatrix(m_conf, n, n,
        new Random(42L), m_MATRIX_A_PATH, false);

    DistributedRowMatrix.createRandomDistributedRowMatrix(m_conf, n, n,
        new Random(1337L), m_MATRIX_B_PATH, (type == CalcType.CPU) ? true
            : false);

    // Load DistributedRowMatrix a and b
    m_matrixA = new DistributedRowMatrix(m_MATRIX_A_PATH, m_OUTPUT_DIR_PATH, n,
        n);
    m_matrixB = new DistributedRowMatrix(m_MATRIX_B_PATH, m_OUTPUT_DIR_PATH, n,
        n);
    m_matrixA.setConf(m_conf);
    m_matrixB.setConf(m_conf);
  }

  @Override
  protected void tearDown() throws Exception {

    verify();

    // Cleanup
    FileSystem fs = FileSystem.get(m_conf);
    fs.delete(m_MATRIX_A_PATH, true);
    fs.delete(m_MATRIX_B_PATH, true);
    fs.delete(m_MATRIX_C_PATH, true);
    fs.delete(m_MATRIX_D_PATH, true);

    printOutput(m_conf);
  }

  private void verify() throws Exception {

    DistributedRowMatrix matrixC = new DistributedRowMatrix(m_MATRIX_C_PATH,
        m_OUTPUT_DIR_PATH, n, n);
    matrixC.setConf(m_conf);

    if (type == CalcType.CPU) {
      // Overwrite matrix B, NOT transposed for verification check
      DistributedRowMatrix.createRandomDistributedRowMatrix(m_conf, n, n,
          new Random(1337L), m_MATRIX_B_PATH, false);
      m_matrixB = new DistributedRowMatrix(m_MATRIX_B_PATH, m_OUTPUT_DIR_PATH,
          n, n);
      m_matrixB.setConf(m_conf);
    }

    DistributedRowMatrix matrixD = m_matrixA.multiplyJava(m_matrixB,
        m_MATRIX_D_PATH);

    if (matrixC.verify(matrixD)) {
      System.out.println("Verify PASSED!");
    } else {
      System.out.println("Verify FAILED!");
    }
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
    /*
     * case JAVA: sum = matrixMultiplyJava(sum); break;
     */
      case CPU:
        sum = matrixMultiplyHamaCPU(sum);
        break;
      case GPU:
        sum = matrixMultiplyHamaGPU(sum);
        break;
      default:
        break;
    }
    return sum;
  }

  private class MatrixMultiplication extends Configured implements Tool {
    private boolean useGPU;

    public MatrixMultiplication(boolean useGPU) {
      this.useGPU = useGPU;
    }

    @Override
    public int run(String[] arg0) throws Exception {
/*
      if (useGPU) {
        m_conf.set(MatrixMultiplicationHybridBSP.CONF_BLOCKSIZE, ""
            + m_blockSize);
        m_conf
            .set(MatrixMultiplicationHybridBSP.CONF_GRIDSIZE, "" + m_gridSize);
        m_conf.setBoolean(MatrixMultiplicationHybridBSP.CONF_DEBUG, false);
        m_conf.setInt("bsp.peers.num", 1);

        m_matrixA.setConf(m_conf);
        m_matrixB.setConf(m_conf);
      }

      DistributedRowMatrix resultMatrix = m_matrixA.multiplyBSP(m_matrixB,
          m_MATRIX_C_PATH);
*/
      return 0;
    }
  }

  private int matrixMultiplyHamaCPU(int sum) {
    try {
      sum += ToolRunner.run(new MatrixMultiplication(false), null);
    } catch (Exception e) {
      e.printStackTrace();
    }
    return sum;
  }

  private int matrixMultiplyHamaGPU(int sum) {
    try {
      sum += ToolRunner.run(new MatrixMultiplication(true), null);
    } catch (Exception e) {
      e.printStackTrace();
    }
    return sum;
  }

  public static void main(String[] args) {
    CaliperMain.main(MatrixMultiplicationHybridBenchmark.class, args);
  }

}
