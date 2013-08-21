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
package at.illecker.hama.rootbeer.examples.matrixmultiplication.gpu;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hama.HamaConfiguration;
import org.apache.hama.bsp.BSP;
import org.apache.hama.bsp.BSPJob;
import org.apache.hama.bsp.BSPPeer;
import org.apache.hama.bsp.FileOutputFormat;
import org.apache.hama.bsp.SequenceFileInputFormat;
import org.apache.hama.bsp.SequenceFileOutputFormat;
import org.apache.hama.bsp.sync.SyncException;
import org.apache.hama.ml.math.DenseDoubleVector;
import org.apache.hama.ml.math.DoubleVector;
import org.apache.hama.ml.writable.VectorWritable;

import at.illecker.hama.rootbeer.examples.matrixmultiplication.util.DistributedRowMatrix;
import edu.syr.pcpratts.rootbeer.runtime.Rootbeer;
import edu.syr.pcpratts.rootbeer.runtime.StatsRow;
import edu.syr.pcpratts.rootbeer.runtime.util.Stopwatch;

public class MatrixMultiplicationBSPGpuNew extends
    BSP<IntWritable, VectorWritable, IntWritable, VectorWritable, NullWritable> {

  private static final Log LOG = LogFactory
      .getLog(MatrixMultiplicationBSPGpuNew.class);

  public static final String CONF_DEBUG = "matrixmultiplication.bsp.gpu.debug";
  public static final String CONF_MATRIX_MULT_B_PATH = "matrixmultiplication.bsp.gpu.B.path";
  public static final String CONF_BLOCKSIZE = "matrixmultiplication.bsp.blockSize";
  public static final String CONF_GRIDSIZE = "matrixmultiplication.bsp.gridSize";

  // gridSize = amount of blocks and multiprocessors
  public static final int GRID_SIZE = 14;
  // blockSize = amount of threads
  public static final int BLOCK_SIZE = 1024;

  private static final Path OUTPUT_DIR = new Path(
      "output/hama/rootbeer/examples/matrixmultiplication/GPU-"
          + System.currentTimeMillis());
  private static final Path MATRIX_A_PATH = new Path(
      "input/hama/rootbeer/examples/MatrixA.seq");
  private static final Path MATRIX_B_PATH = new Path(
      "input/hama/rootbeer/examples/MatrixB.seq");
  private static final Path MATRIX_C_PATH = new Path(OUTPUT_DIR
      + "/MatrixC.seq");
  private static final Path MATRIX_D_PATH = new Path(OUTPUT_DIR
      + "/MatrixD.seq");

  private boolean m_isDebuggingEnabled;
  private FSDataOutputStream m_logger;
  private String m_masterTask;
  private int m_gridSize;
  private int m_blockSize;
  private int m_threadSliceSize;
  private int m_blockSliceSize;

  private double[][] m_matrixBArr;

  @Override
  public void setup(
      BSPPeer<IntWritable, VectorWritable, IntWritable, VectorWritable, NullWritable> peer)
      throws IOException {

    Configuration conf = peer.getConfiguration();
    m_isDebuggingEnabled = conf.getBoolean(CONF_DEBUG, false);

    // Choose one as a master, who sorts the matrix rows at the end
    // m_masterTask = peer.getPeerName(peer.getNumPeers() / 2);
    // TODO
    // task must be 0 otherwise write out does NOT work!
    m_masterTask = peer.getPeerName(0);

    this.m_blockSize = Integer.parseInt(peer.getConfiguration().get(
        CONF_BLOCKSIZE));

    this.m_gridSize = Integer.parseInt(peer.getConfiguration().get(
        CONF_GRIDSIZE));

    // Init logging
    if (m_isDebuggingEnabled) {
      try {
        FileSystem fs = FileSystem.get(conf);
        m_logger = fs.create(new Path(FileOutputFormat
            .getOutputPath(new BSPJob((HamaConfiguration) conf))
            + "/BSP_"
            + peer.getTaskId() + ".log"));

      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    // Load matrixB
    SequenceFile.Reader reader = new SequenceFile.Reader(FileSystem.get(conf),
        new Path(conf.get(CONF_MATRIX_MULT_B_PATH)), conf);

    List<DoubleVector> matrixB = new ArrayList<DoubleVector>();
    IntWritable bKey = new IntWritable();
    VectorWritable bVector = new VectorWritable();
    // for each row of matrix B
    while (reader.next(bKey, bVector)) {
      matrixB.add(bVector.getVector());

      if (m_isDebuggingEnabled) {
        m_logger.writeChars("bsp,setup,MatrixB (" + bKey.get() + ","
            + bVector.getVector() + ")\n");
      }
    }
    reader.close();

    // Convert matrixB to double array for GPU kernels
    m_matrixBArr = toArray(matrixB);

    if (m_isDebuggingEnabled) {
      for (int i = 0; i < m_matrixBArr.length; i++) {
        m_logger.writeChars("bsp,setup,MatrixBArr (" + i + ","
            + Arrays.toString(m_matrixBArr[i]) + ")\n");
      }
    }

    // threadSliceSize defines how much multipliers
    // of column B has to be multiplied with column A
    m_threadSliceSize = divup(m_matrixBArr.length, m_blockSize);

    // blockSliceSize defines the column slice amount
    // columns of B per blockIters
    m_blockSliceSize = divup(m_matrixBArr[0].length, m_gridSize);

    if (m_isDebuggingEnabled) {
      m_logger.writeChars("bsp,setup,blockSize=" + m_blockSize + ",gridSize="
          + m_gridSize + ",threadSliceSize=" + m_threadSliceSize
          + ",blockSliceSize=" + m_blockSliceSize + "\n");
    }
  }

  @Override
  public void bsp(
      BSPPeer<IntWritable, VectorWritable, IntWritable, VectorWritable, NullWritable> peer)
      throws IOException, SyncException, InterruptedException {

    // Collect all rows of matrix A which belong to this bsp task
    List<DoubleVector> matrixA = new ArrayList<DoubleVector>();
    IntWritable aKey = new IntWritable();
    VectorWritable aVector = new VectorWritable();

    while (peer.readNext(aKey, aVector)) {
      matrixA.add(aVector.getVector());

      // Logging
      if (m_isDebuggingEnabled) {
        m_logger.writeChars("bsp,input,key=" + aKey + ",value="
            + aVector.getVector().toString() + "\n");
      }
    }

    // Convert rows of matrix A to double array for GPU kernels
    double[][] matrixAArr = toArray(matrixA);
    if (m_isDebuggingEnabled) {
      for (int i = 0; i < matrixAArr.length; i++) {
        m_logger.writeChars("bsp,input,matrixAArr (" + i + ","
            + Arrays.toString(matrixAArr[i]) + ")\n");
      }
    }

    // Setup GPU Kernel
    MatrixMultiplicationBSPKernel kernel = new MatrixMultiplicationBSPKernel(
        matrixAArr, m_matrixBArr, m_threadSliceSize, m_blockSliceSize);
    Rootbeer rootbeer = new Rootbeer();
    rootbeer.setThreadConfig(m_blockSize, m_gridSize, m_blockSize * m_gridSize);

    // Run GPU Kernels
    Stopwatch watch = new Stopwatch();
    watch.start();
    rootbeer.runAll(kernel);
    watch.stop();

    // DEBUG information of GPU run
    List<StatsRow> stats = rootbeer.getStats();
    for (StatsRow row : stats) {
      System.out.println("  StatsRow:\n");
      System.out.println("    init time: " + row.getInitTime() + "\n");
      System.out.println("    serial time: " + row.getSerializationTime()
          + "\n");
      System.out.println("    exec time: " + row.getExecutionTime() + "\n");
      System.out.println("    deserial time: " + row.getDeserializationTime()
          + "\n");
      System.out.println("    num blocks: " + row.getNumBlocks() + "\n");
      System.out.println("    num threads: " + row.getNumThreads() + "\n");
    }

    if (m_isDebuggingEnabled) {
      m_logger.writeChars("bsp,GPUTime=" + watch.elapsedTimeMillis() + "ms\n");
      m_logger.flush();
    }

    // Get GPU results
    double[][] matrixC = kernel.resultMatrix.matrix;

    peer.sync();

    // MasterTask write out result
    if (peer.getPeerName().equals(m_masterTask)) {

      for (int rowIndex = 0; rowIndex < matrixC.length; rowIndex++) {

        // Build row vector
        DenseDoubleVector rowVector = new DenseDoubleVector(matrixC[rowIndex]);

        if (m_isDebuggingEnabled) {
          m_logger.writeChars("bsp,write,key=" + rowIndex + ",value="
              + rowVector.toString() + "\n");
        }
        // Write out row
        peer.write(new IntWritable(rowIndex), new VectorWritable(rowVector));
      }

    }
  }

  private double[][] toArray(List<DoubleVector> vectors) {
    double[][] matrixArr = null;

    if (vectors.size() > 0) {

      int i = 0;
      for (DoubleVector v : vectors) {

        if (matrixArr == null) {
          matrixArr = new double[vectors.size()][v.getDimension()];
        }

        for (int j = 0; j < v.getDimension(); j++) {
          matrixArr[i][j] = v.get(j);
        }

        i++;
      }
    }
    return matrixArr;
  }

  static int divup(int x, int y) {
    if (x % y != 0) {
      // round up
      return ((x + y - 1) / y);
    } else {
      return x / y;
    }
  }

  static void printOutput(Configuration conf) throws IOException {
    FileSystem fs = OUTPUT_DIR.getFileSystem(conf);
    FileStatus[] files = fs.listStatus(OUTPUT_DIR);
    for (int i = 0; i < files.length; i++) {
      if (files[i].getLen() > 0) {
        if (files[i].getPath().getName().endsWith(".log")) {
          System.out.println("File " + files[i].getPath());
          FSDataInputStream in = fs.open(files[i].getPath());
          IOUtils.copyBytes(in, System.out, conf, false);
          in.close();
        }
      }
    }
    // fs.delete(FileOutputFormat.getOutputPath(job), true);
  }

  public static BSPJob createMatrixMultiplicationBSPGpuConf(Path aPath,
      Path bPath, Path outPath) throws IOException {

    return createMatrixMultiplicationBSPGpuConf(new HamaConfiguration(), aPath,
        bPath, outPath);
  }

  public static BSPJob createMatrixMultiplicationBSPGpuConf(Configuration conf,
      Path aPath, Path bPath, Path outPath) throws IOException {

    BSPJob job = new BSPJob(new HamaConfiguration(conf));
    // Set the job name
    job.setJobName("MatrixMultiplicationBSP GPU");
    // set the BSP class which shall be executed
    job.setBspClass(MatrixMultiplicationBSPGpuNew.class);
    // help Hama to locale the jar to be distributed
    job.setJarByClass(MatrixMultiplicationBSPGpuNew.class);

    job.setInputFormat(SequenceFileInputFormat.class);
    job.setInputPath(aPath);

    job.setOutputFormat(SequenceFileOutputFormat.class);
    job.setOutputKeyClass(IntWritable.class);
    job.setOutputValueClass(VectorWritable.class);
    job.setOutputPath(outPath);

    job.set(CONF_MATRIX_MULT_B_PATH, bPath.toString());
    job.set("bsp.child.java.opts", "-Xmx4G");

    // Order message by row index
    // job.set(MessageManager.QUEUE_TYPE_CLASS,
    // "org.apache.hama.bsp.message.queue.SortedMessageQueue");

    LOG.info("DEBUG: NumBspTask: " + job.getNumBspTask());
    LOG.info("DEBUG: bsp.job.split.file: " + job.get("bsp.job.split.file"));
    LOG.info("DEBUG: bsp.tasks.maximum: " + job.get("bsp.tasks.maximum"));
    LOG.info("DEBUG: bsp.input.dir: " + job.get("bsp.input.dir"));

    return job;
  }

  public static void main(String[] args) throws Exception {

    // Defaults
    int numRowsA = 1024;
    int numColsA = 1024;
    int numRowsB = 1024;
    int numColsB = 1024;
    boolean isDebugging = false;

    Configuration conf = new HamaConfiguration();

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
      conf.setInt("bsp.peers.num", 1); // 1 because only one GPU available
    }

    conf.setBoolean(CONF_DEBUG, isDebugging);
    conf.set(CONF_BLOCKSIZE, "" + BLOCK_SIZE);
    conf.set(CONF_GRIDSIZE, "" + GRID_SIZE);
    conf.setBoolean(CONF_DEBUG, true);

    LOG.info("NumBspTask: " + conf.getInt("bsp.peers.num", 0));
    LOG.info("numRowsA: " + numRowsA);
    LOG.info("numColsA: " + numColsA);
    LOG.info("numRowsB: " + numRowsB);
    LOG.info("numColsB: " + numColsB);
    LOG.info("isDebugging: " + isDebugging);
    LOG.info("outputPath: " + OUTPUT_DIR);

    if (numColsA != numRowsB) {
      throw new Exception("Cols of MatrixA != rows of MatrixB! (" + numColsA
          + "!=" + numRowsB + ")");
    }

    // Create random DistributedRowMatrix
    // use constant seeds to get reproducable results

    // Matrix A
    DistributedRowMatrix.createRandomDistributedRowMatrix(conf, numRowsA,
        numColsA, new Random(42L), MATRIX_A_PATH, false);
    // Matrix B
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
    DistributedRowMatrix c = a.multiplyBSP(b, MATRIX_C_PATH, true);

    System.out.println("MatrixMultiplicationGpu using Hama finished in "
        + (System.currentTimeMillis() - startTime) / 1000.0 + " seconds");

    // Verification
    DistributedRowMatrix d = a.multiplyJava(b, MATRIX_D_PATH);
    if (c.verify(d)) {
      System.out.println("Verify PASSED!");
    } else {
      System.out.println("Verify FAILED!");
    }

    if (isDebugging) {
      System.out.println("Matrix A:");
      a.printDistributedRowMatrix();
      System.out.println("Matrix B:");
      b.printDistributedRowMatrix();
      System.out.println("Matrix C:");
      c.printDistributedRowMatrix();
      System.out.println("Matrix D:");
      d.printDistributedRowMatrix();

      printOutput(conf);
    }
  }
}
