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

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configurable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hama.HamaConfiguration;
import org.apache.hama.bsp.BSPJob;
import org.apache.hama.commons.io.PipesVectorWritable;
import org.apache.hama.commons.math.DenseDoubleMatrix;
import org.apache.hama.commons.math.DenseDoubleVector;
import org.apache.hama.commons.math.DoubleVector;

public class DistributedRowMatrix implements Configurable {
  private static final Log log = LogFactory.getLog(DistributedRowMatrix.class);

  private final Path inputPath;
  private final Path outputTmpPath;
  private Configuration conf;
  private Path rowPath;
  private Path outputTmpBasePath;
  private final int numRows;
  private final int numCols;

  public DistributedRowMatrix(Path inputPath, Path outputTmpPath, int numRows,
      int numCols) {
    this(inputPath, outputTmpPath, numRows, numCols, false);
  }

  public DistributedRowMatrix(Path inputPath, Path outputTmpPath, int numRows,
      int numCols, boolean keepTempFiles) {
    this.inputPath = inputPath;
    this.outputTmpPath = outputTmpPath;
    this.numRows = numRows;
    this.numCols = numCols;
  }

  @Override
  public Configuration getConf() {
    return conf;
  }

  @Override
  public void setConf(Configuration conf) {
    this.conf = conf;
    try {
      FileSystem fs = FileSystem.get(inputPath.toUri(), conf);
      rowPath = fs.makeQualified(inputPath);
      outputTmpBasePath = fs.makeQualified(outputTmpPath);
    } catch (IOException ioe) {
      throw new IllegalStateException(ioe);
    }
  }

  public int numRows() {
    return numRows;
  }

  public int numCols() {
    return numCols;
  }

  public Path getRowPath() {
    return rowPath;
  }

  public Path getOutputTempPath() {
    return outputTmpBasePath;
  }

  public void setOutputTempPathString(String outPathString) {
    try {
      outputTmpBasePath = FileSystem.get(conf).makeQualified(
          new Path(outPathString));
    } catch (IOException ioe) {
      log.error("Unable to set outputBasePath to {}, leaving as {}"
          + outPathString + " " + outputTmpBasePath);
    }
  }

  /**
   * This implements matrix multiplication A * B using MapReduce tasks on CPU
   * 
   * @param other a DistributedRowMatrix
   * @param outPath path to write result to
   * 
   * @return a DistributedRowMatrix containing the product
   */
  public DistributedRowMatrix multiply(DistributedRowMatrix other, Path outPath)
      throws IOException, ClassNotFoundException, InterruptedException {
    return multiplyBSP(other, outPath);
  }

  /**
   * This implements matrix multiplication A * B using MapReduce tasks on CPU or
   * GPU
   * 
   * @param other a DistributedRowMatrix
   * @param outPath path to write result to
   * @param useGPU use GPU or CPU (default: false, use CPU)
   * @return a DistributedRowMatrix containing the product
   */
  public DistributedRowMatrix multiplyBSP(DistributedRowMatrix other,
      Path outPath) throws IOException, ClassNotFoundException,
      InterruptedException {
    // Check if cols of MatrixA = rows of MatrixB
    // (l x m) * (m x n) = (l x n)
    if (numCols != other.numRows()) {
      throw new IOException("Cols of MatrixA != rows of MatrixB! (" + numCols
          + "!=" + other.numRows() + ")");
    }

    Configuration initialConf = (getConf() == null) ? new HamaConfiguration()
        : getConf();

    // Debug
    // System.out.println("DistributedRowMatrix transposed:");
    // transposed.printDistributedRowMatrix();

    // Build MatrixMultiplication job configuration
    BSPJob job = MatrixMultiplicationHybridBSP
        .createMatrixMultiplicationHybridBSPConf(initialConf, this.rowPath,
            other.rowPath, outPath.getParent());

    // Multiply Matrix
    if (job.waitForCompletion(true)) {

      // Rename result file to output path
      Configuration conf = job.getConfiguration();
      FileSystem fs = outPath.getFileSystem(conf);
      FileStatus[] files = fs.listStatus(outPath.getParent());
      for (int i = 0; i < files.length; i++) {
        if ((files[i].getPath().getName().startsWith("part-"))
            && (files[i].getLen() > 97)) {
          fs.rename(files[i].getPath(), outPath);
          break;
        }
      }

      // Read resulting Matrix from HDFS
      DistributedRowMatrix out = new DistributedRowMatrix(outPath,
          outputTmpPath, this.numRows, other.numCols());
      out.setConf(conf);

      return out;
    }

    return null;
  }

  /**
   * This implements matrix multiplication A * B in Java without using MapReduce
   * tasks
   * 
   * @param other a DistributedRowMatrix
   * @param outPath path to write result to
   * 
   * @return a DistributedRowMatrix containing the product
   */
  public DistributedRowMatrix multiplyJava(DistributedRowMatrix other,
      Path outPath) throws IOException {
    // Check if cols of MatrixA = rows of MatrixB
    // (l x m) * (m x n) = (l x n)
    if (numCols != other.numRows()) {
      throw new IOException("Cols of MatrixA != rows of MatrixB! (" + numCols
          + "!=" + other.numRows() + ")");
    }

    // Multiply Matrix with transposed one without new MapReduce Job
    final double[][] matrixA = this.toDoubleArray();
    final double[][] matrixB = other.toDoubleArray();
    final double[][] matrixC = new double[this.numRows][other.numCols];

    int m = this.numRows;
    int n = this.numCols;
    int p = other.numCols;
    for (int k = 0; k < n; k++) {
      for (int i = 0; i < m; i++) {
        for (int j = 0; j < p; j++) {
          matrixC[i][j] = matrixC[i][j] + matrixA[i][k] * matrixB[k][j];
        }
      }
    }

    // Save resulting Matrix to HDFS
    try {
      writeDistributedRowMatrix(this.conf, matrixC, this.numRows,
          other.numCols, outPath, false);
    } catch (Exception e) {
      e.printStackTrace();
    }

    // Read resulting Matrix from HDFS
    DistributedRowMatrix out = new DistributedRowMatrix(outPath, outputTmpPath,
        numCols, other.numCols());
    out.setConf(conf);

    return out;
  }

  public static class MatrixEntryWritable implements
      WritableComparable<MatrixEntryWritable> {
    private int row;
    private int col;
    private double val;

    public int getRow() {
      return row;
    }

    public void setRow(int row) {
      this.row = row;
    }

    public int getCol() {
      return col;
    }

    public void setCol(int col) {
      this.col = col;
    }

    public double getVal() {
      return val;
    }

    public void setVal(double val) {
      this.val = val;
    }

    @Override
    public int compareTo(MatrixEntryWritable o) {
      if (row > o.row) {
        return 1;
      } else if (row < o.row) {
        return -1;
      } else {
        if (col > o.col) {
          return 1;
        } else if (col < o.col) {
          return -1;
        } else {
          return 0;
        }
      }
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof MatrixEntryWritable)) {
        return false;
      }
      MatrixEntryWritable other = (MatrixEntryWritable) o;
      return row == other.row && col == other.col;
    }

    @Override
    public int hashCode() {
      return row + 31 * col;
    }

    @Override
    public void write(DataOutput out) throws IOException {
      out.writeInt(row);
      out.writeInt(col);
      out.writeDouble(val);
    }

    @Override
    public void readFields(DataInput in) throws IOException {
      row = in.readInt();
      col = in.readInt();
      val = in.readDouble();
    }

    @Override
    public String toString() {
      return "(" + row + ',' + col + "):" + val;
    }
  }

  public static void createRandomDistributedRowMatrix(Configuration conf,
      int rows, int columns, Random rand, Path path, boolean saveTransposed)
      throws Exception {

    final double[][] matrix = new double[rows][columns];
    for (int i = 0; i < rows; i++) {
      for (int j = 0; j < columns; j++) {
        // matrix[i][j] = rand.nextDouble();
        matrix[i][j] = rand.nextInt(9) + 1;
      }
    }

    writeDistributedRowMatrix(conf, matrix, rows, columns, path, saveTransposed);
  }

  public static DenseDoubleMatrix readDistributedRowMatrix(Configuration conf,
      Path path) {

    // System.out.println("readDistributedRowMatrix: " + path);

    List<DoubleVector> matrix = new ArrayList<DoubleVector>();

    SequenceFile.Reader reader = null;
    try {
      FileSystem fs = FileSystem.get(conf);
      reader = new SequenceFile.Reader(fs, path, conf);

      IntWritable key = new IntWritable();
      PipesVectorWritable vector = new PipesVectorWritable();

      while (reader.next(key, vector)) {
        // System.out.println("readDistributedRowMatrix: key: " + key
        // + Arrays.toString(vector.getVector().toArray()));
        matrix.add(vector.getVector());
      }
      reader.close();

      if (matrix.size() > 0) {
        DoubleVector list[] = new DoubleVector[matrix.size()];
        DenseDoubleMatrix result = new DenseDoubleMatrix(matrix.toArray(list));
        return result;
      }
      return null;

    } catch (IOException e) {
      e.printStackTrace();
    } finally {
      if (reader != null) {
        try {
          reader.close();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }
    return null;
  }

  public static void writeDistributedRowMatrix(Configuration conf,
      double[][] matrix, int rows, int columns, Path path,
      boolean saveTransposed) {

    SequenceFile.Writer writer = null;
    try {
      FileSystem fs = FileSystem.get(conf);
      writer = new SequenceFile.Writer(fs, conf, path, IntWritable.class,
          PipesVectorWritable.class);

      if (saveTransposed) { // Transpose Matrix before saving
        double[][] transposed = new double[columns][rows];
        for (int i = 0; i < rows; i++) {
          for (int j = 0; j < columns; j++) {
            transposed[j][i] = matrix[i][j];
          }
        }
        matrix = transposed;
      }

      for (int i = 0; i < matrix.length; i++) {
        DenseDoubleVector rowVector = new DenseDoubleVector(matrix[i]);
        writer.append(new IntWritable(i), new PipesVectorWritable(rowVector));
      }

    } catch (IOException e) {
      e.printStackTrace();
    } finally {
      if (writer != null) {
        try {
          writer.close();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }
  }

  public static void printMatrix(double[][] matrix, int rows, int columns) {
    if (matrix != null) {
      System.out.println();
      for (int i = 0; i < rows; i++) {
        for (int j = 0; j < columns; j++) {
          System.out.print(matrix[i][j] + " ");
        }
        System.out.println();
      }
    }
  }

  public void printDistributedRowMatrix() {
    System.out.println("RowPath: " + this.rowPath);
    printMatrix(this.toDoubleArray(), this.numRows, this.numCols);
  }

  public DenseDoubleMatrix readDistributedRowMatrix() {
    return DistributedRowMatrix.readDistributedRowMatrix(this.conf,
        this.rowPath);
  }

  public double[][] toDoubleArray() {
    DenseDoubleMatrix matrix = this.readDistributedRowMatrix();
    if (matrix != null) {
      return matrix.getValues();
    } else {
      return null;
    }
  }

  public boolean verify(DistributedRowMatrix other) {

    DenseDoubleMatrix matrixA = this.readDistributedRowMatrix();
    DenseDoubleMatrix matrixB = other.readDistributedRowMatrix();

    if ((matrixA == null) || (matrixB == null)) {
      return false;
    }

    if ((matrixA.getRowCount() != matrixB.getRowCount())
        || (matrixA.getColumnCount() != matrixB.getColumnCount())) {
      return false;
    }

    for (int i = 0; i < matrixA.getRowCount(); i++) {

      if (!Arrays.equals(matrixA.getRow(i), matrixB.getRow(i))) {
        return false;
      }
    }
    return true;
  }
}
