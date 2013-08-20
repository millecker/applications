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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
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

public class MatrixMultiplicationBSPGpuCleaned
    extends
    BSP<IntWritable, VectorWritable, IntWritable, VectorWritable, MatrixColMessage> {

  private static final Log LOG = LogFactory
      .getLog(MatrixMultiplicationBSPGpuCleaned.class);

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

  private final int BLOCK_SIZE = 256;
  private final int GRID_SIZE = 14;

  private String masterTask;

  private static final String MATRIX_MULT_B_PATH = "matrixmultiplication.bsp.B.path";
  private Map<Integer, DoubleVector> matrixB = new TreeMap<Integer, DoubleVector>();
  private double[][] matrixBArr;

  @Override
  public void setup(
      BSPPeer<IntWritable, VectorWritable, IntWritable, VectorWritable, MatrixColMessage> peer)
      throws IOException {

    // Choose one as a master, who sorts the matrix rows at the end
    this.masterTask = peer.getPeerName(peer.getNumPeers() / 2);

    Configuration conf = peer.getConfiguration();

    // Receive matrix B
    SequenceFile.Reader reader = new SequenceFile.Reader(FileSystem.get(conf),
        new Path(conf.get(MATRIX_MULT_B_PATH)), conf);

    IntWritable bKey = new IntWritable();
    VectorWritable bVector = new VectorWritable();
    // for each row of matrix B
    while (reader.next(bKey, bVector)) {
      matrixB.put(bKey.get(), bVector.getVector());
    }
    reader.close();

    // Convert matrix B to double array for GPU kernels
    matrixBArr = toArray(matrixB.values());
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
    }

    // Convert rows of matrix A to double array for GPU kernels
    double[][] matrixAArr = toArray(matrixA.values());

    int blockSize = (BLOCK_SIZE > matrixAArr[0].length) ? matrixAArr[0].length
        : BLOCK_SIZE;
    int gridSize = (GRID_SIZE > matrixBArr[0].length) ? matrixBArr[0].length
        : GRID_SIZE;

    MatrixMultiplicationBSPSliceKernelCleaned kernel = new MatrixMultiplicationBSPSliceKernelCleaned(
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

    // Get GPU results
    List<Result> resultList = kernel.resultList.getList();
    for (Result result : resultList) {

      if (result == null) {
        continue;
      }

      if (result.resultCols != null) {
        // Collect GPU results and send to masterTask
        for (int j = 0; j < result.resultCols.length; j++) {
          // Send resulting column to masterTask
          DenseDoubleVector outVector = new DenseDoubleVector(
              result.resultCols[j]);
          peer.send(masterTask, new MatrixColMessage(result.resultColsIndex[j],
              outVector));
        }
      }
    }

    peer.sync();

    // MasterTask accumulates result
    if (peer.getPeerName().equals(masterTask)) {

      Map<Integer, DoubleVector> resultCols = new TreeMap<Integer, DoubleVector>();

      // Collect messages
      MatrixColMessage currentMatrixColMessage = null;
      while ((currentMatrixColMessage = peer.getCurrentMessage()) != null) {
        resultCols.put(currentMatrixColMessage.getColIndex(),
            currentMatrixColMessage.getColValues());
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
    job.setJobName("MatrixMultiplicationBSP GPU Cleaned");
    // set the BSP class which shall be executed
    job.setBspClass(MatrixMultiplicationBSPGpuCleaned.class);
    // help Hama to locale the jar to be distributed
    job.setJarByClass(MatrixMultiplicationBSPGpuCleaned.class);

    job.setInputFormat(SequenceFileInputFormat.class);
    job.setInputPath(aPath);

    job.setOutputFormat(SequenceFileOutputFormat.class);
    job.setOutputKeyClass(IntWritable.class);
    job.setOutputValueClass(VectorWritable.class);
    job.setOutputPath(outPath);

    job.set(MATRIX_MULT_B_PATH, bPath.toString());
    job.set("bsp.child.java.opts", "-Xmx4G");

    // Order message by row index
    // job.set(MessageManager.QUEUE_TYPE_CLASS,
    // "org.apache.hama.bsp.message.queue.SortedMessageQueue");

    LOG.info("DEBUG: NumBspTask: " + job.getNumBspTask());
    LOG.info("DEBUG: bsp.job.split.file: " + job.get("bsp.job.split.file"));
    LOG.info("DEBUG: bsp.peers.num: " + job.get("bsp.peers.num"));
    LOG.info("DEBUG: bsp.tasks.maximum: " + job.get("bsp.tasks.maximum"));
    LOG.info("DEBUG: bsp.input.dir: " + job.get("bsp.input.dir"));

    return job;
  }

  public static void main(String[] args) throws Exception {

    // Defaults
    int numRowsA = 20;
    int numColsA = 20;
    int numRowsB = 20;
    int numColsB = 20;

    Configuration conf = new HamaConfiguration();
    BSPJobClient jobClient = new BSPJobClient(conf);
    ClusterStatus cluster = jobClient.getClusterStatus(true);

    if (args.length > 0) {
      if (args.length == 5) {
        conf.setInt("bsp.peers.num", Integer.parseInt(args[0]));
        numRowsA = Integer.parseInt(args[1]);
        numColsA = Integer.parseInt(args[2]);
        numRowsB = Integer.parseInt(args[3]);
        numColsB = Integer.parseInt(args[4]);

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

        return;
      }
    } else {
      conf.setInt("bsp.peers.num", cluster.getMaxTasks());
    }

    LOG.info("NumBspTask: " + conf.getInt("bsp.peers.num", 0));
    LOG.info("numRowsA: " + numRowsA);
    LOG.info("numColsA: " + numColsA);
    LOG.info("numRowsB: " + numRowsB);
    LOG.info("numColsB: " + numColsB);
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
    DistributedRowMatrix c = a.multiplyBSP(b, MATRIX_C_PATH, true, true);
    System.out.println("MatrixMultiplicationGpu using Hama finished in "
        + (System.currentTimeMillis() - startTime) / 1000.0 + " seconds");

    // Verification
    DistributedRowMatrix d = a.multiplyJava(b, MATRIX_D_PATH);
    if (c.verify(d)) {
      System.out.println("Verify PASSED!");
    } else {
      System.out.println("Verify FAILED!");

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
