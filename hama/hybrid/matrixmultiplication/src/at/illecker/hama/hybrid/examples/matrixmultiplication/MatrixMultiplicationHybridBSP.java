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
package at.illecker.hama.hybrid.examples.matrixmultiplication;

import java.io.IOException;
import java.util.ArrayList;
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
import org.apache.hadoop.io.SequenceFile;
import org.apache.hama.HamaConfiguration;
import org.apache.hama.bsp.BSPJob;
import org.apache.hama.bsp.BSPJobClient;
import org.apache.hama.bsp.BSPPeer;
import org.apache.hama.bsp.ClusterStatus;
import org.apache.hama.bsp.FileOutputFormat;
import org.apache.hama.bsp.SequenceFileInputFormat;
import org.apache.hama.bsp.SequenceFileOutputFormat;
import org.apache.hama.bsp.gpu.HybridBSP;
import org.apache.hama.bsp.message.MessageManager;
import org.apache.hama.bsp.sync.SyncException;
import org.apache.hama.commons.io.PipesVectorWritable;
import org.apache.hama.commons.math.DenseDoubleVector;
import org.apache.hama.commons.math.DoubleVector;
import org.apache.hama.commons.util.KeyValuePair;
import org.trifort.rootbeer.runtime.Context;
import org.trifort.rootbeer.runtime.Rootbeer;
import org.trifort.rootbeer.runtime.StatsRow;
import org.trifort.rootbeer.runtime.ThreadConfig;
import org.trifort.rootbeer.runtime.util.Stopwatch;

import at.illecker.hama.hybrid.examples.matrixmultiplication.util.DistributedRowMatrix;
import at.illecker.hama.hybrid.examples.matrixmultiplication.util.MatrixRowMessage;

public class MatrixMultiplicationHybridBSP
    extends
    HybridBSP<IntWritable, PipesVectorWritable, IntWritable, PipesVectorWritable, MatrixRowMessage> {

  private static final Log LOG = LogFactory
      .getLog(MatrixMultiplicationHybridBSP.class);

  public static final String CONF_DEBUG = "matrixmultiplication.hybrid.debug";
  public static final String CONF_MATRIX_MULT_B_PATH = "matrixmultiplication.hybrid.B.path";

  public static final String CONF_BLOCKSIZE = "matrixmultiplication.hybrid.blockSize";
  public static final String CONF_GRIDSIZE = "matrixmultiplication.hybrid.gridSize";
  // gridSize = amount of blocks and multiprocessors
  public static final int GRID_SIZE = 14;
  // blockSize = amount of threads
  public static final int BLOCK_SIZE = 1024;

  private static final Path OUTPUT_DIR = new Path(
      "output/hama/hybrid/examples/matrixmultiplication/Hybrid-"
          + System.currentTimeMillis());
  private static final Path MATRIX_A_PATH = new Path(
      "input/hama/hybrid/examples/MatrixA.seq");
  private static final Path MATRIX_B_PATH = new Path(
      "input/hama/hybrid/examples/MatrixB.seq");
  private static final Path MATRIX_C_PATH = new Path(OUTPUT_DIR
      + "/MatrixC.seq");
  private static final Path MATRIX_D_PATH = new Path(OUTPUT_DIR
      + "/MatrixD.seq");

  private boolean m_isDebuggingEnabled;
  private FSDataOutputStream m_logger;
  private String m_masterTask;

  private int m_gridSize;
  private int m_blockSize;

  // used by CPU
  private List<KeyValuePair<Integer, DoubleVector>> m_bColumns = new ArrayList<KeyValuePair<Integer, DoubleVector>>();

  /********************************* CPU *********************************/
  @Override
  public void setup(
      BSPPeer<IntWritable, PipesVectorWritable, IntWritable, PipesVectorWritable, MatrixRowMessage> peer)
      throws IOException {

    HamaConfiguration conf = peer.getConfiguration();
    this.m_isDebuggingEnabled = conf.getBoolean(CONF_DEBUG, false);

    // Choose one as a master, who sorts the matrix rows at the end
    // m_masterTask = peer.getPeerName(peer.getNumPeers() / 2);

    // TODO
    // task must be 0 otherwise write out does NOT work!
    this.m_masterTask = peer.getPeerName(0);

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

    // Load transposed Matrix B
    SequenceFile.Reader reader = new SequenceFile.Reader(FileSystem.get(conf),
        new Path(conf.get(CONF_MATRIX_MULT_B_PATH)), conf);

    IntWritable bKey = new IntWritable();
    PipesVectorWritable bVector = new PipesVectorWritable();

    // for each col of matrix B (cause by transposed B)
    while (reader.next(bKey, bVector)) {
      m_bColumns.add(new KeyValuePair<Integer, DoubleVector>(bKey.get(),
          bVector.getVector()));
      if (m_isDebuggingEnabled) {
        m_logger.writeChars("setup,read,transposedMatrixB,key=" + bKey.get()
            + ",value=" + bVector.getVector().toString() + "\n");
      }
    }
    reader.close();
  }

  @Override
  public void bsp(
      BSPPeer<IntWritable, PipesVectorWritable, IntWritable, PipesVectorWritable, MatrixRowMessage> peer)
      throws IOException, SyncException, InterruptedException {

    IntWritable aKey = new IntWritable();
    PipesVectorWritable aVector = new PipesVectorWritable();
    // while for each row of matrix A
    while (peer.readNext(aKey, aVector)) {

      // Logging
      if (m_isDebuggingEnabled) {
        m_logger.writeChars("bsp,input,key=" + aKey + ",value="
            + aVector.getVector().toString() + "\n");
      }

      DenseDoubleVector outVector = null;
      // for each col of matrix B (cause by transposed B)
      for (KeyValuePair<Integer, DoubleVector> bVectorRow : m_bColumns) {

        if (outVector == null) {
          outVector = new DenseDoubleVector(bVectorRow.getValue()
              .getDimension());
        }

        double dot = aVector.getVector().dot(bVectorRow.getValue());

        outVector.set(bVectorRow.getKey(), dot);
      }

      peer.send(m_masterTask, new MatrixRowMessage(aKey.get(), outVector));

      if (m_isDebuggingEnabled) {
        m_logger.writeChars("bsp,send,key=" + aKey.get() + ",value="
            + outVector.toString() + "\n");
        m_logger.flush();
      }
    }

    peer.sync();

    // MasterTask accumulates result
    if (peer.getPeerName().equals(m_masterTask)) {

      MatrixRowMessage currentMatrixRowMessage = null;

      // Collect messages
      while ((currentMatrixRowMessage = peer.getCurrentMessage()) != null) {

        int rowIndex = currentMatrixRowMessage.getRowIndex();
        DoubleVector rowValues = currentMatrixRowMessage.getRowValues();

        if (m_isDebuggingEnabled) {
          m_logger.writeChars("bsp,write,key=" + rowIndex + ",value="
              + rowValues.toString() + "\n");
        }
        peer.write(new IntWritable(rowIndex),
            new PipesVectorWritable(rowValues));
      }
    }
  }

  /********************************* GPU *********************************/
  @Override
  public void setupGpu(
      BSPPeer<IntWritable, PipesVectorWritable, IntWritable, PipesVectorWritable, MatrixRowMessage> peer)
      throws IOException, SyncException, InterruptedException {

    HamaConfiguration conf = peer.getConfiguration();
    this.m_isDebuggingEnabled = conf.getBoolean(CONF_DEBUG, false);

    // Choose one as a master, who sorts the matrix rows at the end
    // m_masterTask = peer.getPeerName(peer.getNumPeers() / 2);

    // TODO
    // task must be 0 otherwise write out does NOT work!
    this.m_masterTask = peer.getPeerName(0);

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
  }

  @Override
  public void bspGpu(
      BSPPeer<IntWritable, PipesVectorWritable, IntWritable, PipesVectorWritable, MatrixRowMessage> peer,
      Rootbeer rootbeer) throws IOException, SyncException,
      InterruptedException {

    // Logging
    if (m_isDebuggingEnabled) {
      m_logger
          .writeChars("MatrixMultiplicationHybrid.bspGpu executed on GPU!\n");
      m_logger.writeChars("MatrixMultiplicationHybrid.bspGpu blockSize: "
          + m_blockSize + " gridSize: " + m_gridSize + "\n");
    }

    MatrixMultiplicationHybridKernel kernel = new MatrixMultiplicationHybridKernel(
        peer.getConfiguration().get(CONF_MATRIX_MULT_B_PATH));

    // Run GPU Kernels
    Context context = rootbeer.createDefaultContext();
    Stopwatch watch = new Stopwatch();
    watch.start();
    // 1 Kernel within 1 Block
    // new ThreadConfig(m_blockSize, m_gridSize, m_blockSize * m_gridSize);
    rootbeer.run(kernel, new ThreadConfig(1, 1, 1), context);
    watch.stop();

    List<StatsRow> stats = context.getStats();
    for (StatsRow row : stats) {
      m_logger.writeChars("  StatsRow:\n");
      m_logger.writeChars("    serial time: " + row.getSerializationTime()
          + "\n");
      m_logger.writeChars("    exec time: " + row.getExecutionTime() + "\n");
      m_logger.writeChars("    deserial time: " + row.getDeserializationTime()
          + "\n");
      m_logger.writeChars("    num blocks: " + row.getNumBlocks() + "\n");
      m_logger.writeChars("    num threads: " + row.getNumThreads() + "\n");
    }

    m_logger.writeChars("MatrixMultiplicationHybrid,GPUTime="
        + watch.elapsedTimeMillis() + "ms\n");
    m_logger.writeChars("MatrixMultiplicationHybrid,peerName: '"
        + kernel.m_peerName + "'\n");
    m_logger.writeChars("MatrixMultiplicationHybrid,masterTask: '"
        + kernel.m_masterTask + "'\n");
    m_logger.writeChars("MatrixMultiplicationHybrid,matrixB_path: '"
        + kernel.m_matrixB_path + "'\n");
    m_logger.close();
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

  public static BSPJob createMatrixMultiplicationHybridBSPConf(Path aPath,
      Path bPath, Path outPath) throws IOException {

    return createMatrixMultiplicationHybridBSPConf(new HamaConfiguration(),
        aPath, bPath, outPath);
  }

  public static BSPJob createMatrixMultiplicationHybridBSPConf(
      Configuration conf, Path aPath, Path bPath, Path outPath)
      throws IOException {

    BSPJob job = new BSPJob(new HamaConfiguration(conf));
    // Set the job name
    job.setJobName("MatrixMultiplicationHybridBSP");
    // set the BSP class which shall be executed
    job.setBspClass(MatrixMultiplicationHybridBSP.class);
    // help Hama to locale the jar to be distributed
    job.setJarByClass(MatrixMultiplicationHybridBSP.class);

    job.setInputFormat(SequenceFileInputFormat.class);
    job.setInputKeyClass(IntWritable.class);
    job.setInputValueClass(PipesVectorWritable.class);
    job.setInputPath(aPath);

    job.setOutputFormat(SequenceFileOutputFormat.class);
    job.setOutputKeyClass(IntWritable.class);
    job.setOutputValueClass(PipesVectorWritable.class);
    job.setOutputPath(outPath);

    job.setMessageClass(MatrixRowMessage.class);

    job.set(CONF_MATRIX_MULT_B_PATH, bPath.toString());
    job.set("bsp.child.java.opts", "-Xmx4G");

    // Order message by row index
    job.set(MessageManager.RECEIVE_QUEUE_TYPE_CLASS,
        "org.apache.hama.bsp.message.queue.SortedMemoryQueue");

    LOG.info("DEBUG: NumBspTask: " + job.getNumBspTask()); // "bsp.peers.num"
    LOG.info("DEBUG: bsp.job.split.file: " + job.get("bsp.job.split.file"));
    LOG.info("DEBUG: bsp.tasks.maximum: " + job.get("bsp.tasks.maximum"));
    LOG.info("DEBUG: bsp.input.dir: " + job.get("bsp.input.dir"));
    LOG.info("DEBUG: bsp.join.expr: " + job.get("bsp.join.expr"));

    return job;
  }

  public static void main(String[] args) throws Exception {

    // Defaults
    int numRowsA = 4;// 1024;
    int numColsA = 4;// 1024;
    int numRowsB = 4;// 1024;
    int numColsB = 4;// 1024;
    boolean isDebugging = true;

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
      conf.setInt("bsp.peers.num", 1); // cluster.getMaxTasks());
      // Enable one GPU task
      conf.setInt("bsp.peers.gpu.num", 1);
    }

    conf.setBoolean("hama.pipes.logging", isDebugging);
    conf.setBoolean(CONF_DEBUG, isDebugging);
    conf.set(CONF_BLOCKSIZE, "" + BLOCK_SIZE);
    conf.set(CONF_GRIDSIZE, "" + GRID_SIZE);

    LOG.info("NumBspTask: " + conf.getInt("bsp.peers.num", 0));
    LOG.info("NumGpuBspTask: " + conf.getInt("bsp.peers.gpu.num", 0));
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
    // use constant seeds to get reproducible results

    // Matrix A
    DistributedRowMatrix.createRandomDistributedRowMatrix(conf, numRowsA,
        numColsA, new Random(42L), MATRIX_A_PATH, false);
    // Matrix B is stored transposed
    DistributedRowMatrix.createRandomDistributedRowMatrix(conf, numRowsB,
        numColsB, new Random(1337L), MATRIX_B_PATH, true);

    // Load DistributedRowMatrix a and b
    DistributedRowMatrix a = new DistributedRowMatrix(MATRIX_A_PATH,
        OUTPUT_DIR, numRowsA, numColsA);
    a.setConf(conf);

    DistributedRowMatrix b = new DistributedRowMatrix(MATRIX_B_PATH,
        OUTPUT_DIR, numRowsB, numColsB);
    b.setConf(conf);

    // MatrixMultiplication
    long startTime = System.currentTimeMillis();
    DistributedRowMatrix c = a.multiplyBSP(b, MATRIX_C_PATH);

    LOG.info("MatrixMultiplicationHybrid using Hama finished in "
        + (System.currentTimeMillis() - startTime) / 1000.0 + " seconds");

    // Verification

    // Overwrite matrix B, NOT transposed for verification
    DistributedRowMatrix.createRandomDistributedRowMatrix(conf, numRowsB,
        numColsB, new Random(1337L), MATRIX_B_PATH, false);
    b = new DistributedRowMatrix(MATRIX_B_PATH, OUTPUT_DIR, numRowsB, numColsB);
    b.setConf(conf);

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
