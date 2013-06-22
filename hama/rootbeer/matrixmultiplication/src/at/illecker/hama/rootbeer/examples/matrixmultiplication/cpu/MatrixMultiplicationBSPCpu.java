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
package at.illecker.hama.rootbeer.examples.matrixmultiplication.cpu;

import java.io.IOException;
import java.util.Random;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hama.HamaConfiguration;
import org.apache.hama.bsp.BSP;
import org.apache.hama.bsp.BSPJob;
import org.apache.hama.bsp.BSPJobClient;
import org.apache.hama.bsp.BSPPeer;
import org.apache.hama.bsp.ClusterStatus;
import org.apache.hama.bsp.FileOutputFormat;
import org.apache.hama.bsp.SequenceFileInputFormat;
import org.apache.hama.bsp.SequenceFileOutputFormat;
import org.apache.hama.bsp.join.CompositeInputFormat;
import org.apache.hama.bsp.join.TupleWritable;
import org.apache.hama.bsp.sync.SyncException;
import org.apache.mahout.math.CardinalityException;
import org.apache.mahout.math.VectorWritable;

import at.illecker.hama.rootbeer.examples.matrixmultiplication.util.DistributedRowMatrix;

/**
 * @author MatrixMultiplication based on Mahout https://github.com/apache/mahout
 *         /blob/trunk/core/src/main/java/org/apache
 *         /mahout/math/hadoop/MatrixMultiplicationJob.java
 * 
 */

public class MatrixMultiplicationBSPCpu extends
    BSP<IntWritable, TupleWritable, IntWritable, VectorWritable, NullWritable> {

  private static final Log LOG = LogFactory
      .getLog(MatrixMultiplicationBSPCpu.class);

  private static final Path TMP_OUTPUT = new Path(
      "output/hama/rootbeer/examples/matrixmultiplication/CPU-"
          + System.currentTimeMillis());
  private static final String OUT_CARD = "output.vector.cardinality";
  private static final String DEBUG = "matrixmultiplication.bsp.cpu.debug";

  private static final Path OUTPUT_DIR = new Path(
      "output/hadoop/rootbeer/examples/matrixmultiplication/CPU-"
          + System.currentTimeMillis());

  private static final Path MATRIX_A_PATH = new Path(
      "input/hadoop/rootbeer/examples/MatrixA.seq");
  private static final Path MATRIX_B_PATH = new Path(
      "input/hadoop/rootbeer/examples/MatrixB.seq");
  private static final Path MATRIX_C_PATH = new Path(OUTPUT_DIR
      + "/MatrixC.seq");

  @Override
  public void setup(
      BSPPeer<IntWritable, TupleWritable, IntWritable, VectorWritable, NullWritable> peer)
      throws IOException {

  }

  @Override
  public void bsp(
      BSPPeer<IntWritable, TupleWritable, IntWritable, VectorWritable, NullWritable> peer)
      throws IOException, SyncException, InterruptedException {

  }

  @Override
  public void cleanup(
      BSPPeer<IntWritable, TupleWritable, IntWritable, VectorWritable, NullWritable> peer)
      throws IOException {

  }

  static void printOutput(Configuration conf) throws IOException {
    FileSystem fs = OUTPUT_DIR.getFileSystem(conf);
    FileStatus[] files = fs.listStatus(OUTPUT_DIR);
    for (int i = 0; i < files.length; i++) {
      if (files[i].getLen() > 0) {
        System.out.println("File " + files[i].getPath());
        if (files[i].getPath().getName().endsWith(".log")) {
          FSDataInputStream in = fs.open(files[i].getPath());
          IOUtils.copyBytes(in, System.out, conf, false);
          in.close();
        }
      }
    }
    // fs.delete(FileOutputFormat.getOutputPath(job), true);
  }

  public static BSPJob createMatrixMultiplicationBSPCpuConf(Path aPath,
      Path bPath, Path outPath, int outCardinality) throws IOException {

    return createMatrixMultiplicationBSPCpuConf(new HamaConfiguration(), aPath,
        bPath, outPath, outCardinality);
  }

  public static BSPJob createMatrixMultiplicationBSPCpuConf(Configuration conf,
      Path aPath, Path bPath, Path outPath, int outCardinality)
      throws IOException {

    BSPJob job = new BSPJob(new HamaConfiguration(conf));
    // Set the job name
    job.setJobName("MatrixMultiplicationBSP CPU");
    // set the BSP class which shall be executed
    job.setBspClass(MatrixMultiplicationBSPCpu.class);
    // help Hama to locale the jar to be distributed
    job.setJarByClass(MatrixMultiplicationBSPCpu.class);

    job.setInputFormat(CompositeInputFormat.class);
    job.set("mapred.join.expr", CompositeInputFormat.compose("inner",
        SequenceFileInputFormat.class, aPath, bPath));

    job.setOutputFormat(SequenceFileOutputFormat.class);
    job.setOutputKeyClass(IntWritable.class);
    job.setOutputValueClass(VectorWritable.class);

    FileOutputFormat.setOutputPath(job, TMP_OUTPUT);

    job.set(OUT_CARD, "" + outCardinality);
    job.set("bsp.child.java.opts", "-Xmx4G");

    return job;
  }

  public static void main(String[] args) throws Exception {

    // Defaults
    int numRowsA = 4;
    int numColsA = 4;
    int numRowsB = 4;
    int numColsB = 4;
    boolean isDebugging = false;

    Configuration conf = new HamaConfiguration();
    BSPJobClient jobClient = new BSPJobClient(conf);
    ClusterStatus cluster = jobClient.getClusterStatus(true);

    if (args.length > 0) {
      if (args.length == 6) {
        conf.setInt("bsp.peers.num", Integer.parseInt(args[0]));
        numRowsA = Integer.parseInt(args[1]);
        numColsA = Integer.parseInt(args[2]);
        numRowsB = Integer.parseInt(args[3]);
        numColsB = Integer.parseInt(args[4]);
        isDebugging = Boolean.parseBoolean(args[5]);

      } else {
        System.out.println("Wrong argument size!");
        System.out.println("    Argument1=numBspTask");
        System.out
            .println("    Argument2=numRowsA | Number of rows of the first input matrix");
        System.out
            .println("    Argument3=numColsA | Number of columns of the first input matrix");
        System.out
            .println("    Argument4=numRowsB | Number of rows of the second input matrix");
        System.out
            .println("    Argument5=numColsB | Number of columns of the second input matrix");
        System.out
            .println("    Argument6=debug | Enable debugging (true|false)");
        return;
      }
    } else {
      conf.setInt("bsp.peers.num", cluster.getMaxTasks());
    }

    conf.setInt("matrixmultiplication.bsp.cpu.numRowsA", numRowsA);
    conf.setInt("matrixmultiplication.bsp.cpu.numColsA", numColsA);
    conf.setInt("matrixmultiplication.bsp.cpu.numRowsB", numRowsB);
    conf.setInt("matrixmultiplication.bsp.cpu.numColsB", numRowsB);
    conf.setBoolean(DEBUG, isDebugging);

    LOG.info("NumBspTask: " + conf.getInt("bsp.peers.num", 0));
    LOG.info("numRowsA: " + numRowsA);
    LOG.info("numColsA: " + numColsA);
    LOG.info("numRowsB: " + numRowsB);
    LOG.info("numColsB: " + numColsB);
    LOG.info("isDebugging: " + isDebugging);
    LOG.info("outputPath: " + OUTPUT_DIR);

    if (numColsA != numRowsB) {
      throw new CardinalityException(numColsA, numRowsB);
    }

    // Create random DistributedRowMatrix
    // use constant seeds to get reproducable results
    // Matrix A is stored transposed
    DistributedRowMatrix.createRandomDistributedRowMatrix(conf, numRowsA,
        numColsA, new Random(42L), MATRIX_A_PATH, true);
    DistributedRowMatrix.createRandomDistributedRowMatrix(conf, numRowsB,
        numColsB, new Random(1337L), MATRIX_B_PATH, false);

    // Load DistributedRowMatrix a and b
    DistributedRowMatrix a = new DistributedRowMatrix(MATRIX_A_PATH,
        OUTPUT_DIR, numRowsA, numColsA);
    a.setConf(conf);

    DistributedRowMatrix b = new DistributedRowMatrix(MATRIX_B_PATH,
        OUTPUT_DIR, numRowsB, numColsB);
    b.setConf(conf);

    // MatrixMultiply all within a new BSP job
    long startTime = System.currentTimeMillis();
    DistributedRowMatrix c = a.multiplyBSP(b, MATRIX_C_PATH, false, true);
    System.out.println("MatrixMultiplicationCpu using Hama finished in "
        + (System.currentTimeMillis() - startTime) / 1000.0 + " seconds");

    if (isDebugging) {
      System.out.println("Matrix A:");
      a.printDistributedRowMatrix();
      System.out.println("Matrix B:");
      b.printDistributedRowMatrix();
      System.out.println("Matrix C:");
      c.printDistributedRowMatrix();

      printOutput(conf);
    }
  }
}
