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
import org.apache.hama.bsp.BSPPeer;
import org.apache.hama.bsp.FileOutputFormat;
import org.apache.hama.bsp.SequenceFileInputFormat;
import org.apache.hama.bsp.SequenceFileOutputFormat;
import org.apache.hama.bsp.gpu.HybridBSP;
import org.apache.hama.bsp.message.MessageManager;
import org.apache.hama.bsp.sync.SyncException;
import org.apache.hama.commons.io.VectorWritable;
import org.apache.hama.commons.math.DenseDoubleVector;
import org.apache.hama.commons.math.DoubleVector;
import org.apache.hama.commons.util.KeyValuePair;
import org.trifort.rootbeer.runtime.Context;
import org.trifort.rootbeer.runtime.Rootbeer;
import org.trifort.rootbeer.runtime.StatsRow;
import org.trifort.rootbeer.runtime.ThreadConfig;
import org.trifort.rootbeer.runtime.util.Stopwatch;

public class MatrixMultiplicationHybridBSP
    extends
    HybridBSP<IntWritable, VectorWritable, IntWritable, VectorWritable, MatrixRowMessage> {

  private static final Log LOG = LogFactory
      .getLog(MatrixMultiplicationHybridBSP.class);

  public static final String CONF_DEBUG = "matrixmultiplication.hybrid.debug";
  public static final String CONF_MATRIX_B_PATH = "matrixmultiplication.hybrid.B.path";
  private static final String CONF_TILE_WIDTH = "matrixmultiplication.hybrid.tilewidth";

  private static final Path CONF_TMP_DIR = new Path(
      "output/hama/hybrid/examples/kmeans/hybrid-" + System.currentTimeMillis());
  private static final Path CONF_INPUT_DIR = new Path(CONF_TMP_DIR, "input");
  private static final Path CONF_OUTPUT_DIR = new Path(CONF_TMP_DIR, "output");

  private static final Path MATRIX_A_PATH = new Path(CONF_INPUT_DIR
      + "/MatrixA.seq");
  private static final Path MATRIX_B_PATH = new Path(CONF_INPUT_DIR
      + "/MatrixB.seq");
  private static final Path MATRIX_B_TRANSPOSED_PATH = new Path(CONF_INPUT_DIR
      + "/transposedMatrixB.seq");
  private static final Path MATRIX_C_PATH = new Path(CONF_OUTPUT_DIR
      + "/MatrixC.seq");
  private static final Path MATRIX_D_PATH = new Path(CONF_OUTPUT_DIR
      + "/MatrixD.seq");

  private boolean m_isDebuggingEnabled;
  private FSDataOutputStream m_logger;
  private String m_masterTask;
  private List<KeyValuePair<Integer, DoubleVector>> m_transposedMatrixB = new ArrayList<KeyValuePair<Integer, DoubleVector>>();
  private int m_tileWidth;

  /********************************* CPU *********************************/
  @Override
  public void setup(
      BSPPeer<IntWritable, VectorWritable, IntWritable, VectorWritable, MatrixRowMessage> peer)
      throws IOException {

    HamaConfiguration conf = peer.getConfiguration();
    this.m_isDebuggingEnabled = conf.getBoolean(CONF_DEBUG, false);

    // used by GPU only
    m_tileWidth = conf.getInt(CONF_TILE_WIDTH, 32);

    // Choose one as a master, who sorts the matrix rows at the end
    // this.m_masterTask = peer.getPeerName(peer.getNumPeers() / 2);
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
        new Path(conf.get(CONF_MATRIX_B_PATH)), conf);

    IntWritable transposedMatrixBRowId = new IntWritable();
    VectorWritable transposedMatrixBRow = new VectorWritable();

    // for each col of matrix B (cause by transposed B)
    while (reader.next(transposedMatrixBRowId, transposedMatrixBRow)) {
      m_transposedMatrixB.add(new KeyValuePair<Integer, DoubleVector>(
          transposedMatrixBRowId.get(), transposedMatrixBRow.getVector()));
      if (m_isDebuggingEnabled) {
        m_logger.writeChars("setup,read,transposedMatrixB,key="
            + transposedMatrixBRowId.get() + ",value="
            + transposedMatrixBRow.getVector().toString() + "\n");
      }
    }
    reader.close();
  }

  @Override
  public void bsp(
      BSPPeer<IntWritable, VectorWritable, IntWritable, VectorWritable, MatrixRowMessage> peer)
      throws IOException, SyncException, InterruptedException {

    IntWritable matrixARowId = new IntWritable();
    VectorWritable matrixARow = new VectorWritable();
    // while for each row of matrix A
    while (peer.readNext(matrixARowId, matrixARow)) {

      // Logging
      if (m_isDebuggingEnabled) {
        m_logger.writeChars("bsp,input,key=" + matrixARowId + ",value="
            + matrixARow.getVector().toString() + "\n");
      }

      DenseDoubleVector outVector = null;
      // for each column of matrix B (cause by transposed matrix B)
      for (KeyValuePair<Integer, DoubleVector> bVectorRow : m_transposedMatrixB) {
        if (outVector == null) { // init outVector only once
          outVector = new DenseDoubleVector(bVectorRow.getValue()
              .getDimension());
        }
        double dot = matrixARow.getVector().dot(bVectorRow.getValue());

        outVector.set(bVectorRow.getKey(), dot);
      }

      peer.send(m_masterTask, new MatrixRowMessage(matrixARowId.get(),
          outVector));

      if (m_isDebuggingEnabled) {
        m_logger.writeChars("bsp,send,key=" + matrixARowId.get() + ",value="
            + outVector.toString() + "\n");
        m_logger.flush();
      }
    }

    peer.sync();

    // the master task writes out the incoming messages
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
        peer.write(new IntWritable(rowIndex), new VectorWritable(rowValues));
      }
    }
  }

  /********************************* GPU *********************************/
  @Override
  public void setupGpu(
      BSPPeer<IntWritable, VectorWritable, IntWritable, VectorWritable, MatrixRowMessage> peer)
      throws IOException, SyncException, InterruptedException {

    setup(peer); // use CPU implementation
  }

  @Override
  public void bspGpu(
      BSPPeer<IntWritable, VectorWritable, IntWritable, VectorWritable, MatrixRowMessage> peer,
      Rootbeer rootbeer) throws IOException, SyncException,
      InterruptedException {

    // Fetch inputs
    List<KeyValuePair<Integer, DoubleVector>> matrixA = new ArrayList<KeyValuePair<Integer, DoubleVector>>();
    final IntWritable matrixARowId = new IntWritable();
    final VectorWritable matrixARow = new VectorWritable();
    while (peer.readNext(matrixARowId, matrixARow)) {
      matrixA.add(new KeyValuePair<Integer, DoubleVector>(matrixARowId.get(),
          matrixARow.getVector()));
    }

    // Convert data for GPU
    // n - rows of matrix A
    int n = matrixA.size();
    // m - cols of matrix A and rows of matrix B
    int m = matrixA.get(0).getValue().getDimension();
    // l - cols of matrix B
    int l = m_transposedMatrixB.size();

    double[] transposedmatrixA = new double[m * n];
    double[] matrixB = new double[m * l];
    double[] matrixC = new double[n * l];

    // Convert matrixA rows to double[]
    int i = 0;
    for (KeyValuePair<Integer, DoubleVector> row : matrixA) {
      for (int j = 0; j < m; j++) {
        // store row column wise to get a transposed matrix A
        transposedmatrixA[(j * n) + j] = row.getValue().get(j);
      }
      i++;
    }
    // Convert transposedMatrixB rows to double[]
    i = 0;
    for (KeyValuePair<Integer, DoubleVector> row : m_transposedMatrixB) {
      for (int j = 0; j < m; j++) {
        // store row column wise to get a normal matrix B
        matrixB[(j * l) + j] = row.getValue().get(j);
      }
      i++;
    }

    // DEBUG
    if (m_isDebuggingEnabled) {
      m_logger.writeChars("transposedmatrixA: \n");
      printMatrix(transposedmatrixA, m, n);
      m_logger.writeChars("\nmatrixB:\n");
      printMatrix(matrixB, m, l);
      m_logger.writeChars("\n");
    }

    int subMatrixSize = m_tileWidth * m_tileWidth;
    int numberOfSubMatrices = divup(n * l, subMatrixSize);
    int gridSize = numberOfSubMatrices;
    int blockSize = subMatrixSize;

    // int subMatrixSize = tileWidth * tileWidth;
    // rows of A and cols of B per block
    int subMatricesPerThread = divup(m, m_tileWidth);

    if (m_isDebuggingEnabled) {
      m_logger.writeChars("map,close,tileWidth: " + m_tileWidth + "\n");
      m_logger.writeChars("map,close,gridSize: " + gridSize + "\n");
      m_logger.writeChars("map,close,blockSize: " + blockSize + "\n");
      m_logger.writeChars("map,close,n: " + n + "\n");
      m_logger.writeChars("map,close,m: " + m + "\n");
      m_logger.writeChars("map,close,l: " + l + "\n");
      m_logger.writeChars("map,close,subMatricesPerThread: "
          + subMatricesPerThread + "\n");
    }

    MatrixMultiplicationHybridKernel kernel = new MatrixMultiplicationHybridKernel(
        transposedmatrixA, matrixB, matrixC, n, m, l, gridSize, blockSize,
        m_tileWidth, subMatricesPerThread);

    // Run GPU kernel
    Context context = rootbeer.createDefaultContext();
    Stopwatch watch = new Stopwatch();
    watch.start();
    rootbeer.run(kernel, new ThreadConfig(blockSize, gridSize, blockSize
        * gridSize), context);
    watch.stop();

    if (m_isDebuggingEnabled) {
      List<StatsRow> stats = context.getStats();
      for (StatsRow row : stats) {
        m_logger.writeChars("  StatsRow:\n");
        m_logger.writeChars("    serial time: " + row.getSerializationTime()
            + "\n");
        m_logger.writeChars("    exec time: " + row.getExecutionTime() + "\n");
        m_logger.writeChars("    deserial time: "
            + row.getDeserializationTime() + "\n");
        m_logger.writeChars("    num blocks: " + row.getNumBlocks() + "\n");
        m_logger.writeChars("    num threads: " + row.getNumThreads() + "\n");
      }
      m_logger.writeChars("map,close,GPUTime=" + watch.elapsedTimeMillis()
          + "ms\n");
      m_logger.writeChars("map,close,blockSize=" + blockSize + ",gridSize="
          + gridSize + "\n");
      m_logger.flush();
    }

    // Send results of GPU kernels
    DenseDoubleVector resultRow = new DenseDoubleVector(l);
    for (int x = 0; x < n; x++) {
      for (int y = 0; y < l; y++) {
        // submit in col-wise order
        resultRow.set(y, matrixC[(x * l) + y]);
      }
    }

    peer.send(m_masterTask, new MatrixRowMessage(i, resultRow));

    if (m_isDebuggingEnabled) {
      m_logger.writeChars("bsp,send,key=" + i + ",value="
          + resultRow.toString() + "\n");
      m_logger.flush();
    }

    peer.sync();

    // the master task writes out the incoming messages
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
        peer.write(new IntWritable(rowIndex), new VectorWritable(rowValues));
      }
    }
  }

  private int divup(int x, int y) {
    if (x % y != 0) {
      return ((x + y - 1) / y); // round up
    } else {
      return x / y;
    }
  }

  private void printMatrix(double[] matrix, int n, int m) throws IOException {
    for (int i = 0; i < n; ++i) {
      for (int j = 0; j < m; ++j) {
        if (j == m - 1) {
          m_logger.writeChars(matrix[i * m + j] + "]\n");
        } else if (j == 0) {
          m_logger.writeChars("[" + matrix[i * m + j] + ",");
        } else {
          m_logger.writeChars(matrix[i * m + j] + ",");
        }
      }
    }
    System.out.println();
  }

  public static BSPJob createMatrixMultiplicationHybridBSPConf(
      Path matrixAPath, Path transposedMatrixBPath, Path matrixCPath,
      int tileWidth, boolean isDebugging) throws IOException {

    return createMatrixMultiplicationHybridBSPConf(new HamaConfiguration(),
        matrixAPath, transposedMatrixBPath, matrixCPath, tileWidth, isDebugging);
  }

  public static BSPJob createMatrixMultiplicationHybridBSPConf(
      Configuration conf, Path matrixAPath, Path transposedMatrixBPath,
      Path matrixCPath, int tileWidth, boolean isDebugging) throws IOException {

    BSPJob job = new BSPJob(new HamaConfiguration(conf));
    // Set the job name
    job.setJobName("MatrixMultiplicationHybridBSP");
    // set the BSP class which shall be executed
    job.setBspClass(MatrixMultiplicationHybridBSP.class);
    // help Hama to locale the jar to be distributed
    job.setJarByClass(MatrixMultiplicationHybridBSP.class);

    job.setInputFormat(SequenceFileInputFormat.class);
    job.setInputKeyClass(IntWritable.class);
    job.setInputValueClass(VectorWritable.class);
    job.setInputPath(matrixAPath);

    job.setOutputFormat(SequenceFileOutputFormat.class);
    job.setOutputKeyClass(IntWritable.class);
    job.setOutputValueClass(VectorWritable.class);
    job.setOutputPath(matrixCPath);

    job.setMessageClass(MatrixRowMessage.class);

    job.set("bsp.child.java.opts", "-Xms8G -Xmx8G");

    // Order message by row index
    job.set(MessageManager.RECEIVE_QUEUE_TYPE_CLASS,
        "org.apache.hama.bsp.message.queue.SortedMemoryQueue");

    job.set(CONF_MATRIX_B_PATH, transposedMatrixBPath.toString());
    job.set(CONF_TILE_WIDTH, "" + tileWidth);
    job.setBoolean(CONF_DEBUG, isDebugging);

    return job;
  }

  public static void main(String[] args) throws Exception {

    // Defaults
    int numBspTask = 1;
    int numGpuBspTask = 1;
    int numRowsA = 4;// 1024;
    int numColsA = 4;// 1024;
    int numRowsB = 4;// 1024;
    int numColsB = 4;// 1024;
    int tileWidth = 32; // 2 * 32 = 1024 threads matches the blocksize
    int GPUPercentage = 100;
    boolean isDebugging = true;

    Configuration conf = new HamaConfiguration();

    if (args.length > 0) {
      if (args.length == 9) {
        numBspTask = Integer.parseInt(args[0]);
        numGpuBspTask = Integer.parseInt(args[1]);
        numRowsA = Integer.parseInt(args[2]);
        numColsA = Integer.parseInt(args[3]);
        numRowsB = Integer.parseInt(args[4]);
        numColsB = Integer.parseInt(args[5]);
        tileWidth = Integer.parseInt(args[6]);
        GPUPercentage = Integer.parseInt(args[7]);
        isDebugging = Boolean.parseBoolean(args[8]);

      } else {
        System.out.println("Wrong argument size!");
        System.out.println("    Argument1=numBspTask");
        System.out.println("    Argument2=numGpuBspTask");
        System.out
            .println("    Argument3=numRowsA | Number of rows of the first input matrix");
        System.out
            .println("    Argument4=numColsA | Number of columns of the first input matrix");
        System.out
            .println("    Argument5=numRowsB | Number of rows of the second input matrix");
        System.out
            .println("    Argument6=numColsB | Number of columns of the second input matrix");
        System.out
            .println("    Argument7=tileWidth | TileWidth denotes the size of a submatrix");
        System.out.println("    Argument8=GPUPercentage (percentage of input)");
        System.out
            .println("    Argument9=debug | Enable debugging (true|false)");
        return;
      }
    }

    // Set config variables
    conf.setBoolean("hama.pipes.logging", false);
    // Set CPU tasks
    conf.setInt("bsp.peers.num", numBspTask);
    // Set GPU tasks
    conf.setInt("bsp.peers.gpu.num", numGpuBspTask);
    // Set GPU workload
    // conf.setInt(CONF_GPU_PERCENTAGE, GPUPercentage);

    LOG.info("NumBspTask: " + conf.getInt("bsp.peers.num", 0));
    LOG.info("NumGpuBspTask: " + conf.getInt("bsp.peers.gpu.num", 0));
    LOG.info("bsp.tasks.maximum: " + conf.get("bsp.tasks.maximum"));
    // LOG.info("GPUPercentage: " + conf.get(CONF_GPU_PERCENTAGE));
    LOG.info("numRowsA: " + numRowsA);
    LOG.info("numColsA: " + numColsA);
    LOG.info("numRowsB: " + numRowsB);
    LOG.info("numColsB: " + numColsB);
    LOG.info("isDebugging: " + isDebugging);
    LOG.info("inputPath: " + CONF_INPUT_DIR);
    LOG.info("outputPath: " + CONF_OUTPUT_DIR);

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
        numColsB, new Random(1337L), MATRIX_B_TRANSPOSED_PATH, true);

    // Load DistributedRowMatrix matrixA
    DistributedRowMatrix matrixA = new DistributedRowMatrix(MATRIX_A_PATH,
        CONF_INPUT_DIR, numRowsA, numColsA);
    matrixA.setConf(conf);
    // Load DistributedRowMatrix transposedMatrixB
    DistributedRowMatrix transposedMatrixB = new DistributedRowMatrix(
        MATRIX_B_TRANSPOSED_PATH, CONF_INPUT_DIR, numRowsB, numColsB);
    transposedMatrixB.setConf(conf);

    // Execute MatrixMultiplication BSP Job
    long startTime = System.currentTimeMillis();
    DistributedRowMatrix matrixC = matrixA.multiplyBSP(transposedMatrixB,
        MATRIX_C_PATH, tileWidth, isDebugging);

    LOG.info("MatrixMultiplicationHybrid using Hama finished in "
        + (System.currentTimeMillis() - startTime) / 1000.0 + " seconds");

    // Overwrite matrix B, NOT transposed for verification
    DistributedRowMatrix.createRandomDistributedRowMatrix(conf, numRowsB,
        numColsB, new Random(1337L), MATRIX_B_PATH, false);
    DistributedRowMatrix matrixB = new DistributedRowMatrix(MATRIX_B_PATH,
        CONF_INPUT_DIR, numRowsB, numColsB);
    matrixB.setConf(conf);

    // Verification
    DistributedRowMatrix matrixD = matrixA.multiplyJava(matrixB, MATRIX_D_PATH);
    if (matrixC.verify(matrixD)) {
      System.out.println("Verify PASSED!");
    } else {
      System.out.println("Verify FAILED!");
    }

    if (isDebugging) {
      System.out.println("Matrix A:");
      matrixA.printDistributedRowMatrix();
      System.out.println("Matrix B:");
      matrixB.printDistributedRowMatrix();
      System.out.println("TransposedMatrix B:");
      transposedMatrixB.printDistributedRowMatrix();
      // System.out.println("Matrix C:");
      // matrixC.printDistributedRowMatrix();
      System.out.println("Matrix D:");
      matrixD.printDistributedRowMatrix();
      printOutput(conf);
    }
  }

  static void printOutput(Configuration conf) throws IOException {
    FileSystem fs = CONF_OUTPUT_DIR.getFileSystem(conf);
    FileStatus[] files = fs.listStatus(CONF_OUTPUT_DIR);
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
}
