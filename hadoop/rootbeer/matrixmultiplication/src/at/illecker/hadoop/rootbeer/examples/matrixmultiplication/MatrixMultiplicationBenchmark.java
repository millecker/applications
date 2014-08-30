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
package at.illecker.hadoop.rootbeer.examples.matrixmultiplication;

import java.io.IOException;
import java.util.Random;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.mapreduce.Job;

import at.illecker.hadoop.rootbeer.examples.matrixmultiplication.cpu.MatrixMultiplicationCpu;
import at.illecker.hadoop.rootbeer.examples.matrixmultiplication.gpu.MatrixMultiplicationGpu;

import com.google.caliper.Benchmark;
import com.google.caliper.Param;
import com.google.caliper.api.Macrobenchmark;
import com.google.caliper.runner.CaliperMain;

public class MatrixMultiplicationBenchmark extends Benchmark {

  @Param({ "256", "512", "768", "1024", "1280", "1536", "1792", "2048" })
  private int n;

  @Param
  CalcType type;

  public enum CalcType {
    // JAVA,
    CPU, GPU
  };

  public static final int TILE_WIDTH = 32; // -> max blockSize 1024

  private static final Path CONF_TMP_DIR = new Path(
      "output/hadoop/rootbeer/examples/matrixmultiplication/bench-"
          + System.currentTimeMillis());
  private static final Path CONF_INPUT_DIR = new Path(CONF_TMP_DIR, "input");
  private static final Path CONF_OUTPUT_DIR = new Path(CONF_TMP_DIR, "output");

  private Configuration m_conf;
  private boolean m_runLocally = false;

  private Path m_transposedMatrixAPath = new Path(CONF_INPUT_DIR
      + "/transposedMatrixA.seq");
  private Path m_matrixAPath = new Path(CONF_INPUT_DIR + "/matrixA.seq");
  private Path m_matrixBPath = new Path(CONF_INPUT_DIR + "/matrixB.seq");
  private Path m_matrixCPath = new Path(CONF_OUTPUT_DIR + "/matrixC.seq");

  private DistributedRowMatrix m_transposedMatrixA;
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
    }

    // Create random DistributedRowMatrix and write out transposed
    DistributedRowMatrix.createRandomDistributedRowMatrix(m_conf, n, n,
        new Random(42L), m_transposedMatrixAPath, true);
    DistributedRowMatrix.createRandomDistributedRowMatrix(m_conf, n, n,
        new Random(), m_matrixBPath, false);

    // Load DistributedRowMatrix A and B
    m_transposedMatrixA = new DistributedRowMatrix(m_transposedMatrixAPath,
        CONF_INPUT_DIR, n, n);
    m_transposedMatrixA.setConf(m_conf);

    m_matrixB = new DistributedRowMatrix(m_matrixBPath, CONF_INPUT_DIR, n, n);
    m_matrixB.setConf(m_conf);

    // Debug output
    System.out.println("CONF_TMP_DIR: " + CONF_TMP_DIR.toString());
    System.out.println("Benchmark " + n + " x " + n + " matrix on " + type);
  }

  @Override
  protected void tearDown() throws Exception {
    // verify();

    // Cleanup
    FileSystem fs = FileSystem.get(m_conf);
    fs.delete(CONF_TMP_DIR, true);

    // printOutput(m_conf);
  }

  private void verify() throws Exception {
    // Create NOT transposed matrix A for verification check
    DistributedRowMatrix.createRandomDistributedRowMatrix(m_conf, n, n,
        new Random(42L), m_matrixAPath, false);
    DistributedRowMatrix matrixA = new DistributedRowMatrix(m_matrixAPath,
        CONF_INPUT_DIR, n, n);
    matrixA.setConf(m_conf);

    Path matrixDPath = new Path(CONF_INPUT_DIR + "/MatrixD.seq");
    DistributedRowMatrix matrixD = matrixA.multiplyJava(m_matrixB, matrixDPath);

    // Load MapReduce result matrix C
    DistributedRowMatrix matrixC = new DistributedRowMatrix(m_matrixCPath,
        CONF_OUTPUT_DIR, n, n);
    matrixC.setConf(m_conf);

    if (matrixC.verify(matrixD)) {
      System.out.println("Verify PASSED!");
    } else {
      System.out.println("Verify FAILED!");
    }

    // matrixC.printDistributedRowMatrix();
    // matrixD.printDistributedRowMatrix();
  }

  private void printOutput(Configuration conf) throws IOException {
    FileSystem fs = FileSystem.get(conf);
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
      Configuration conf = null;

      switch (type) {
      // case JAVA:
      // m_matrixA.multiplyJava(m_matrixB, m_matrixC);
      // break;
        case CPU:
          conf = MatrixMultiplicationCpu.createMatrixMultiplicationCpuConf(
              m_conf, m_transposedMatrixAPath, m_matrixBPath, m_matrixCPath,
              Integer.MAX_VALUE, false);
          break;
        case GPU:
          conf = MatrixMultiplicationGpu.createMatrixMultiplicationGpuConf(
              m_conf, m_transposedMatrixAPath, m_matrixBPath, m_matrixCPath,
              Integer.MAX_VALUE, TILE_WIDTH, false);
          break;
        default:
          break;
      }

      Job job = new Job(conf);
      long startTime = System.currentTimeMillis();
      job.waitForCompletion(false);
      System.out.println("MatrixMultiplication on " + type + " with size: " + n
          + " finished in " + (System.currentTimeMillis() - startTime) / 1000.0
          + " seconds");

    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public static void main(String[] args) {
    CaliperMain.main(MatrixMultiplicationBenchmark.class, args);
  }

}
