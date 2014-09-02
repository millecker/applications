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
import java.util.List;
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
import org.apache.hama.bsp.BSPJob;

import com.google.caliper.Benchmark;
import com.google.caliper.Param;
import com.google.caliper.api.Macrobenchmark;
import com.google.caliper.runner.CaliperMain;

public class MatrixMultiplicationHybridBenchmark extends Benchmark {

  // @Param({ "256", "512", "768", "1024", "1280", "1536", "1792", "2048" })
  private int n = 2048;

  // @Param
  // CalcType type;

  public enum CalcType {
    CPU, GPU
  };

  @Param({ "1", "2", "3", "4", "5" })
  private int bspTaskNum; // = 5;
  private final int maxTaskNum = 5;

  // GPU percentage of the input data
  // @Param({ "20", "30", "40", "50", "60", "70", "80", "90", "95" })
  private int GPUWorkload = 0;

  private static final int TILE_WIDTH = 32; // max blockSize is 1024 (32 x 32)

  private static final Path CONF_TMP_DIR = new Path(
      "output/hama/hybrid/examples/matrixmultiplication/bench-"
          + System.currentTimeMillis());
  private static final Path CONF_INPUT_DIR = new Path(CONF_TMP_DIR, "input");
  private static final Path CONF_OUTPUT_DIR = new Path(CONF_TMP_DIR, "output");

  private static final Path MATRIX_A_SPLITS_PATH = new Path(CONF_INPUT_DIR
      + "/matrixAsplits/");
  private static Path MATRIX_B_TRANSPOSED_PATH = new Path(CONF_INPUT_DIR
      + "/transposedMatrixB");
  private static final Path MATRIX_A_PATH = new Path(CONF_INPUT_DIR
      + "/matrixA");
  private static Path MATRIX_B_PATH = new Path(CONF_INPUT_DIR + "/matrixB");
  private static final Path MATRIX_C_PATH = new Path(CONF_OUTPUT_DIR
      + "/matrixC");
  private static final Path MATRIX_D_PATH = new Path(CONF_OUTPUT_DIR
      + "/matrixD");

  private Configuration m_conf = null;
  private boolean m_runLocally = false;
  private int m_numBspTask;
  private int m_numGpuBspTask;
  private List<Path> m_transposedMatrixBPaths;
  private DistributedRowMatrix m_matrixC = null;

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

    // CPU vs GPU benchmark
    int numGpuBspTask = 0;
    // if (type == CalcType.GPU) {
    // bspTaskNum = 1;
    // numGpuBspTask = 1;
    // GPUWorkload = 100;
    // }

    // CPU + GPU Hybrid benchmark
    if (bspTaskNum == maxTaskNum) {
      numGpuBspTask = 1;
      GPUWorkload = 95;
    } else {
      numGpuBspTask = 0;
    }

    // Set CPU tasks
    m_conf.setInt("bsp.peers.num", bspTaskNum);
    m_numBspTask = bspTaskNum;
    // Set GPU tasks
    m_conf.setInt("bsp.peers.gpu.num", numGpuBspTask);
    m_numGpuBspTask = numGpuBspTask;

    m_conf.setBoolean("hama.pipes.logging", false);

    // Generate input matrix A and transposed matrix B
    prepareInput();

    // Debug output
    // System.out.println("CalcType: " + type);
    System.out.println("CONF_TMP_DIR: " + CONF_TMP_DIR.toString());
    System.out.println("NumBspTask: " + m_conf.getInt("bsp.peers.num", 0)
        + " NumGpuBspTask: " + m_conf.getInt("bsp.peers.gpu.num", 0));
    System.out.println("n: " + n + " GPUWorkload: " + GPUWorkload + "%");
  }

  @Override
  protected void tearDown() throws Exception {
    // skip verification
    // verify();

    // Cleanup
    FileSystem fs = FileSystem.get(m_conf);
    fs.delete(CONF_TMP_DIR, true);

    // printOutput(m_conf);
  }

  private void prepareInput() throws Exception {
    // Create random DistributedRowMatrix
    // use constant seeds to get reproducible results
    // Matrix A
    DistributedRowMatrix.createRandomDistributedRowMatrix(m_conf, n, n,
        new Random(42L), MATRIX_A_SPLITS_PATH, false, m_numBspTask,
        m_numGpuBspTask, GPUWorkload);

    // Matrix B is stored in transposed order
    m_transposedMatrixBPaths = DistributedRowMatrix
        .createRandomDistributedRowMatrix(m_conf, n, n, new Random(1337L),
            MATRIX_B_TRANSPOSED_PATH, true);
  }

  private void verify() throws Exception {
    // Create matrix A in one file for verification
    List<Path> matrixAPaths = DistributedRowMatrix
        .createRandomDistributedRowMatrix(m_conf, n, n, new Random(42L),
            MATRIX_A_PATH, false);
    DistributedRowMatrix matrixA = new DistributedRowMatrix(
        matrixAPaths.get(0), CONF_INPUT_DIR, n, n);
    matrixA.setConf(m_conf);

    // Create matrix B, NOT transposed for verification
    List<Path> matrixBPaths = DistributedRowMatrix
        .createRandomDistributedRowMatrix(m_conf, n, n, new Random(1337L),
            MATRIX_B_PATH, false);
    DistributedRowMatrix matrixB = new DistributedRowMatrix(
        matrixBPaths.get(0), CONF_INPUT_DIR, n, n);
    matrixB.setConf(m_conf);

    // Verification
    DistributedRowMatrix matrixD = matrixA.multiplyJava(matrixB, MATRIX_D_PATH);
    if (m_matrixC.verify(matrixD)) {
      System.out.println("Verify PASSED!");
    } else {
      System.out.println("Verify FAILED!");
    }
  }

  static void printOutput(Configuration conf) throws IOException {
    FileSystem fs = CONF_OUTPUT_DIR.getFileSystem(conf);
    FileStatus[] files = fs.listStatus(CONF_OUTPUT_DIR);
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

  @Macrobenchmark
  public void timeCalculate() {
    doBenchmark();
  }

  public void doBenchmark() {
    try {
      ToolRunner.run(new MatrixMultiplication(), null);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private class MatrixMultiplication extends Configured implements Tool {
    public MatrixMultiplication() {
    }

    @Override
    public int run(String[] arg0) throws Exception {
      BSPJob job = MatrixMultiplicationHybridBSP
          .createMatrixMultiplicationHybridBSPConf(m_conf,
              MATRIX_A_SPLITS_PATH, m_transposedMatrixBPaths.get(0),
              MATRIX_C_PATH, TILE_WIDTH, false);

      long startTime = System.currentTimeMillis();

      // Execute MatrixMultiplication BSP Job
      if (job.waitForCompletion(true)) {

        // Rename result file to output path
        Path matrixCoutPath = new Path(MATRIX_C_PATH + "/part0.seq");

        FileSystem fs = MATRIX_C_PATH.getFileSystem(m_conf);
        FileStatus[] files = fs.listStatus(MATRIX_C_PATH);
        for (int i = 0; i < files.length; i++) {
          if ((files[i].getPath().getName().startsWith("part-"))
              && (files[i].getLen() > 97)) {
            fs.rename(files[i].getPath(), matrixCoutPath);
            break;
          }
        }

        // Read resulting Matrix from HDFS
        m_matrixC = new DistributedRowMatrix(matrixCoutPath, MATRIX_C_PATH, n,
            n);
        m_matrixC.setConf(m_conf);
      }

      System.out.println("MatrixMultiplicationHybrid using Hama finished in "
          + (System.currentTimeMillis() - startTime) / 1000.0 + " seconds");

      return 0;
    }
  }

  public static void main(String[] args) {
    CaliperMain.main(MatrixMultiplicationHybridBenchmark.class, args);
  }

}
