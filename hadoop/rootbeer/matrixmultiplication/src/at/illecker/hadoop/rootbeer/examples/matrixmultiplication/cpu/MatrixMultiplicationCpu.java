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
package at.illecker.hadoop.rootbeer.examples.matrixmultiplication.cpu;

import java.io.IOException;
import java.util.Iterator;
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
import org.apache.hadoop.mapred.Reducer;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.SequenceFileInputFormat;
import org.apache.hadoop.mapred.SequenceFileOutputFormat;
import org.apache.hadoop.mapred.join.CompositeInputFormat;
import org.apache.hadoop.mapred.join.TupleWritable;
import org.apache.hadoop.util.ToolRunner;
import org.apache.mahout.common.AbstractJob;
import org.apache.mahout.math.CardinalityException;
import org.apache.mahout.math.RandomAccessSparseVector;
import org.apache.mahout.math.SequentialAccessSparseVector;
import org.apache.mahout.math.Vector;
import org.apache.mahout.math.VectorWritable;
import org.apache.mahout.math.function.Functions;

import at.illecker.hadoop.rootbeer.examples.matrixmultiplication.DistributedRowMatrix;

/**
 * @author MatrixMultiplication based on Mahout https://github.com/apache/mahout
 *         /blob/trunk/core/src/main/java/org/apache
 *         /mahout/math/hadoop/MatrixMultiplicationJob.java
 * 
 */

public class MatrixMultiplicationCpu extends AbstractJob {

  private static final Log LOG = LogFactory
      .getLog(MatrixMultiplicationCpu.class);

  private static final String CONF_OUT_CARD = "matrixmultiplication.cpu.output.vector.cardinality";
  private static final String CONF_DEBUG = "matrixmultiplication.cpu.debug";

  private static final Path OUTPUT_DIR = new Path(
      "output/hadoop/rootbeer/examples/matrixmultiplication/CPU-"
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

  public static class MatrixMultiplyCpuMapper extends MapReduceBase implements
      Mapper<IntWritable, TupleWritable, IntWritable, VectorWritable> {

    private int m_outCardinality;
    private boolean m_isDebuggingEnabled;
    private FSDataOutputStream m_logMapper;

    @Override
    public void configure(JobConf conf) {

      m_outCardinality = conf.getInt(CONF_OUT_CARD, Integer.MAX_VALUE);
      m_isDebuggingEnabled = conf.getBoolean(CONF_DEBUG, false);

      // Init logging
      if (m_isDebuggingEnabled) {
        try {
          FileSystem fs = FileSystem.get(conf);
          m_logMapper = fs.create(new Path(FileOutputFormat.getOutputPath(conf)
              .getParent() + "/Mapper_" + conf.get("mapred.job.id") + ".log"));

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

      // outCardinality is resulting column size n
      // (l x m) * (m x n) = (l x n)
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

      for (Vector.Element e : multiplier.nonZeroes()) {

        VectorWritable outVector = new VectorWritable();
        // Scalar Multiplication (Vector x Element)
        outVector.set(outFrag.times(e.get()));

        out.collect(new IntWritable(e.index()), outVector);

        if (m_isDebuggingEnabled) {
          m_logMapper.writeChars("map,collect,key=" + e.index() + ",value="
              + outVector.get().toString() + "\n");
        }
      }
      if (m_isDebuggingEnabled) {
        m_logMapper.flush();
      }
    }
  }

  public static class MatrixMultiplicationCpuReducer extends MapReduceBase
      implements
      Reducer<IntWritable, VectorWritable, IntWritable, VectorWritable> {

    @Override
    public void reduce(IntWritable rowNum, Iterator<VectorWritable> it,
        OutputCollector<IntWritable, VectorWritable> out, Reporter reporter)
        throws IOException {

      if (!it.hasNext()) {
        return;
      }

      Vector accumulator = new RandomAccessSparseVector(it.next().get());
      while (it.hasNext()) {
        Vector row = it.next().get();
        accumulator.assign(row, Functions.PLUS);
      }

      out.collect(rowNum, new VectorWritable(new SequentialAccessSparseVector(
          accumulator)));
    }
  }

  public static Configuration createMatrixMultiplicationCpuConf(Path aPath,
      Path bPath, Path outPath, int outCardinality, boolean isDebugging) {

    return createMatrixMultiplicationCpuConf(new Configuration(), aPath, bPath,
        outPath, outCardinality, isDebugging);
  }

  public static Configuration createMatrixMultiplicationCpuConf(
      Configuration initialConf, Path aPath, Path bPath, Path outPath,
      int outCardinality, boolean isDebugging) {

    JobConf conf = new JobConf(initialConf, MatrixMultiplicationCpu.class);
    conf.setJobName("MatrixMultiplicationCPU: " + aPath + " x " + bPath + " = "
        + outPath);

    conf.setInt(CONF_OUT_CARD, outCardinality);
    conf.setBoolean(CONF_DEBUG, isDebugging);

    conf.setInputFormat(CompositeInputFormat.class);
    conf.set("mapred.join.expr", CompositeInputFormat.compose("inner",
        SequenceFileInputFormat.class, aPath, bPath));

    conf.setOutputFormat(SequenceFileOutputFormat.class);
    FileOutputFormat.setOutputPath(conf, outPath);

    conf.setMapperClass(MatrixMultiplyCpuMapper.class);
    conf.setCombinerClass(MatrixMultiplicationCpuReducer.class);
    conf.setReducerClass(MatrixMultiplicationCpuReducer.class);

    conf.setMapOutputKeyClass(IntWritable.class);
    conf.setMapOutputValueClass(VectorWritable.class);

    conf.setOutputKeyClass(IntWritable.class);
    conf.setOutputValueClass(VectorWritable.class);

    // Increase client heap size
    conf.set("mapred.child.java.opts", "-Xms8G -Xmx8G");

    return conf;
  }

  public static void main(String[] args) throws Exception {
    ToolRunner.run(new MatrixMultiplicationCpu(), args);
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
    addOption("debug", "db", "Enable debugging (true|false)", false);

    Map<String, List<String>> argMap = parseArguments(strings);
    if (argMap == null) {
      return -1;
    }

    int numRowsA = Integer.parseInt(getOption("numRowsA"));
    int numColsA = Integer.parseInt(getOption("numColsA"));
    int numRowsB = Integer.parseInt(getOption("numRowsB"));
    int numColsB = Integer.parseInt(getOption("numColsB"));
    boolean isDebugging = Boolean.parseBoolean(getOption("debug"));

    LOG.info("numRowsA: " + numRowsA);
    LOG.info("numColsA: " + numColsA);
    LOG.info("numRowsB: " + numRowsB);
    LOG.info("numColsB: " + numColsB);
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
        false, true, 0, isDebugging);
    System.out.println("MatrixMultiplicationCpu using Hadoop finished in "
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
