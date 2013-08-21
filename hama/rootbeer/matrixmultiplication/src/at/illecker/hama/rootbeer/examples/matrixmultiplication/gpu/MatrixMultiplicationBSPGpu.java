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
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

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
import org.apache.hama.bsp.BSP;
import org.apache.hama.bsp.BSPJob;
import org.apache.hama.bsp.BSPJobClient;
import org.apache.hama.bsp.BSPPeer;
import org.apache.hama.bsp.ClusterStatus;
import org.apache.hama.bsp.FileOutputFormat;
import org.apache.hama.bsp.SequenceFileInputFormat;
import org.apache.hama.bsp.SequenceFileOutputFormat;
import org.apache.hama.bsp.sync.SyncException;
import org.apache.hama.ml.math.DenseDoubleVector;
import org.apache.hama.ml.math.DoubleVector;
import org.apache.hama.ml.writable.VectorWritable;

import at.illecker.hama.rootbeer.examples.matrixmultiplication.util.DistributedRowMatrix;
import at.illecker.hama.rootbeer.examples.matrixmultiplication.util.MatrixColMessage;
import edu.syr.pcpratts.rootbeer.runtime.Rootbeer;
import edu.syr.pcpratts.rootbeer.runtime.StatsRow;
import edu.syr.pcpratts.rootbeer.runtime.util.Stopwatch;

public class MatrixMultiplicationBSPGpu
    extends
    BSP<IntWritable, VectorWritable, IntWritable, VectorWritable, MatrixColMessage> {

  private static final Log LOG = LogFactory
      .getLog(MatrixMultiplicationBSPGpu.class);

  private static final String CONF_DEBUG = "matrixmultiplication.bsp.gpu.debug";
  public static final String CONF_MATRIX_MULT_B_PATH = "matrixmultiplication.bsp.gpu.B.path";

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

  private boolean isDebuggingEnabled;
  private FSDataOutputStream logger;
  private String masterTask;

  private Map<Integer, DoubleVector> matrixB = new TreeMap<Integer, DoubleVector>();
  private double[][] matrixBArr;

  private final int BLOCK_SIZE = 1024;
  private final int GRID_SIZE = 14;

  @Override
  public void setup(
      BSPPeer<IntWritable, VectorWritable, IntWritable, VectorWritable, MatrixColMessage> peer)
      throws IOException {

    Configuration conf = peer.getConfiguration();
    isDebuggingEnabled = conf.getBoolean(CONF_DEBUG, false);

    // Choose one as a master, who sorts the matrix rows at the end
    this.masterTask = peer.getPeerName(peer.getNumPeers() / 2);

    // Init logging
    if (isDebuggingEnabled) {
      try {
        FileSystem fs = FileSystem.get(conf);
        logger = fs.create(new Path(FileOutputFormat.getOutputPath(new BSPJob(
            (HamaConfiguration) conf)) + "/BSP_" + peer.getTaskId() + ".log"));

      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    // Receive matrix B
    SequenceFile.Reader reader = new SequenceFile.Reader(FileSystem.get(conf),
        new Path(conf.get(CONF_MATRIX_MULT_B_PATH)), conf);

    IntWritable bKey = new IntWritable();
    VectorWritable bVector = new VectorWritable();
    // for each row of matrix B
    while (reader.next(bKey, bVector)) {
      matrixB.put(bKey.get(), bVector.getVector());

      if (isDebuggingEnabled) {
        logger.writeChars("bsp,setup,MatrixB (" + bKey.get() + ","
            + bVector.getVector() + ")\n");
      }
    }
    reader.close();

    // Convert matrix B to double array for GPU kernels
    matrixBArr = toArray(matrixB.values());

    if (isDebuggingEnabled) {
      for (int i = 0; i < matrixBArr.length; i++) {
        logger.writeChars("bsp,setup,MatrixBArr (" + i + ","
            + Arrays.toString(matrixBArr[i]) + ")\n");
      }
    }
  }

  @Override
  public void bsp(
      BSPPeer<IntWritable, VectorWritable, IntWritable, VectorWritable, MatrixColMessage> peer)
      throws IOException, SyncException, InterruptedException {

    Map<Integer, DoubleVector> matrixA = new TreeMap<Integer, DoubleVector>();

    IntWritable aKey = new IntWritable();
    VectorWritable aVector = new VectorWritable();
    // Collect all rows of matrix A which belong to this bsp task
    while (peer.readNext(aKey, aVector)) {
      matrixA.put(aKey.get(), aVector.getVector());
      // Logging
      if (isDebuggingEnabled) {
        logger.writeChars("bsp,input,key=" + aKey + ",value="
            + aVector.getVector().toString() + "\n");
      }
    }

    // Convert rows of matrix A to double array for GPU kernels
    double[][] matrixAArr = toArray(matrixA.values());
    if (isDebuggingEnabled) {
      for (int i = 0; i < matrixAArr.length; i++) {
        logger.writeChars("bsp,input,matrixAArr (" + i + ","
            + Arrays.toString(matrixAArr[i]) + ")\n");
      }
    }

    int blockSize = (BLOCK_SIZE > matrixAArr[0].length) ? matrixAArr[0].length
        : BLOCK_SIZE;
    int gridSize = (GRID_SIZE > matrixBArr[0].length) ? matrixBArr[0].length
        : GRID_SIZE;

    if (isDebuggingEnabled) {
      logger.writeChars("bsp,blockSize=" + blockSize + ",gridSize=" + gridSize
          + ",threadNum=" + blockSize * gridSize + "\n");
    }

    MatrixMultiplicationBSPKernel kernel = new MatrixMultiplicationBSPKernel(
        matrixAArr, matrixBArr, blockSize, gridSize);
    Rootbeer rootbeer = new Rootbeer();
    rootbeer.setThreadConfig(blockSize, gridSize, blockSize * gridSize);

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
    if (isDebuggingEnabled) {
      logger.writeChars("bsp,GPUTime=" + watch.elapsedTimeMillis() + "ms\n");
      logger.flush();
    }

    // Get GPU results
    /*
    int i = 0;
    List<Result> resultList = kernel.resultList.getList();
    for (Result result : resultList) {

      if (result == null) {
        if (isDebuggingEnabled) {
          logger.writeChars("bsp,result[" + i + "]=NULL\n");
        }
        i++;
        continue;
      }

      if (isDebuggingEnabled) {
        logger.writeChars("bsp,result[" + i + "]=" + result.toString() + "\n");

        // DEBUG info columns of matrix B within shared memory
        if (result.bColsSharedMemIndex != null) {
          for (int k = 0; k < result.bColsSharedMemIndex.length; k++) {
            logger.writeChars("bsp,bColsSharedMemIndex[" + k + "]="
                + Arrays.toString(result.bColsSharedMemIndex[k]) + "\n");
            logger.writeChars("bsp,bColsSharedMemValues[" + k + "]="
                + Arrays.toString(result.bColsSharedMemValues[k]) + "\n");
          }
        }

        // DEBUG info thread computations
        if ((result.bColsVals != null) && (result.multipliers != null)) {
          for (int k = 0; k < result.bColsVals.length; k++) {
            for (int j = 0; j < result.multipliers.length; j++) {
              logger.writeChars("bsp,multiplier[" + j + "]="
                  + Arrays.toString(result.multipliers[j]) + "\n");
              logger.writeChars("bsp,bColsIndexes[" + k + "][" + j + "]="
                  + Arrays.toString(result.bColsIndexes[k][j]) + "\n");
              logger.writeChars("bsp,bColsVals[" + k + "][" + j + "]="
                  + Arrays.toString(result.bColsVals[k][j]) + "\n");
            }
          }
        }

        // DEBUG info threadResults within shared memory
        if (result.threadResultsSharedMemIndex != null) {
          for (int j = 0; j < result.threadResultsSharedMemIndex.length; j++) {
            logger
                .writeChars("bsp,threadResultsSharedMemIndex[" + j + "]="
                    + Arrays.toString(result.threadResultsSharedMemIndex[j])
                    + "\n");
            logger.writeChars("bsp,threadResultsSharedMemValues[" + j + "]="
                + Arrays.toString(result.threadResultsSharedMemValues[j])
                + "\n");
          }
        }
      }

      if (result.resultCols != null) {

        if (isDebuggingEnabled) {
          // DEBUG info blockResults within shared memory
          if (result.blockResultsSharedMemIndex != null) {
            for (int j = 0; j < result.blockResultsSharedMemIndex.length; j++) {
              for (int k = 0; k < result.blockResultsSharedMemIndex[0].length; k++) {
                logger.writeChars("bsp,blockResultsSharedMemIndex[blockSlize_"
                    + j + "][thread_" + k + "]="
                    + Arrays.toString(result.blockResultsSharedMemIndex[j][k])
                    + "\n");
                logger.writeChars("bsp,blockResultsSharedMemValues[blockSlize_"
                    + j + "][thread_" + k + "]="
                    + Arrays.toString(result.blockResultsSharedMemValues[j][k])
                    + "\n");
              }
            }
          }
        }

        // Collect GPU results and send to masterTask
        for (int j = 0; j < result.resultCols.length; j++) {

          if (isDebuggingEnabled) {
            logger.writeChars("bsp,resultCols[" + j + "]="
                + Arrays.toString(result.resultCols[j]) + "\n");
          }

          // Send resulting column to masterTask
          DenseDoubleVector outVector = new DenseDoubleVector(
              result.resultCols[j]);
          peer.send(masterTask, new MatrixColMessage(result.resultColsIndex[j],
              outVector));

          if (isDebuggingEnabled) {
            logger.writeChars("bsp,send,key=" + result.resultColsIndex[j]
                + ",value=" + outVector.toString() + "\n");
          }
        }

      }

      i++;
    }

    if (isDebuggingEnabled) {
      logger.writeChars("bsp,resultCount=" + i + "\n");
      logger.flush();
    }*/

    peer.sync();

    // MasterTask accumulates result
    if (peer.getPeerName().equals(masterTask)) {

      Map<Integer, DoubleVector> resultCols = new TreeMap<Integer, DoubleVector>();

      // Collect messages
      MatrixColMessage currentMatrixColMessage = null;
      while ((currentMatrixColMessage = peer.getCurrentMessage()) != null) {
        resultCols.put(currentMatrixColMessage.getColIndex(),
            currentMatrixColMessage.getColValues());
        if (isDebuggingEnabled) {
          logger.writeChars("bsp,collect,key="
              + currentMatrixColMessage.getColIndex() + ",value="
              + currentMatrixColMessage.getColValues().toString() + "\n");
        }
      }

      // Write resultMatrix out
      if (resultCols.size() > 0) {
        for (int rowIndex = 0; rowIndex < resultCols.get(0).getDimension(); rowIndex++) {

          // Build row vector
          DenseDoubleVector rowVector = new DenseDoubleVector(resultCols.size());
          int colIndex = 0;
          for (DoubleVector colVector : resultCols.values()) {
            rowVector.set(colIndex, colVector.get(rowIndex));
            colIndex++;
          }

          if (isDebuggingEnabled) {
            logger.writeChars("bsp,write,key=" + rowIndex + ",value="
                + rowVector.toString() + "\n");
          }
          // Write out row
          peer.write(new IntWritable(rowIndex), new VectorWritable(rowVector));
        }
      }
    }

  }

  private double[][] toArray(Collection<DoubleVector> vectors) {
    double[][] matrixArr = null;

    if (vectors.size() > 0) {

      int i = 0;
      for (DoubleVector v : vectors) {

        if (matrixArr == null) {
          matrixArr = new double[vectors.size()][v.getDimension()];
        }

        for (int j = 0; j < v.getDimension(); j++) {
          matrixArr[i][j] = v.get(i);
        }

        i++;
      }
    }
    return matrixArr;
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
    job.setBspClass(MatrixMultiplicationBSPGpu.class);
    // help Hama to locale the jar to be distributed
    job.setJarByClass(MatrixMultiplicationBSPGpu.class);

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
    int numRowsA = 10;
    int numColsA = 10;
    int numRowsB = 10;
    int numColsB = 10;
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

    conf.setInt("matrixmultiplication.bsp.gpu.numRowsA", numRowsA);
    conf.setInt("matrixmultiplication.bsp.gpu.numColsA", numColsA);
    conf.setInt("matrixmultiplication.bsp.gpu.numRowsB", numRowsB);
    conf.setInt("matrixmultiplication.bsp.gpu.numColsB", numRowsB);
    conf.setBoolean(CONF_DEBUG, isDebugging);

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
