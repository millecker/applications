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
package at.illecker.hama.hybrid.examples.onlinecf;

import java.io.IOException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.SequenceFile.CompressionType;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.hama.bsp.BSPJob;
import org.apache.hama.commons.io.PipesVectorWritable;
import org.apache.hama.commons.math.DenseDoubleVector;

import com.google.caliper.Benchmark;
import com.google.caliper.Param;
import com.google.caliper.api.Macrobenchmark;
import com.google.caliper.runner.CaliperMain;

public class OnlineCFHybridBenchmark extends Benchmark {

  // @Param({ "10000" })
  private int n = 5000; // users

  // @Param({ "10000" })
  private int m = 5000; // items

  // Plot 1
  @Param({ "1" })
  // , "10", "25", "50", "75", "100", "125", "150" })
  private int iteration; // amount of iterations

  // Plot 2
  // @Param({ "1", "2", "3", "4", "5", "6", "7", "8", "9", "10" })
  private int percentNonZeroValues = 10;

  @Param
  CalcType type;

  public enum CalcType {
    CPU, GPU
  };

  // Plot 2
  // maximal 4 cpu tasks and 1 gpu task
  // @Param({ "1", "2", "3", "4", "5" })
  private int bspTaskNum = 1;

  private int matrixRank = 256;
  private int skipCount = 1;

  private static final Path CONF_TMP_DIR = new Path(
      "output/hama/hybrid/examples/onlinecf/bench-"
          + System.currentTimeMillis());
  private static final Path CONF_INPUT_DIR = new Path(CONF_TMP_DIR, "input");
  private static final Path CONF_OUTPUT_DIR = new Path(CONF_TMP_DIR, "output");

  private Configuration m_conf = null;
  private boolean m_runLocally = false;
  private int m_maxTestPrefs = 10;
  private List<double[]> m_testPrefs = null;

  // gridSize = amount of blocks and multiprocessors
  public static final int GRID_SIZE = 14;
  // blockSize = amount of threads
  public static final int BLOCK_SIZE = 256; // 1024;

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
      // System.out.println("Run Benchmark local: " + m_runLocally);
    }

    // Try to load Hama configuration
    String HAMA_HOME = System.getenv("HAMA_HOME");
    String HAMA_INSTALL = System.getenv("HAMA_INSTALL");
    if ((HAMA_HOME != null) || (HAMA_INSTALL != null) && (!m_runLocally)) {
      String HAMA = ((HAMA_HOME != null) ? HAMA_HOME : HAMA_INSTALL);

      m_conf.addResource(new Path(HAMA, "conf/hama-default.xml"));
      m_conf.addResource(new Path(HAMA, "conf/hama-site.xml"));
      // System.out.println("Loaded Hama configuration from " + HAMA);
    }

    // Setup OnlineCF config variables
    m_conf.setBoolean(OnlineCFTrainHybridBSP.CONF_DEBUG, false);
    m_conf.setBoolean("hama.pipes.logging", false);

    // Set GPU blockSize and gridSize
    m_conf.set(OnlineCFTrainHybridBSP.CONF_BLOCKSIZE, "" + BLOCK_SIZE);
    m_conf.set(OnlineCFTrainHybridBSP.CONF_GRIDSIZE, "" + GRID_SIZE);

    // CPU vs GPU iterations benchmark
    // Plot 1
    int numGpuBspTask = 0;
    if (type == CalcType.GPU) {
      bspTaskNum = 1;
      numGpuBspTask = 1;
    }

    // CPU + GPU Hybrid benchmark
    // Plot 2
    // if (bspTaskNum == 5) {
    // numGpuBspTask = 1;
    // } else {
    // numGpuBspTask = 0;
    // }

    // Set CPU tasks
    m_conf.setInt("bsp.peers.num", bspTaskNum);
    // Set GPU tasks
    m_conf.setInt("bsp.peers.gpu.num", numGpuBspTask);

    m_conf.setInt(OnlineCF.CONF_ITERATION_COUNT, iteration);
    m_conf.setInt(OnlineCF.CONF_MATRIX_RANK, matrixRank);
    m_conf.setInt(OnlineCF.CONF_SKIP_COUNT, skipCount);

    // Generate input data
    Path preferencesIn = new Path(CONF_INPUT_DIR, "preferences_in.seq");
    m_testPrefs = generateRandomInputData(m_conf, FileSystem.get(m_conf),
        CONF_INPUT_DIR, preferencesIn, n, m, percentNonZeroValues,
        m_maxTestPrefs);

    System.out.println("CONF_TMP_DIR: " + CONF_TMP_DIR.toString());
    System.out.println("n: " + n + " m: " + m + " matrixRank: " + matrixRank);
    System.out.println(" iterations: " + iteration + " percentNonZeroValues: "
        + percentNonZeroValues);
    System.out.println("NumBspTask: " + m_conf.getInt("bsp.peers.num", 0));
    System.out.println("NumGpuBspTask: "
        + m_conf.getInt("bsp.peers.gpu.num", 0));
  }

  @Override
  protected void tearDown() throws Exception {

    verify();

    FileSystem fs = FileSystem.get(m_conf);
    fs.delete(CONF_TMP_DIR, true);
  }

  private void verify() throws Exception {
    // TODO verify results with m_testPrefs
  }

  public static List<double[]> generateRandomInputData(Configuration conf,
      FileSystem fs, Path in, Path preferencesIn, int userCount, int itemCount,
      int percentNonZeroValues, int maxTestPrefs) throws IOException {

    // Delete input files if already exist
    if (fs.exists(in)) {
      fs.delete(in, true);
    }
    if (fs.exists(preferencesIn)) {
      fs.delete(preferencesIn, true);
    }

    Random rand = new Random(32L);
    Set<Map.Entry<Long, Long>> userItemPairs = new HashSet<Map.Entry<Long, Long>>();
    List<double[]> testItems = new ArrayList<double[]>();

    int possibleUserItemRatings = userCount * itemCount;
    int userItemRatings = possibleUserItemRatings * percentNonZeroValues / 100;
    System.out.println("possibleRatings: " + possibleUserItemRatings
        + " ratings: " + userItemRatings);

    final SequenceFile.Writer dataWriter = SequenceFile.createWriter(fs, conf,
        preferencesIn, LongWritable.class, PipesVectorWritable.class,
        CompressionType.NONE);

    for (int i = 0; i < userItemRatings; i++) {

      // Find new user item rating which was not used before
      Map.Entry<Long, Long> userItemPair;
      do {
        long userId = rand.nextInt(userCount);
        long itemId = rand.nextInt(itemCount);
        userItemPair = new AbstractMap.SimpleImmutableEntry<Long, Long>(userId,
            itemId);
      } while (userItemPairs.contains(userItemPair));

      // Add user item rating
      userItemPairs.add(userItemPair);

      // Generate rating
      int rating = rand.nextInt(5) + 1; // values between 1 and 5

      // Add user item rating to test data
      if (i < maxTestPrefs) {
        testItems.add(new double[] { userItemPair.getKey(),
            userItemPair.getValue(), rating });
      }

      // Write out user item rating
      dataWriter.append(new LongWritable(userItemPair.getKey()),
          new PipesVectorWritable(new DenseDoubleVector(new double[] {
              userItemPair.getValue(), rating })));
    }
    dataWriter.close();

    return testItems;
  }

  // Microbenchmark
  // Uncomment Macro to use Micro
  // public void timeCalculate(int reps) {
  // int sum = 0;
  // for (int rep = 0; rep < reps; rep++) {
  // sum = doBenchmark(sum);
  // }
  // System.out.println(sum);
  // }

  @Macrobenchmark
  public void timeCalculate() {
    doBenchmark();
  }

  public void doBenchmark() {
    try {
      ToolRunner.run(new OnlineCFRunner(), null);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private class OnlineCFRunner extends Configured implements Tool {
    public OnlineCFRunner() {
    }

    @Override
    public int run(String[] arg0) throws Exception {

      BSPJob job = OnlineCFTrainHybridBSP.createOnlineCFTrainHybridBSPConf(
          m_conf, CONF_INPUT_DIR, CONF_OUTPUT_DIR);

      long startTime = System.currentTimeMillis();
      if (job.waitForCompletion(true)) {
        System.out.println("Job Finished in "
            + (System.currentTimeMillis() - startTime) / 1000.0 + " seconds");
      }

      return 0;
    }
  }

  public static void main(String[] args) {
    CaliperMain.main(OnlineCFHybridBenchmark.class, args);
  }

}
