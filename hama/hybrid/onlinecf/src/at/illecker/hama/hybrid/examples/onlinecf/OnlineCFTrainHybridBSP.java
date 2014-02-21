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
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
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
import org.apache.hama.commons.math.DenseDoubleVector;
import org.apache.hama.commons.math.DoubleMatrix;
import org.apache.hama.commons.math.DoubleVector;
import org.apache.hama.ml.recommendation.Preference;
import org.apache.hama.ml.recommendation.cf.function.MeanAbsError;
import org.apache.hama.ml.recommendation.cf.function.OnlineUpdate;

import edu.syr.pcpratts.rootbeer.runtime.Rootbeer;
import edu.syr.pcpratts.rootbeer.runtime.StatsRow;
import edu.syr.pcpratts.rootbeer.runtime.util.Stopwatch;

public class OnlineCFTrainHybridBSP
    extends
    HybridBSP<IntWritable, PipesVectorWritable, Text, PipesVectorWritable, ItemMessage> {

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

  // Input Preferences
  private ArrayList<Preference<Integer, Long>> m_preferences = new ArrayList<Preference<Integer, Long>>();
  private ArrayList<Integer> m_indexes = new ArrayList<Integer>();

  // randomly generated depending on matrix rank,
  // will be computed runtime and represents trained model
  // userId, factorized value
  private HashMap<Integer, PipesVectorWritable> m_usersMatrix = new HashMap<Integer, PipesVectorWritable>();
  // itemId, factorized value
  private HashMap<Long, PipesVectorWritable> m_itemsMatrix = new HashMap<Long, PipesVectorWritable>();

  // matrixRank, factorized value
  private DoubleMatrix userFeatureMatrix = null;
  private DoubleMatrix itemFeatureMatrix = null;

  // obtained from input data
  // will not change during execution
  private HashMap<String, PipesVectorWritable> inpUsersFeatures = null;
  private HashMap<String, PipesVectorWritable> inpItemsFeatures = null;

  private OnlineUpdate.Function m_function = null;

  Random rnd = new Random();

  /********************************* CPU *********************************/
  @Override
  public void setup(
      BSPPeer<IntWritable, PipesVectorWritable, Text, PipesVectorWritable, ItemMessage> peer)
      throws IOException {

    long startTime = System.currentTimeMillis();

    this.m_conf = peer.getConfiguration();
    this.m_isDebuggingEnabled = m_conf.getBoolean(CONF_DEBUG, false);

    this.m_maxIterations = m_conf.getInt(OnlineCF.CONF_ITERATION_COUNT,
        OnlineCF.DFLT_ITERATION_COUNT);

    this.m_matrix_rank = m_conf.getInt(OnlineCF.CONF_MATRIX_RANK,
        OnlineCF.DFLT_MATRIX_RANK);

    this.m_skip_count = m_conf.getInt(OnlineCF.CONF_SKIP_COUNT,
        OnlineCF.DFLT_SKIP_COUNT);

    this.m_function = new MeanAbsError();

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
      BSPPeer<IntWritable, PipesVectorWritable, Text, PipesVectorWritable, ItemMessage> peer)
      throws IOException, SyncException, InterruptedException {

    int loggingPeer = 0;
    long startTime = System.currentTimeMillis();
    HashSet<Text> requiredUserFeatures = null;
    HashSet<Text> requiredItemFeatures = null;

    LOG.info(peer.getPeerName() + ") collecting input data");
    collectInput(peer);

    // TODO
    // REMOVED broadcast user features and item features

    LOG.info(peer.getPeerName() + ") collected: " + this.m_usersMatrix.size()
        + " users, " + this.m_itemsMatrix.size() + " items, "
        + this.m_preferences.size() + " preferences");

    // DEBUG
    if (peer.getPeerIndex() == loggingPeer) {
      LOG.info(peer.getPeerName() + ") usersMatrix: length: "
          + this.m_usersMatrix.size());
      for (Map.Entry<Integer, PipesVectorWritable> e : this.m_usersMatrix
          .entrySet()) {
        LOG.info(peer.getPeerName() + ") key: '" + e.getKey() + "' value: '"
            + e.getValue().toString() + "'");
      }
      LOG.info(peer.getPeerName() + ") itemsMatrix: length: "
          + this.m_itemsMatrix.size());
      for (Map.Entry<Long, PipesVectorWritable> e : this.m_itemsMatrix
          .entrySet()) {
        LOG.info(peer.getPeerName() + ") key: '" + e.getKey() + "' value: '"
            + e.getValue().toString() + "'");
      }
      LOG.info(peer.getPeerName() + ") preferences: length: "
          + this.m_preferences.size());
      for (Preference p : this.m_preferences) {
        LOG.info(peer.getPeerName() + ") userId: '" + p.getUserId()
            + "' itemId: '" + p.getItemId() + "' value: '" + p.getValue().get()
            + "'");
      }
      LOG.info("indexes: length: " + this.m_indexes.size() + " indexes: "
          + Arrays.toString(this.m_indexes.toArray()));

    }

    // calculation steps
    for (int i = 0; i < m_maxIterations; i++) {
      computeValues();

      // DEBUG
      if (peer.getPeerIndex() == loggingPeer) {
        LOG.info(peer.getPeerName() + ") usersMatrix: length: "
            + this.m_usersMatrix.size());
        for (Map.Entry<Integer, PipesVectorWritable> e : this.m_usersMatrix
            .entrySet()) {
          LOG.info(peer.getPeerName() + ") key: '" + e.getKey() + "' value: '"
              + e.getValue().toString() + "'");
        }
        LOG.info(peer.getPeerName() + ") itemsMatrix: length: "
            + this.m_itemsMatrix.size());
        for (Map.Entry<Long, PipesVectorWritable> e : this.m_itemsMatrix
            .entrySet()) {
          LOG.info(peer.getPeerName() + ") key: '" + e.getKey() + "' value: '"
              + e.getValue().toString() + "'");
        }
        LOG.info("indexes: length: " + this.m_indexes.size() + " indexes: "
            + Arrays.toString(this.m_indexes.toArray()));
      }

      if ((i + 1) % m_skip_count == 0) {
        // DEBUG
        if (peer.getPeerIndex() == loggingPeer) {
          LOG.info(peer.getPeerName()
              + ") normalizeWithBroadcastingValues: i: " + i);
        }

        normalizeWithBroadcastingValues(peer);
      }
    }

    // DEBUG
    if (peer.getPeerIndex() == loggingPeer) {
      LOG.info(peer.getPeerName() + ") usersMatrix: length: "
          + this.m_usersMatrix.size());
      for (Map.Entry<Integer, PipesVectorWritable> e : this.m_usersMatrix
          .entrySet()) {
        LOG.info(peer.getPeerName() + ") key: '" + e.getKey() + "' value: '"
            + e.getValue().toString() + "'");
      }
      LOG.info(peer.getPeerName() + ") itemsMatrix: length: "
          + this.m_itemsMatrix.size());
      for (Map.Entry<Long, PipesVectorWritable> e : this.m_itemsMatrix
          .entrySet()) {
        LOG.info(peer.getPeerName() + ") key: '" + e.getKey() + "' value: '"
            + e.getValue().toString() + "'");
      }
    }

    saveModel(peer);

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

  private void collectInput(
      BSPPeer<IntWritable, PipesVectorWritable, Text, PipesVectorWritable, ItemMessage> peer)
      throws IOException {

    IntWritable key = new IntWritable();
    PipesVectorWritable value = new PipesVectorWritable();
    int counter = 0;

    while (peer.readNext(key, value)) {
      int actualId = key.get();

      // parse as <k:userId, v:(itemId, score)>
      long itemId = (long) value.getVector().get(0);
      double score = value.getVector().get(1);

      if (m_usersMatrix.containsKey(actualId) == false) {
        DenseDoubleVector vals = new DenseDoubleVector(m_matrix_rank);
        for (int i = 0; i < m_matrix_rank; i++) {
          vals.set(i, rnd.nextDouble());
        }
        m_usersMatrix.put(actualId, new PipesVectorWritable(vals));
      }

      if (m_itemsMatrix.containsKey(itemId) == false) {
        DenseDoubleVector vals = new DenseDoubleVector(m_matrix_rank);
        for (int i = 0; i < m_matrix_rank; i++) {
          vals.set(i, rnd.nextDouble());
        }
        m_itemsMatrix.put(itemId, new PipesVectorWritable(vals));
      }
      m_preferences.add(new Preference<Integer, Long>(actualId, itemId, score));
      m_indexes.add(counter);
      counter++;
    }
  }

  private void computeValues() {
    // shuffling indexes
    int idx = 0;
    int idxValue = 0;
    int tmp = 0;
    for (int i = m_indexes.size(); i > 0; i--) {
      idx = Math.abs(rnd.nextInt()) % i;
      idxValue = m_indexes.get(idx);
      tmp = m_indexes.get(i - 1);
      m_indexes.set(i - 1, idxValue);
      m_indexes.set(idx, tmp);
    }

    // compute values
    OnlineUpdate.InputStructure inp = new OnlineUpdate.InputStructure();
    OnlineUpdate.OutputStructure out = null;
    Preference<Integer, Long> pref = null;
    for (Integer prefIdx : m_indexes) {
      pref = m_preferences.get(prefIdx);

      PipesVectorWritable userFactorizedValues = m_usersMatrix.get(pref
          .getUserId());
      PipesVectorWritable itemFactorizedValues = m_itemsMatrix.get(pref
          .getItemId());
      PipesVectorWritable userFeatures = (inpUsersFeatures != null) ? inpUsersFeatures
          .get(pref.getUserId()) : null;
      PipesVectorWritable itemFeatures = (inpItemsFeatures != null) ? inpItemsFeatures
          .get(pref.getItemId()) : null;

      inp.user = userFactorizedValues;
      inp.item = itemFactorizedValues;
      inp.expectedScore = pref.getValue();
      inp.userFeatures = userFeatures;
      inp.itemFeatures = itemFeatures;
      inp.userFeatureFactorized = userFeatureMatrix;
      inp.itemFeatureFactorized = itemFeatureMatrix;

      out = m_function.compute(inp);

      m_usersMatrix.put(pref.getUserId(), new PipesVectorWritable(
          out.userFactorized));
      m_itemsMatrix.put(pref.getItemId(), new PipesVectorWritable(
          out.itemFactorized));
      userFeatureMatrix = out.userFeatureFactorized;
      itemFeatureMatrix = out.itemFeatureFactorized;
    }
  }

  private void normalizeWithBroadcastingValues(
      BSPPeer<IntWritable, PipesVectorWritable, Text, PipesVectorWritable, ItemMessage> peer)
      throws IOException, SyncException, InterruptedException {

    // normalize item factorized values
    // normalize user/item feature matrix

    // Step 1)
    // send item factorized matrices to selected peers
    int peerCount = peer.getNumPeers();
    // item factorized values should be normalized
    int peerId = peer.getPeerIndex();

    for (Map.Entry<Long, PipesVectorWritable> item : m_itemsMatrix.entrySet()) {
      peer.send(peer.getPeerName(item.getKey().hashCode() % peerCount),
          new ItemMessage(peerId, item.getKey().longValue(), item.getValue()
              .getVector()));
    }
    peer.sync();

    // Step 2)
    // receive item factorized matrices if this peer is selected and normalize
    // them
    HashMap<Long, LinkedList<Integer>> senderList = new HashMap<Long, LinkedList<Integer>>();
    HashMap<Long, DoubleVector> normalizedValues = new HashMap<Long, DoubleVector>();
    HashMap<Long, Integer> normalizedValueCount = new HashMap<Long, Integer>();

    ItemMessage msg;
    while ((msg = peer.getCurrentMessage()) != null) {
      int senderId = msg.getSenderId();
      long itemId = msg.getItemId();
      DoubleVector vector = msg.getVector();

      if (normalizedValues.containsKey(itemId) == false) {
        normalizedValues.put(itemId, new DenseDoubleVector(m_matrix_rank, 0.0));
        normalizedValueCount.put(itemId, 0);
        senderList.put(itemId, new LinkedList<Integer>());
      }

      normalizedValues.put(itemId, normalizedValues.get(itemId).add(vector));
      normalizedValueCount.put(itemId, normalizedValueCount.get(itemId) + 1);
      senderList.get(itemId).add(senderId);
    }

    // normalize
    for (Map.Entry<Long, DoubleVector> e : normalizedValues.entrySet()) {
      double count = normalizedValueCount.get(e.getKey());
      e.setValue(e.getValue().multiply(1.0 / count));
    }

    // Step 3)
    // send back normalized values to senders
    for (Map.Entry<Long, DoubleVector> e : normalizedValues.entrySet()) {
      msg = new ItemMessage(peerId, e.getKey(), e.getValue());

      // send to interested peers
      Iterator<Integer> iter = senderList.get(e.getKey()).iterator();
      while (iter.hasNext()) {
        peer.send(peer.getPeerName(iter.next()), msg);
      }
    }
    peer.sync();

    // Step 4)
    // receive already normalized and synced data
    while ((msg = peer.getCurrentMessage()) != null) {
      m_itemsMatrix.put(msg.getItemId(),
          new PipesVectorWritable(msg.getVector()));
    }
  }

  private void saveModel(
      BSPPeer<IntWritable, PipesVectorWritable, Text, PipesVectorWritable, ItemMessage> peer)
      throws IOException, SyncException, InterruptedException {

    // save user information
    LOG.info(peer.getPeerName() + ") saving " + m_usersMatrix.size() + " users");
    for (Map.Entry<Integer, PipesVectorWritable> user : m_usersMatrix
        .entrySet()) {
      peer.write(new Text("u" + user.getKey()), user.getValue());
    }

    // save item information
    LOG.info(peer.getPeerName() + ") saving " + m_itemsMatrix.size() + " items");
    for (Map.Entry<Long, PipesVectorWritable> item : m_itemsMatrix.entrySet()) {
      peer.write(new Text("i" + item.getKey()), item.getValue());
    }
  }

  /********************************* GPU *********************************/
  @Override
  public void setupGpu(
      BSPPeer<IntWritable, PipesVectorWritable, Text, PipesVectorWritable, ItemMessage> peer)
      throws IOException, SyncException, InterruptedException {

    long startTime = System.currentTimeMillis();

    this.m_conf = peer.getConfiguration();
    this.m_isDebuggingEnabled = m_conf.getBoolean(CONF_DEBUG, false);

    this.m_maxIterations = m_conf.getInt(OnlineCF.CONF_ITERATION_COUNT,
        OnlineCF.DFLT_ITERATION_COUNT);
    this.m_matrix_rank = m_conf.getInt(OnlineCF.CONF_MATRIX_RANK,
        OnlineCF.DFLT_MATRIX_RANK);
    this.m_skip_count = m_conf.getInt(OnlineCF.CONF_SKIP_COUNT,
        OnlineCF.DFLT_SKIP_COUNT);

    this.m_function = new MeanAbsError();

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
      BSPPeer<IntWritable, PipesVectorWritable, Text, PipesVectorWritable, ItemMessage> peer,
      Rootbeer rootbeer) throws IOException, SyncException,
      InterruptedException {

    long startTime = System.currentTimeMillis();

    // Logging
    if (m_isDebuggingEnabled) {
      m_logger.writeChars("OnlineTrainHybridBSP.bspGpu executed on GPU!\n");
      m_logger.writeChars("OnlineTrainHybridBSP.bspGpu blockSize: "
          + m_blockSize + " gridSize: " + m_gridSize + "\n");
      // m_logger.writeChars("KMeansHybrid.bspGpu inputSize: " + inputs.size() +
      // "\n");
    }

    // TODO
    // OnlineCFTrainHybridKernel kernel = new
    // OnlineCFTrainHybridKernel(inputsArr,
    // m_centers_gpu, m_conf.getInt(CONF_MAX_ITERATIONS, 0),
    // peer.getAllPeerNames());

    rootbeer.setThreadConfig(m_blockSize, m_gridSize, m_blockSize * m_gridSize);

    // Run GPU Kernels
    Stopwatch watch = new Stopwatch();
    watch.start();
    // rootbeer.runAll(kernel);
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

    if (conf.getInt(OnlineCF.CONF_MATRIX_RANK, -1) == -1) {
      conf.setInt(OnlineCF.CONF_MATRIX_RANK, OnlineCF.DFLT_MATRIX_RANK);
    }

    if (conf.getInt(OnlineCF.CONF_ITERATION_COUNT, -1) == -1) {
      conf.setInt(OnlineCF.CONF_ITERATION_COUNT, OnlineCF.DFLT_ITERATION_COUNT);
    }

    if (conf.getInt(OnlineCF.CONF_SKIP_COUNT, -1) == -1) {
      conf.setInt(OnlineCF.CONF_SKIP_COUNT, OnlineCF.DFLT_SKIP_COUNT);
    }

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
    job.setInputKeyClass(IntWritable.class);
    job.setInputValueClass(PipesVectorWritable.class);

    job.setOutputPath(outPath);
    job.setOutputFormat(SequenceFileOutputFormat.class);
    job.setOutputKeyClass(Text.class);
    job.setOutputValueClass(PipesVectorWritable.class);

    job.setMessageClass(ItemMessage.class);

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

    int maxIteration = 150;
    int matrixRank = 3;
    int skipCount = 1;

    boolean useTestExampleInput = false;
    boolean isDebugging = false;

    Configuration conf = new HamaConfiguration();
    FileSystem fs = FileSystem.get(conf);

    // Set numBspTask to maxTasks
    BSPJobClient jobClient = new BSPJobClient(conf);
    ClusterStatus cluster = jobClient.getClusterStatus(true);
    numBspTask = cluster.getMaxTasks();

    if (args.length > 0) {
      if (args.length == 9) {
        numBspTask = Integer.parseInt(args[0]);
        numGpuBspTask = Integer.parseInt(args[1]);
        blockSize = Integer.parseInt(args[2]);
        gridSize = Integer.parseInt(args[3]);

        maxIteration = Integer.parseInt(args[4]);
        matrixRank = Integer.parseInt(args[5]);
        skipCount = Integer.parseInt(args[6]);

        useTestExampleInput = Boolean.parseBoolean(args[7]);
        isDebugging = Boolean.parseBoolean(args[8]);

      } else {
        System.out.println("Wrong argument size!");
        System.out.println("    Argument1=numBspTask");
        System.out.println("    Argument2=numGpuBspTask");
        System.out.println("    Argument3=blockSize");
        System.out.println("    Argument4=gridSize");

        System.out
            .println("    Argument5=maxIterations | Number of maximal iterations ("
                + maxIteration + ")");
        System.out.println("    Argument6=matrixRank | matrixRank ("
            + matrixRank + ")");
        System.out.println("    Argument7=skipCount | skipCount (" + skipCount
            + ")");
        System.out
            .println("    Argument8=testExample | Use testExample input (true|false=default)");
        System.out
            .println("    Argument9=debug | Enable debugging (true|false=default)");
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

    conf.setInt(OnlineCF.CONF_ITERATION_COUNT, maxIteration);
    conf.setInt(OnlineCF.CONF_MATRIX_RANK, matrixRank);
    conf.setInt(OnlineCF.CONF_SKIP_COUNT, skipCount);

    // Debug output
    LOG.info("NumBspTask: " + conf.getInt("bsp.peers.num", 0));
    LOG.info("NumGpuBspTask: " + conf.getInt("bsp.peers.gpu.num", 0));
    LOG.info("bsp.tasks.maximum: " + conf.get("bsp.tasks.maximum"));
    LOG.info("BlockSize: " + conf.get(CONF_BLOCKSIZE));
    LOG.info("GridSize: " + conf.get(CONF_GRIDSIZE));

    LOG.info("isDebugging: " + isDebugging);
    LOG.info("useTestExampleInput: " + useTestExampleInput);
    LOG.info("inputPath: " + CONF_INPUT_DIR);
    LOG.info("outputPath: " + CONF_OUTPUT_DIR);

    LOG.info("maxIteration: " + maxIteration);
    LOG.info("matrixRank: " + matrixRank);
    LOG.info("skipCount: " + skipCount);

    // prepare Input
    Preference[] testPrefs = null;
    if (useTestExampleInput) {
      Path preferencesIn = new Path(CONF_INPUT_DIR, "preferences_in.seq");
      testPrefs = prepareInputData(conf, fs, CONF_INPUT_DIR, preferencesIn,
          new Random(3337L));
    }

    BSPJob job = createOnlineCFTrainHybridBSPConf(conf, CONF_INPUT_DIR,
        CONF_OUTPUT_DIR);

    long startTime = System.currentTimeMillis();
    if (job.waitForCompletion(true)) {
      LOG.info("Job Finished in " + (System.currentTimeMillis() - startTime)
          / 1000.0 + " seconds");

      if (useTestExampleInput) {
        OnlineCF recommender = new OnlineCF();
        recommender.load(CONF_OUTPUT_DIR.toString(), false);

        int correct = 0;
        for (Preference<Integer, Integer> test : testPrefs) {
          double actual = test.getValue().get();
          double estimated = recommender.estimatePreference(test.getUserId(),
              test.getItemId());
          correct += (Math.abs(actual - estimated) < 0.5) ? 1 : 0;
        }

        LOG.info("assertEquals(expected: " + (testPrefs.length * 0.75) + " == "
            + correct + " actual with delta: 1");
      }

      if (isDebugging) {
        printOutput(conf, fs, ".log", new IntWritable(),
            new PipesVectorWritable());
      }
      // TODO
      // printFile(conf, fs, centerOut, new PipesVectorWritable(),
      // NullWritable.get());
    }

  }

  /**
   * prepareInputData
   * 
   */
  public static Preference[] prepareInputData(Configuration conf,
      FileSystem fs, Path in, Path preferencesIn, Random rand)
      throws IOException {

    Preference[] train_prefs = { new Preference<Integer, Integer>(1, 1, 4),
        new Preference<Integer, Integer>(1, 2, 2.5),
        new Preference<Integer, Integer>(1, 3, 3.5),
        new Preference<Integer, Integer>(1, 4, 1),
        new Preference<Integer, Integer>(1, 5, 3.5),

        new Preference<Integer, Integer>(2, 1, 4),
        new Preference<Integer, Integer>(2, 2, 2.5),
        new Preference<Integer, Integer>(2, 3, 3.5),
        new Preference<Integer, Integer>(2, 4, 1),
        new Preference<Integer, Integer>(2, 5, 3.5),

        new Preference<Integer, Integer>(3, 1, 4),
        new Preference<Integer, Integer>(3, 2, 2.5),
        new Preference<Integer, Integer>(3, 3, 3.5) };

    Preference[] test_prefs = { new Preference<Integer, Integer>(1, 3, 3.5),
        new Preference<Integer, Integer>(2, 4, 1),
        new Preference<Integer, Integer>(3, 4, 1),
        new Preference<Integer, Integer>(3, 5, 3.5) };

    // Delete input files if already exist
    if (fs.exists(in)) {
      fs.delete(in, true);
    }
    if (fs.exists(preferencesIn)) {
      fs.delete(preferencesIn, true);
    }

    final SequenceFile.Writer prefWriter = SequenceFile.createWriter(fs, conf,
        preferencesIn, IntWritable.class, PipesVectorWritable.class,
        CompressionType.NONE);

    for (Preference<Integer, Integer> taste : train_prefs) {
      double values[] = new double[2];
      values[0] = taste.getItemId();
      values[1] = taste.getValue().get();

      prefWriter.append(new IntWritable(taste.getUserId()),
          new PipesVectorWritable(new DenseDoubleVector(values)));
    }

    prefWriter.close();

    return test_prefs;
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
