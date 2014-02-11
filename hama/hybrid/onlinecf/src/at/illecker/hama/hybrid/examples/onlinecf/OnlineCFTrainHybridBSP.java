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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
import org.apache.hadoop.io.SequenceFile.CompressionType;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hama.Constants;
import org.apache.hama.HamaConfiguration;
import org.apache.hama.bsp.BSPJob;
import org.apache.hama.bsp.BSPJobClient;
import org.apache.hama.bsp.BSPPeer;
import org.apache.hama.bsp.ClusterStatus;
import org.apache.hama.bsp.FileOutputFormat;
import org.apache.hama.bsp.HashPartitioner;
import org.apache.hama.bsp.SequenceFileInputFormat;
import org.apache.hama.bsp.SequenceFileOutputFormat;
import org.apache.hama.bsp.gpu.HybridBSP;
import org.apache.hama.bsp.sync.SyncException;
import org.apache.hama.commons.io.PipesVectorWritable;
import org.apache.hama.commons.io.VectorWritable;
import org.apache.hama.commons.math.DenseDoubleVector;
import org.apache.hama.commons.math.DoubleMatrix;
import org.apache.hama.commons.math.DoubleVector;
import org.apache.hama.ml.recommendation.Preference;
import org.apache.hama.ml.recommendation.cf.OnlineCF;
import org.apache.hama.ml.recommendation.cf.function.OnlineUpdate;

import edu.syr.pcpratts.rootbeer.runtime.Rootbeer;
import edu.syr.pcpratts.rootbeer.runtime.StatsRow;
import edu.syr.pcpratts.rootbeer.runtime.util.Stopwatch;

public class OnlineCFTrainHybridBSP
    extends
    HybridBSP<Text, PipesVectorWritable, Text, PipesVectorWritable, PipesMapWritable> {

  private static final Log LOG = LogFactory
      .getLog(OnlineCFTrainHybridBSP.class);

  private static final Path CONF_TMP_DIR = new Path(
      "output/hama/hybrid/examples/onlinecf/hybrid-"
          + System.currentTimeMillis());
  private static final Path CONF_INPUT_DIR = new Path(CONF_TMP_DIR, "input");
  private static final Path CONF_OUTPUT_DIR = new Path(CONF_TMP_DIR, "output");

  public static final String CONF_BLOCKSIZE = "onlinecf.hybrid.blockSize";
  public static final String CONF_GRIDSIZE = "onlinecf.hybrid.gridSize";
  public static final String CONF_DEBUG = "onlinecf.is.debugging";

  // gridSize = amount of blocks and multiprocessors
  public static final int GRID_SIZE = 14;
  // blockSize = amount of threads
  public static final int BLOCK_SIZE = 1024;

  public long m_setupTimeCpu = 0;
  public long m_setupTimeGpu = 0;
  public long m_bspTimeCpu = 0;
  public long m_bspTimeGpu = 0;

  private Configuration m_conf;
  private boolean m_isDebuggingEnabled;
  private FSDataOutputStream m_logger;

  private int m_gridSize;
  private int m_blockSize;

  // OnlineCF members
  private int m_maxIterations = 0;
  private int m_matrix_rank = 0;
  private int m_skip_count = 0;

  private String m_inputPreferenceDelim = null;
  private String m_inputUserDelim = null;
  private String m_inputItemDelim = null;

  // randomly generated depending on matrix rank,
  // will be computed runtime and represents trained model
  // userId, factorized value
  private HashMap<String, VectorWritable> usersMatrix = new HashMap<String, VectorWritable>();
  // itemId, factorized value
  private HashMap<String, VectorWritable> itemsMatrix = new HashMap<String, VectorWritable>();
  // matrixRank, factorized value
  private DoubleMatrix userFeatureMatrix = null;
  private DoubleMatrix itemFeatureMatrix = null;

  // obtained from input data
  // will not change during execution
  private HashMap<String, VectorWritable> inpUsersFeatures = null;
  private HashMap<String, VectorWritable> inpItemsFeatures = null;

  private OnlineUpdate.Function function = null;

  // Input Preferences
  private ArrayList<Preference<String, String>> preferences = new ArrayList<Preference<String, String>>();
  private ArrayList<Integer> indexes = new ArrayList<Integer>();

  Random rnd = new Random();

  /********************************* CPU *********************************/
  @Override
  public void setup(
      BSPPeer<Text, PipesVectorWritable, Text, PipesVectorWritable, PipesMapWritable> peer)
      throws IOException {

    long startTime = System.currentTimeMillis();

    this.m_conf = peer.getConfiguration();
    this.m_isDebuggingEnabled = m_conf.getBoolean(CONF_DEBUG, false);

    this.m_maxIterations = m_conf.getInt(
        OnlineCF.Settings.CONF_ITERATION_COUNT,
        OnlineCF.Settings.DFLT_ITERATION_COUNT);
    this.m_matrix_rank = m_conf.getInt(OnlineCF.Settings.CONF_MATRIX_RANK,
        OnlineCF.Settings.DFLT_MATRIX_RANK);
    this.m_skip_count = m_conf.getInt(OnlineCF.Settings.CONF_SKIP_COUNT,
        OnlineCF.Settings.DFLT_SKIP_COUNT);

    this.m_inputItemDelim = m_conf.get(OnlineCF.Settings.CONF_INPUT_ITEM_DELIM,
        OnlineCF.Settings.DFLT_ITEM_DELIM);
    this.m_inputUserDelim = m_conf.get(OnlineCF.Settings.CONF_INPUT_USER_DELIM,
        OnlineCF.Settings.DFLT_USER_DELIM);
    this.m_inputPreferenceDelim = m_conf.get(
        OnlineCF.Settings.CONF_INPUT_PREFERENCES_DELIM,
        OnlineCF.Settings.DFLT_PREFERENCE_DELIM);

    Class<?> cls = m_conf.getClass(
        OnlineCF.Settings.CONF_ONLINE_UPDATE_FUNCTION, null);
    try {
      function = (OnlineUpdate.Function) (cls.newInstance());
    } catch (Exception e) {
      // set default function
    }

    // Init logging
    if (m_isDebuggingEnabled) {
      try {
        FileSystem fs = FileSystem.get(m_conf);
        m_logger = fs.create(new Path(FileOutputFormat
            .getOutputPath(new BSPJob((HamaConfiguration) m_conf))
            + "/BSP_"
            + peer.getTaskId() + ".log"));

      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    this.m_setupTimeCpu = System.currentTimeMillis() - startTime;
  }

  @Override
  public void bsp(
      BSPPeer<Text, PipesVectorWritable, Text, PipesVectorWritable, PipesMapWritable> peer)
      throws IOException, SyncException, InterruptedException {

    long startTime = System.currentTimeMillis();

    LOG.info(peer.getPeerName() + ") collecting input data");
    // input partitioning begin,
    // because we used one file for all input data
    // and input data are different type
    HashSet<Text> requiredUserFeatures = null;
    HashSet<Text> requiredItemFeatures = null;
    // TODO
    // collectInput(peer, requiredUserFeatures, requiredItemFeatures);

    // since we have used some delimiters for
    // keys, HashPartitioner cannot partition
    // as we want, take user preferences and
    // broadcast user features and item features
    // TODO
    // askForFeatures(peer, requiredUserFeatures, requiredItemFeatures);
    peer.sync();

    requiredUserFeatures = null;
    requiredItemFeatures = null;
    //TODO
    // sendRequiredFeatures(peer);
    peer.sync();

    // TODO
    // collectFeatures(peer);
    LOG.info(peer.getPeerName() + ") collected: " + this.usersMatrix.size()
        + " users, " + this.itemsMatrix.size() + " items, "
        + this.preferences.size() + " preferences");
    // input partitioning end

    // calculation steps
    for (int i = 0; i < m_maxIterations; i++) {
      // TODO
      // computeValues();

      if ((i + 1) % m_skip_count == 0) {
        // TODO
        // normalizeWithBroadcastingValues(peer);
      }
    }

    // TODO
    //saveModel(peer);

    this.m_bspTimeCpu = System.currentTimeMillis() - startTime;

    if (m_isDebuggingEnabled) {
      m_logger.writeChars("OnlineTrainHybridBSP,setupTimeCpu="
          + this.m_setupTimeCpu + " ms\n");
      m_logger.writeChars("OnlineTrainHybridBSP,setupTimeCpu="
          + (this.m_setupTimeCpu / 1000.0) + " seconds\n");
      m_logger.writeChars("OnlineTrainHybridBSP,bspTimeCpu="
          + this.m_bspTimeCpu + " ms\n");
      m_logger.writeChars("OnlineTrainHybridBSP,bspTimeCpu="
          + (this.m_bspTimeCpu / 1000.0) + " seconds\n");
      m_logger.close();
    }
  }

  /********************************* GPU *********************************/
  @Override
  public void setupGpu(
      BSPPeer<Text, PipesVectorWritable, Text, PipesVectorWritable, PipesMapWritable> peer)
      throws IOException, SyncException, InterruptedException {

    long startTime = System.currentTimeMillis();

    this.m_conf = peer.getConfiguration();
    this.m_isDebuggingEnabled = m_conf.getBoolean(CONF_DEBUG, false);

    this.m_maxIterations = m_conf.getInt(
        OnlineCF.Settings.CONF_ITERATION_COUNT,
        OnlineCF.Settings.DFLT_ITERATION_COUNT);
    this.m_matrix_rank = m_conf.getInt(OnlineCF.Settings.CONF_MATRIX_RANK,
        OnlineCF.Settings.DFLT_MATRIX_RANK);
    this.m_skip_count = m_conf.getInt(OnlineCF.Settings.CONF_SKIP_COUNT,
        OnlineCF.Settings.DFLT_SKIP_COUNT);

    this.m_inputItemDelim = m_conf.get(OnlineCF.Settings.CONF_INPUT_ITEM_DELIM,
        OnlineCF.Settings.DFLT_ITEM_DELIM);
    this.m_inputUserDelim = m_conf.get(OnlineCF.Settings.CONF_INPUT_USER_DELIM,
        OnlineCF.Settings.DFLT_USER_DELIM);
    this.m_inputPreferenceDelim = m_conf.get(
        OnlineCF.Settings.CONF_INPUT_PREFERENCES_DELIM,
        OnlineCF.Settings.DFLT_PREFERENCE_DELIM);

    Class<?> cls = m_conf.getClass(
        OnlineCF.Settings.CONF_ONLINE_UPDATE_FUNCTION, null);
    try {
      function = (OnlineUpdate.Function) (cls.newInstance());
    } catch (Exception e) {
      // set default function
    }

    this.m_blockSize = Integer.parseInt(this.m_conf.get(CONF_BLOCKSIZE));
    this.m_gridSize = Integer.parseInt(this.m_conf.get(CONF_GRIDSIZE));

    // Init logging
    if (m_isDebuggingEnabled) {
      try {
        FileSystem fs = FileSystem.get(m_conf);
        m_logger = fs.create(new Path(FileOutputFormat
            .getOutputPath(new BSPJob((HamaConfiguration) m_conf))
            + "/BSP_"
            + peer.getTaskId() + ".log"));

      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    this.m_setupTimeGpu = System.currentTimeMillis() - startTime;
  }

  @Override
  public void bspGpu(
      BSPPeer<Text, PipesVectorWritable, Text, PipesVectorWritable, PipesMapWritable> peer,
      Rootbeer rootbeer) throws IOException, SyncException,
      InterruptedException {

    long startTime = System.currentTimeMillis();


    // Logging
    if (m_isDebuggingEnabled) {
      m_logger.writeChars("KMeansHybrid.bspGpu executed on GPU!\n");
      m_logger.writeChars("KMeansHybrid.bspGpu blockSize: " + m_blockSize
          + " gridSize: " + m_gridSize + "\n");
     // m_logger.writeChars("KMeansHybrid.bspGpu inputSize: " + inputs.size()   + "\n");
    }

    // TODO
    // OnlineCFTrainHybridKernel kernel = new OnlineCFTrainHybridKernel(inputsArr,
    //    m_centers_gpu, m_conf.getInt(CONF_MAX_ITERATIONS, 0),
    //    peer.getAllPeerNames());

    rootbeer.setThreadConfig(m_blockSize, m_gridSize, m_blockSize * m_gridSize);

    // Run GPU Kernels
    Stopwatch watch = new Stopwatch();
    watch.start();
    //rootbeer.runAll(kernel);
    watch.stop();


    this.m_bspTimeGpu = System.currentTimeMillis() - startTime;

    // Logging
    if (m_isDebuggingEnabled) {
      m_logger.writeChars("OnlineTrainHybridBSP,setupTimeGpu="
          + this.m_setupTimeGpu + " ms\n");
      m_logger.writeChars("OnlineTrainHybridBSP,setupTimeGpu="
          + (this.m_setupTimeGpu / 1000.0) + " seconds\n");
      m_logger.writeChars("OnlineTrainHybridBSP,bspTimeGpu="
          + this.m_bspTimeGpu + " ms\n");
      m_logger.writeChars("OnlineTrainHybridBSP,bspTimeGpu="
          + (this.m_bspTimeGpu / 1000.0) + " seconds\n");

      List<StatsRow> stats = rootbeer.getStats();
      for (StatsRow row : stats) {
        m_logger.writeChars("  StatsRow:\n");
        m_logger.writeChars("    init time: " + row.getInitTime() + "\n");
        m_logger.writeChars("    serial time: " + row.getSerializationTime()
            + "\n");
        m_logger.writeChars("    exec time: " + row.getExecutionTime() + "\n");
        m_logger.writeChars("    deserial time: "
            + row.getDeserializationTime() + "\n");
        m_logger.writeChars("    num blocks: " + row.getNumBlocks() + "\n");
        m_logger.writeChars("    num threads: " + row.getNumThreads() + "\n");
        m_logger.writeChars("GPUTime: " + watch.elapsedTimeMillis() + " ms"
            + "\n");
      }

      m_logger.close();
    }
  }

  public static BSPJob createOnlineCFTrainHybridBSPConf(Path inPath,
      Path outPath) throws IOException {
    return createOnlineCFTrainHybridBSPConf(new HamaConfiguration(), inPath,
        outPath);
  }

  public static BSPJob createOnlineCFTrainHybridBSPConf(Configuration conf,
      Path inPath, Path outPath) throws IOException {

    if (conf.getInt(OnlineCF.Settings.CONF_MATRIX_RANK, -1) == -1) {
      conf.setInt(OnlineCF.Settings.CONF_MATRIX_RANK,
          OnlineCF.Settings.DFLT_MATRIX_RANK);
    }

    if (conf.getInt(OnlineCF.Settings.CONF_ITERATION_COUNT, -1) == -1) {
      conf.setInt(OnlineCF.Settings.CONF_ITERATION_COUNT,
          OnlineCF.Settings.DFLT_ITERATION_COUNT);
    }

    if (conf.getInt(OnlineCF.Settings.CONF_SKIP_COUNT, -1) == -1) {
      conf.setInt(OnlineCF.Settings.CONF_SKIP_COUNT,
          OnlineCF.Settings.DFLT_SKIP_COUNT);
    }

    if (conf.getClass(OnlineCF.Settings.CONF_ONLINE_UPDATE_FUNCTION, null) == null) {
      conf.setClass(OnlineCF.Settings.CONF_ONLINE_UPDATE_FUNCTION,
          OnlineCF.Settings.DFLT_UPDATE_FUNCTION, OnlineUpdate.Function.class);
    }
    conf.set(OnlineCF.Settings.CONF_MODEL_USER_DELIM,
        OnlineCF.Settings.DFLT_MODEL_USER_DELIM);
    conf.set(OnlineCF.Settings.CONF_MODEL_USER_FEATURE_DELIM,
        OnlineCF.Settings.DFLT_MODEL_USER_MTX_FEATURES_DELIM);
    conf.set(OnlineCF.Settings.CONF_MODEL_ITEM_DELIM,
        OnlineCF.Settings.DFLT_MODEL_ITEM_DELIM);
    conf.set(OnlineCF.Settings.CONF_MODEL_ITEM_FEATURE_DELIM,
        OnlineCF.Settings.DFLT_MODEL_ITEM_MTX_FEATURES_DELIM);

    BSPJob job = new BSPJob(new HamaConfiguration(conf),
        OnlineCFTrainHybridBSP.class);
    // Set the job name
    job.setJobName("Online CF train");
    // set the BSP class which shall be executed
    job.setBspClass(OnlineCFTrainHybridBSP.class);
    // help Hama to locale the jar to be distributed
    job.setJarByClass(OnlineCFTrainHybridBSP.class);

    job.setInputPath(inPath);
    job.setInputFormat(SequenceFileInputFormat.class);
    job.setInputKeyClass(Text.class);
    job.setInputValueClass(PipesVectorWritable.class);

    job.setOutputPath(outPath);
    job.setOutputFormat(SequenceFileOutputFormat.class);
    job.setOutputKeyClass(Text.class);
    job.setOutputValueClass(PipesVectorWritable.class);

    job.setMessageClass(PipesMapWritable.class);

    // Enable Partitioning
    job.setBoolean(Constants.ENABLE_RUNTIME_PARTITIONING, true);
    job.setPartitioner(HashPartitioner.class);

    job.set("bsp.child.java.opts", "-Xmx4G");

    return job;
  }

  public static void main(String[] args) throws Exception {

    // Defaults
    int numBspTask = 1;
    int numGpuBspTask = 1;
    int blockSize = BLOCK_SIZE;
    int gridSize = GRID_SIZE;

    long n = 10; // input vectors
    int k = 3; // start vectors
    int vectorDimension = 2;
    int maxIteration = 10;

    boolean useTestExampleInput = false;
    boolean isDebugging = false;

    Configuration conf = new HamaConfiguration();
    FileSystem fs = FileSystem.get(conf);

    // Set numBspTask to maxTasks
    BSPJobClient jobClient = new BSPJobClient(conf);
    ClusterStatus cluster = jobClient.getClusterStatus(true);
    numBspTask = cluster.getMaxTasks();

    if (args.length > 0) {
      if (args.length == 10) {
        numBspTask = Integer.parseInt(args[0]);
        numGpuBspTask = Integer.parseInt(args[1]);
        blockSize = Integer.parseInt(args[2]);
        gridSize = Integer.parseInt(args[3]);
        n = Long.parseLong(args[4]);
        k = Integer.parseInt(args[5]);
        vectorDimension = Integer.parseInt(args[6]);
        maxIteration = Integer.parseInt(args[7]);
        useTestExampleInput = Boolean.parseBoolean(args[8]);
        isDebugging = Boolean.parseBoolean(args[9]);

      } else {
        System.out.println("Wrong argument size!");
        System.out.println("    Argument1=numBspTask");
        System.out.println("    Argument2=numGpuBspTask");
        System.out.println("    Argument3=blockSize");
        System.out.println("    Argument4=gridSize");
        System.out.println("    Argument5=n | Number of input vectors (" + n
            + ")");
        System.out.println("    Argument6=k | Number of start vectors (" + k
            + ")");
        System.out
            .println("    Argument7=vectorDimension | Dimension of each vector ("
                + vectorDimension + ")");
        System.out
            .println("    Argument8=maxIterations | Number of maximal iterations ("
                + maxIteration + ")");
        System.out
            .println("    Argument9=testExample | Use testExample input (true|false=default)");
        System.out
            .println("    Argument10=debug | Enable debugging (true|false=default)");
        return;
      }
    }

    // Set config variables
    conf.setBoolean(CONF_DEBUG, isDebugging);
    conf.setBoolean("hama.pipes.logging", false);
    // Set CPU tasks
    conf.setInt("bsp.peers.num", numBspTask);
    // Set GPU tasks
    conf.setInt("bsp.peers.gpu.num", numGpuBspTask);
    // Set GPU blockSize and gridSize
    conf.set(CONF_BLOCKSIZE, "" + blockSize);
    conf.set(CONF_GRIDSIZE, "" + gridSize);

    LOG.info("NumBspTask: " + conf.getInt("bsp.peers.num", 0));
    LOG.info("NumGpuBspTask: " + conf.getInt("bsp.peers.gpu.num", 0));
    LOG.info("bsp.tasks.maximum: " + conf.get("bsp.tasks.maximum"));
    LOG.info("BlockSize: " + conf.get(CONF_BLOCKSIZE));
    LOG.info("GridSize: " + conf.get(CONF_GRIDSIZE));
    LOG.info("isDebugging: " + isDebugging);
    LOG.info("useTestExampleInput: " + useTestExampleInput);
    LOG.info("inputPath: " + CONF_INPUT_DIR);
    LOG.info("outputPath: " + CONF_OUTPUT_DIR);
    LOG.info("n: " + n);
    LOG.info("k: " + k);
    LOG.info("vectorDimension: " + vectorDimension);
    LOG.info("maxIteration: " + maxIteration);

    // prepare Input
    if (useTestExampleInput) {
      // prepareTestInput(conf, fs, input, centerIn);
      // prepareInputData(conf, fs, CONF_INPUT_DIR, centerIn, numBspTask
      //    + numGpuBspTask, n, k, vectorDimension, null);
    } else {
      // prepareInputData(conf, fs, CONF_INPUT_DIR, centerIn, numBspTask
      //    + numGpuBspTask, n, k, vectorDimension, new Random(3337L));
    }

    BSPJob job = createOnlineCFTrainHybridBSPConf(conf, CONF_INPUT_DIR,
        CONF_OUTPUT_DIR);

    long startTime = System.currentTimeMillis();
    if (job.waitForCompletion(true)) {
      LOG.info("Job Finished in " + (System.currentTimeMillis() - startTime)
          / 1000.0 + " seconds");
      if (isDebugging) {
        printOutput(conf, fs, ".log", new IntWritable(),
            new PipesVectorWritable());
      }
      // TODO
      // printFile(conf, fs, centerOut, new PipesVectorWritable(),
      //    NullWritable.get());
    }

  }

  /**
   * prepareInputData
   * 
   */
  public static void prepareInputData(Configuration conf, FileSystem fs,
      Path in, Path centerIn, int numBspTask, long n, int k,
      int vectorDimension, Random rand) throws IOException {

    // Delete input files if already exist
    if (fs.exists(in)) {
      fs.delete(in, true);
    }
    if (fs.exists(centerIn)) {
      fs.delete(centerIn, true);
    }

    final NullWritable nullValue = NullWritable.get();
    final SequenceFile.Writer centerWriter = SequenceFile.createWriter(fs,
        conf, centerIn, PipesVectorWritable.class, NullWritable.class,
        CompressionType.NONE);

    long totalNumberOfPoints = n;
    long interval = totalNumberOfPoints / numBspTask;
    int centers = 0;

    for (int part = 0; part < numBspTask; part++) {
      Path partIn = new Path(in, "part" + part + ".seq");
      final SequenceFile.Writer dataWriter = SequenceFile.createWriter(fs,
          conf, partIn, PipesVectorWritable.class, NullWritable.class,
          CompressionType.NONE);

      long start = interval * part;
      long end = start + interval - 1;
      if ((numBspTask - 1) == part) {
        end = totalNumberOfPoints;
      }
      LOG.info("Partition " + part + ": from " + start + " to " + end);

      for (long i = start; i <= end; i++) {

        double[] arr = new double[vectorDimension];
        for (int j = 0; j < vectorDimension; j++) {
          if (rand != null) {
            arr[j] = rand.nextInt((int) n);
          } else {
            arr[j] = i;
          }
        }
        PipesVectorWritable vector = new PipesVectorWritable(
            new DenseDoubleVector(arr));

        // LOG.info("input[" + i + "]: " + Arrays.toString(arr));
        dataWriter.append(vector, nullValue);

        if (k > centers) {
          // LOG.info("center[" + i + "]: " + Arrays.toString(arr));
          centerWriter.append(vector, nullValue);
          centers++;
        } else {
          centerWriter.close();
        }

      }
      dataWriter.close();
    }

  }

  static void printOutput(Configuration conf, FileSystem fs,
      String extensionFilter, Writable key, Writable value) throws IOException {
    FileStatus[] files = fs.listStatus(CONF_OUTPUT_DIR);
    for (int i = 0; i < files.length; i++) {
      if ((files[i].getLen() > 0)
          && (files[i].getPath().getName().endsWith(extensionFilter))) {
        printFile(conf, fs, files[i].getPath(), key, value);
      }
    }
    // fs.delete(FileOutputFormat.getOutputPath(job), true);
  }

  static void printFile(Configuration conf, FileSystem fs, Path file,
      Writable key, Writable value) throws IOException {
    System.out.println("File " + file.toString());
    SequenceFile.Reader reader = null;
    try {
      reader = new SequenceFile.Reader(fs, file, conf);

      while (reader.next(key, value)) {
        System.out.println("key: '" + key.toString() + "' value: '"
            + value.toString() + "'\n");
      }
    } catch (IOException e) {
      FSDataInputStream in = fs.open(file);
      IOUtils.copyBytes(in, System.out, conf, false);
      in.close();
    } catch (NullPointerException e) {
      LOG.error(e);
    } finally {
      if (reader != null) {
        reader.close();
      }
    }
  }
}
