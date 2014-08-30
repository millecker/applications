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
package at.illecker.hadoop.rootbeer.examples.matrixmultiplication.gpu;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.SequenceFileInputFormat;
import org.apache.hadoop.mapred.SequenceFileOutputFormat;
import org.apache.hadoop.mapred.join.CompositeInputFormat;
import org.apache.hadoop.mapred.join.TupleWritable;
import org.apache.hadoop.util.ToolRunner;
import org.apache.mahout.common.AbstractJob;
import org.apache.mahout.math.CardinalityException;
import org.apache.mahout.math.DenseVector;
import org.apache.mahout.math.Vector;
import org.apache.mahout.math.VectorWritable;
import org.trifort.rootbeer.runtime.Context;
import org.trifort.rootbeer.runtime.Kernel;
import org.trifort.rootbeer.runtime.Rootbeer;
import org.trifort.rootbeer.runtime.StatsRow;
import org.trifort.rootbeer.runtime.ThreadConfig;
import org.trifort.rootbeer.runtime.util.Stopwatch;

import at.illecker.hadoop.rootbeer.examples.matrixmultiplication.DistributedRowMatrix;

public class MatrixMultiplicationGpu extends AbstractJob {

  private static final Log LOG = LogFactory
      .getLog(MatrixMultiplicationGpu.class);

  private static final String CONF_OUT_CARD = "matrixmultiplication.gpu.output.vector.cardinality";
  private static final String CONF_DEBUG = "matrixmultiplication.gpu.debug";
  private static final String CONF_TILE_WIDTH = "matrixmultiplication.gpu.tilewidth";

  private static final Path OUTPUT_DIR = new Path(
      "output/hadoop/rootbeer/examples/matrixmultiplication/GPU-"
          + System.currentTimeMillis());
  private static final Path MATRIX_A_PATH = new Path(
      "input/hadoop/rootbeer/examples/MatrixA.seq");
  private static final Path MATRIX_A_TRANSPOSED_PATH = new Path(
      "input/hadoop/rootbeer/examples/MatrixA_transposed.seq");
  private static final Path MATRIX_B_PATH = new Path(
      "input/hadoop/rootbeer/examples/MatrixB.seq");
  private static final Path MATRIX_C_PATH = new Path(OUTPUT_DIR
      + "/MatrixC.seq");
  private static final Path MATRIX_D_PATH = new Path(OUTPUT_DIR
      + "/MatrixD.seq");

  public static class MatrixMultiplyGpuMapper extends MapReduceBase implements
      Mapper<IntWritable, TupleWritable, IntWritable, VectorWritable> {

    private int m_outCardinality;
    private boolean m_isDebuggingEnabled;
    private FSDataOutputStream m_logMapper;

    private int m_tileWidth;
    private List<Vector> m_tranposedMatrixA = new ArrayList<Vector>();
    private List<Vector> m_matrixB = new ArrayList<Vector>();

    // New Hadoop API would provide a context object to write results
    // Mapper.cleanup(Context)
    // To submit data in the close method we need a
    // reference to OutputCollector;
    OutputCollector<IntWritable, VectorWritable> out;

    @Override
    public void configure(JobConf conf) {

      m_outCardinality = conf.getInt(CONF_OUT_CARD, Integer.MAX_VALUE);
      m_tileWidth = conf.getInt(CONF_TILE_WIDTH, 32);
      m_isDebuggingEnabled = conf.getBoolean(CONF_DEBUG, false);

      // Set user.home to jars dir for .rootbeer folder
      // which includes CUDA lib
      System.setProperty("user.home", new Path(conf.getJobLocalDir())
          .getParent().toString() + File.separator + "jars");

      // Init logging
      if (m_isDebuggingEnabled) {
        try {
          FileSystem fs = FileSystem.get(conf);
          m_logMapper = fs.create(new Path(FileOutputFormat.getOutputPath(conf)
              .getParent() + "/Mapper_" + conf.get("mapred.job.id") + ".log"));

          m_logMapper.writeChars("map,configure,user.home="
              + System.getProperty("user.home") + "\n");

          m_logMapper.writeChars("map,configure,NumMapTasks="
              + conf.getNumMapTasks() + "\n");

          m_logMapper.writeChars("map,configure,outCardinality="
              + m_outCardinality + "\n");
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }

    @Override
    public void map(IntWritable index, TupleWritable v,
        OutputCollector<IntWritable, VectorWritable> out, Reporter reporter)
        throws IOException {

      // Set OutputCollector reference, for close method
      this.out = out;

      // Logging
      if (m_isDebuggingEnabled) {
        for (int i = 0; i < v.size(); i++) {
          Vector vector = ((VectorWritable) v.get(i)).get();
          m_logMapper.writeChars("map,input,key=" + index + ",value="
              + vector.toString() + "\n");
        }
      }

      Vector firstVector = ((VectorWritable) v.get(0)).get();
      Vector secondVector = ((VectorWritable) v.get(1)).get();

      // outCardinality is resulting column size l
      // (n x m) * (m x l) = (n x l)
      boolean firstIsOutFrag = secondVector.size() == m_outCardinality;

      // outFrag is Matrix which has the resulting column cardinality
      // (matrixB)
      Vector outFrag = firstIsOutFrag ? secondVector : firstVector;

      // multiplier is Matrix which has the resulting row count
      // (transposed matrixA)
      Vector multiplier = firstIsOutFrag ? firstVector : secondVector;

      if (m_isDebuggingEnabled) {
        m_logMapper.writeChars("map,firstIsOutFrag=" + firstIsOutFrag + "\n");
        m_logMapper.writeChars("map,outFrag=" + outFrag + "\n");
        m_logMapper.writeChars("map,multiplier=" + multiplier + "\n");
      }

      m_tranposedMatrixA.add(outFrag);
      m_matrixB.add(multiplier);
    }

    @Override
    public void close() throws IOException {
      // After last input key/value convert data and run GPU kernel

      // m - rows of transposed matrix A and rows of matrix B
      int m = m_tranposedMatrixA.size();
      // n - cols of transposed matrix A
      int n = m_tranposedMatrixA.get(0).size();
      // l - cols of matrix B
      int l = m_matrixB.get(0).size();

      double[] transposedmatrixA = new double[m * n];
      double[] matrixB = new double[m * l];
      double[] matrixC = new double[n * l];

      // Convert transposedMatrixA List<Vector> to double[] in row-wise order
      for (int i = 0; i < m; i++) {
        int j = 0;
        // m_logMapper.writeChars("map,close,transposedMatrixA[" + i + "]: ");
        for (Vector.Element e : m_tranposedMatrixA.get(i).all()) {
          transposedmatrixA[(i * n) + j] = e.get();
          j++;
          // m_logMapper.writeChars(e.get() + ",");
        }
        // m_logMapper.writeChars("\n");
      }

      // Convert matrixB List<Vector> to double[] in row-wise order
      for (int i = 0; i < m; i++) {
        int j = 0;
        // m_logMapper.writeChars("map,close,matrixB[" + i + "]: ");
        for (Vector.Element e : m_matrixB.get(i).all()) {
          matrixB[(i * l) + j] = e.get();
          j++;
          // m_logMapper.writeChars(e.get() + ",");
        }
        // m_logMapper.writeChars("\n");
      }

      int subMatrixSize = m_tileWidth * m_tileWidth;
      int numberOfSubMatrices = divup(n * l, subMatrixSize);
      int gridSize = numberOfSubMatrices;
      int blockSize = subMatrixSize;

      // int subMatrixSize = tileWidth * tileWidth;
      // rows of A and cols of B per block
      int subMatricesPerThread = divup(m, m_tileWidth);

      if (m_isDebuggingEnabled) {
        m_logMapper.writeChars("map,close,tileWidth: " + m_tileWidth + "\n");
        m_logMapper.writeChars("map,close,gridSize: " + gridSize + "\n");
        m_logMapper.writeChars("map,close,blockSize: " + blockSize + "\n");
        m_logMapper.writeChars("map,close,n: " + n + "\n");
        m_logMapper.writeChars("map,close,m: " + m + "\n");
        m_logMapper.writeChars("map,close,l: " + l + "\n");
        m_logMapper.writeChars("map,close,subMatricesPerThread: "
            + subMatricesPerThread + "\n");
      }

      Kernel kernel = new MatrixMultiplicationMapperKernel(transposedmatrixA,
          matrixB, matrixC, n, m, l, gridSize, blockSize, m_tileWidth,
          subMatricesPerThread);

      // Run GPU kernel
      Rootbeer rootbeer = new Rootbeer();
      Context context = rootbeer.createDefaultContext();
      Stopwatch watch = new Stopwatch();
      watch.start();
      rootbeer.run(kernel, new ThreadConfig(blockSize, gridSize, blockSize
          * gridSize), context);
      watch.stop();

      if (m_isDebuggingEnabled) {
        List<StatsRow> stats = context.getStats();
        for (StatsRow row : stats) {
          m_logMapper.writeChars("  StatsRow:\n");
          m_logMapper.writeChars("    serial time: "
              + row.getSerializationTime() + "\n");
          m_logMapper.writeChars("    exec time: " + row.getExecutionTime()
              + "\n");
          m_logMapper.writeChars("    deserial time: "
              + row.getDeserializationTime() + "\n");
          m_logMapper
              .writeChars("    num blocks: " + row.getNumBlocks() + "\n");
          m_logMapper.writeChars("    num threads: " + row.getNumThreads()
              + "\n");
        }
        m_logMapper.writeChars("map,close,GPUTime=" + watch.elapsedTimeMillis()
            + "ms\n");
        m_logMapper.writeChars("map,close,blockSize=" + blockSize
            + ",gridSize=" + gridSize + "\n");
        m_logMapper.flush();
      }

      // Collect results of GPU kernels
      double[] resultRow = new double[l];
      for (int i = 0; i < n; i++) {
        for (int j = 0; j < l; j++) {
          resultRow[j] = matrixC[(j * l) + i]; // submit in col-wise order
        }
        if (m_isDebuggingEnabled) {
          m_logMapper.writeChars("map,close,resultRow[" + i + "]="
              + Arrays.toString(resultRow) + "\n");
        }
        out.collect(new IntWritable(i), new VectorWritable(new DenseVector(
            resultRow)));
      }
    }

    private int divup(int x, int y) {
      if (x % y != 0) {
        return ((x + y - 1) / y); // round up
      } else {
        return x / y;
      }
    }
  }

  public static Configuration createMatrixMultiplicationGpuConf(Path aPath,
      Path bPath, Path outPath, int outCardinality, int tileWidth,
      boolean isDebugging) {

    return createMatrixMultiplicationGpuConf(new Configuration(), aPath, bPath,
        outPath, outCardinality, tileWidth, isDebugging);
  }

  public static Configuration createMatrixMultiplicationGpuConf(
      Configuration initialConf, Path aPath, Path bPath, Path outPath,
      int outCardinality, int tileWidth, boolean isDebugging) {

    JobConf conf = new JobConf(initialConf, MatrixMultiplicationGpu.class);
    conf.setJobName("MatrixMultiplicationGPU: " + aPath + " x " + bPath + " = "
        + outPath);

    conf.setInt(CONF_OUT_CARD, outCardinality);
    conf.setInt(CONF_TILE_WIDTH, tileWidth);
    conf.setBoolean(CONF_DEBUG, isDebugging);

    conf.setInputFormat(CompositeInputFormat.class);
    conf.set("mapred.join.expr", CompositeInputFormat.compose("inner",
        SequenceFileInputFormat.class, aPath, bPath));

    conf.setOutputFormat(SequenceFileOutputFormat.class);
    FileOutputFormat.setOutputPath(conf, outPath);

    conf.setMapperClass(MatrixMultiplyGpuMapper.class);

    conf.setMapOutputKeyClass(IntWritable.class);
    conf.setMapOutputValueClass(VectorWritable.class);

    conf.setOutputKeyClass(IntWritable.class);
    conf.setOutputValueClass(VectorWritable.class);

    // Increase client heap size for GPU Rootbeer execution
    conf.set("mapred.child.java.opts", "-Xms8G -Xmx8G");

    // No Reduce step is needed
    // -> 0 reducer means reduce step will be skipped and
    // mapper output will be the final out
    // -> Identity reducer means then shuffling/sorting will still take place
    conf.setNumReduceTasks(0);

    return conf;
  }

  public static void main(String[] args) throws Exception {
    ToolRunner.run(new MatrixMultiplicationGpu(), args);
  }

  @Override
  public int run(String[] strings) throws Exception {
    addOption("numRowsA", "nra", "Number of rows of the first input matrix",
        true);
    addOption("numColsA", "nca", "Number of columns of the first input matrix",
        true);
    addOption("numRowsB", "nrb", "Number of rows of the second input matrix",
        true);
    addOption("numColsB", "ncb",
        "Number of columns of the second input matrix", true);
    addOption("tileWidth", "tw", "TileWidth denotes the size of a submatrix",
        true);
    addOption("debug", "db", "Enable debugging (true|false)", false);

    Map<String, List<String>> argMap = parseArguments(strings);
    if (argMap == null) {
      return -1;
    }

    int numRowsA = Integer.parseInt(getOption("numRowsA"));
    int numColsA = Integer.parseInt(getOption("numColsA"));
    int numRowsB = Integer.parseInt(getOption("numRowsB"));
    int numColsB = Integer.parseInt(getOption("numColsB"));

    // TILE_WITH = 32
    // --> 2 * 32 = 1024 threads matches the blocksize
    int tileWidth = Integer.parseInt(getOption("tileWidth"));
    boolean isDebugging = Boolean.parseBoolean(getOption("debug"));

    LOG.info("numRowsA: " + numRowsA);
    LOG.info("numColsA: " + numColsA);
    LOG.info("numRowsB: " + numRowsB);
    LOG.info("numColsB: " + numColsB);
    LOG.info("tileWidth: " + tileWidth);
    LOG.info("isDebugging: " + isDebugging);
    LOG.info("outputPath: " + OUTPUT_DIR);

    if (numColsA != numRowsB) {
      throw new CardinalityException(numColsA, numRowsB);
    }

    Configuration conf = new Configuration(getConf());

    // Create random DistributedRowMatrix
    // use constant seeds to get reproducable results
    // Matrix A is stored transposed
    DistributedRowMatrix.createRandomDistributedRowMatrix(conf, numRowsA,
        numColsA, new Random(42L), MATRIX_A_TRANSPOSED_PATH, true);
    DistributedRowMatrix.createRandomDistributedRowMatrix(conf, numRowsB,
        numColsB, new Random(1337L), MATRIX_B_PATH, false);

    // Load DistributedRowMatrix a and b
    DistributedRowMatrix aTransposed = new DistributedRowMatrix(
        MATRIX_A_TRANSPOSED_PATH, OUTPUT_DIR, numRowsA, numColsA);
    aTransposed.setConf(conf);

    DistributedRowMatrix b = new DistributedRowMatrix(MATRIX_B_PATH,
        OUTPUT_DIR, numRowsB, numColsB);
    b.setConf(conf);

    // MatrixMultiply all within a new MapReduce job
    long startTime = System.currentTimeMillis();
    DistributedRowMatrix c = aTransposed.multiplyMapReduce(b, MATRIX_C_PATH,
        true, true, tileWidth, isDebugging);
    System.out.println("MatrixMultiplicationGpu using Hadoop finished in "
        + (System.currentTimeMillis() - startTime) / 1000.0 + " seconds");

    // Verification
    // Overwrite matrix A, NOT transposed for verification check
    DistributedRowMatrix.createRandomDistributedRowMatrix(conf, numRowsA,
        numColsA, new Random(42L), MATRIX_A_PATH, false);
    DistributedRowMatrix a = new DistributedRowMatrix(MATRIX_A_PATH,
        OUTPUT_DIR, numRowsA, numColsA);
    a.setConf(conf);

    DistributedRowMatrix d = a.multiplyJava(b, MATRIX_D_PATH);
    if (c.verify(d)) {
      System.out.println("Verify PASSED!");
    } else {
      System.out.println("Verify FAILED!");
    }

    if (isDebugging) {
      System.out.println("Matrix A:");
      a.printDistributedRowMatrix();
      System.out.println("Matrix A transposed:");
      aTransposed.printDistributedRowMatrix();
      System.out.println("Matrix B:");
      b.printDistributedRowMatrix();
      System.out.println("Matrix C:");
      c.printDistributedRowMatrix();
      System.out.println("Matrix D:");
      d.printDistributedRowMatrix();

      printOutput(conf);
    }
    return 0;
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
}
