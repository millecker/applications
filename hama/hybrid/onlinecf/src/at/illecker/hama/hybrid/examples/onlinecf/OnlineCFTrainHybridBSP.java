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
import org.apache.hama.commons.io.MatrixWritable;
import org.apache.hama.commons.io.PipesVectorWritable;
import org.apache.hama.commons.io.VectorWritable;
import org.apache.hama.commons.math.DenseDoubleMatrix;
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
    collectInput(peer, requiredUserFeatures, requiredItemFeatures);

    // since we have used some delimiters for
    // keys, HashPartitioner cannot partition
    // as we want, take user preferences and
    // broadcast user features and item features
    askForFeatures(peer, requiredUserFeatures, requiredItemFeatures);
    peer.sync();

    requiredUserFeatures = null;
    requiredItemFeatures = null;
    sendRequiredFeatures(peer);
    peer.sync();

    collectFeatures(peer);
    LOG.info(peer.getPeerName() + ") collected: " + this.usersMatrix.size()
        + " users, " + this.itemsMatrix.size() + " items, "
        + this.preferences.size() + " preferences");
    // input partitioning end

    // calculation steps
    for (int i = 0; i < m_maxIterations; i++) {
      computeValues();

      if ((i + 1) % m_skip_count == 0) {
        normalizeWithBroadcastingValues(peer);
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
      BSPPeer<Text, PipesVectorWritable, Text, PipesVectorWritable, PipesMapWritable> peer,
      HashSet<Text> requiredUserFeatures, HashSet<Text> requiredItemFeatures)
      throws IOException {

    Text key = new Text();
    PipesVectorWritable value = new PipesVectorWritable();
    int counter = 0;

    requiredUserFeatures = new HashSet<Text>();
    requiredItemFeatures = new HashSet<Text>();

    while (peer.readNext(key, value)) {
      // key format: (0, 1..n)
      // 0 - delimiter, for type of key
      // 1..n - actual key value
      String firstSymbol = key.toString().substring(0, 1);
      String actualId = key.toString().substring(1);

      if (firstSymbol.equals(m_inputPreferenceDelim)) {
        // parse as <k:userId, v:(itemId, score)>
        String itemId = Long.toString((long) value.getVector().get(0));
        String score = Double.toString(value.getVector().get(1));

        if (usersMatrix.containsKey(actualId) == false) {
          DenseDoubleVector vals = new DenseDoubleVector(m_matrix_rank);
          for (int i = 0; i < m_matrix_rank; i++) {
            vals.set(i, rnd.nextDouble());
          }
          VectorWritable rndValues = new VectorWritable(vals);
          usersMatrix.put(actualId, rndValues);
        }

        if (itemsMatrix.containsKey(itemId) == false) {
          DenseDoubleVector vals = new DenseDoubleVector(m_matrix_rank);
          for (int i = 0; i < m_matrix_rank; i++) {
            vals.set(i, rnd.nextDouble());
          }
          VectorWritable rndValues = new VectorWritable(vals);
          itemsMatrix.put(itemId, rndValues);
        }
        preferences.add(new Preference<String, String>(actualId, itemId, Double
            .parseDouble(score)));
        indexes.add(counter);

        // since we used HashPartitioner,
        // in order to ask for input feature we need peer index
        // we can obtain peer index by using actual key
        requiredUserFeatures.add(new Text(m_inputUserDelim + actualId));
        requiredItemFeatures.add(new Text(m_inputItemDelim + itemId));
        counter++;
      } else if (firstSymbol.equals(m_inputUserDelim)) {
        // parse as <k:userId, v:(ArrayOfFeatureValues)>
        if (inpUsersFeatures == null) {
          inpUsersFeatures = new HashMap<String, VectorWritable>();
        }
        inpUsersFeatures.put(actualId, value);

      } else if (firstSymbol.equals(m_inputItemDelim)) {
        // parse as <k:itemId, v:(ArrayOfFeatureValues)>
        if (inpItemsFeatures == null) {
          inpItemsFeatures = new HashMap<String, VectorWritable>();
        }
        inpItemsFeatures.put(actualId, value);

      } else {
        // just skip, maybe we should throw exception
        continue;
      }
    }
  }

  private void askForFeatures(
      BSPPeer<Text, PipesVectorWritable, Text, PipesVectorWritable, PipesMapWritable> peer,
      HashSet<Text> requiredUserFeatures, HashSet<Text> requiredItemFeatures)
      throws IOException, SyncException, InterruptedException {
    int peerCount = peer.getNumPeers();
    int peerId = peer.getPeerIndex();

    if (requiredUserFeatures != null) {
      Iterator<Text> iter = requiredUserFeatures.iterator();
      Text key = null;
      while (iter.hasNext()) {
        PipesMapWritable msg = new PipesMapWritable();
        key = iter.next();
        msg.put(OnlineCF.Settings.MSG_INP_USER_FEATURES, key);
        msg.put(OnlineCF.Settings.MSG_SENDER_ID, new IntWritable(peerId));
        peer.send(peer.getPeerName(key.hashCode() % peerCount), msg);
      }
    }

    if (requiredItemFeatures != null) {
      Iterator<Text> iter = requiredItemFeatures.iterator();
      Text key = null;
      while (iter.hasNext()) {
        PipesMapWritable msg = new PipesMapWritable();
        key = iter.next();
        msg.put(OnlineCF.Settings.MSG_INP_ITEM_FEATURES, key);
        msg.put(OnlineCF.Settings.MSG_SENDER_ID, new IntWritable(peerId));
        peer.send(peer.getPeerName(key.hashCode() % peerCount), msg);
      }
    }
  }

  private void collectFeatures(
      BSPPeer<Text, PipesVectorWritable, Text, PipesVectorWritable, PipesMapWritable> peer)
      throws IOException {
    // remove all features,
    // since we will get necessary features via messages
    inpItemsFeatures = new HashMap<String, VectorWritable>();
    inpUsersFeatures = new HashMap<String, VectorWritable>();

    PipesMapWritable msg = null;
    int userFeatureSize = 0;
    int itemFeatureSize = 0;
    while ((msg = peer.getCurrentMessage()) != null) {
      if (msg.containsKey(OnlineCF.Settings.MSG_INP_ITEM_FEATURES)) {
        // send item feature
        String itemId = ((Text) msg
            .get(OnlineCF.Settings.MSG_INP_ITEM_FEATURES)).toString();
        inpItemsFeatures.put(itemId,
            (VectorWritable) msg.get(OnlineCF.Settings.MSG_VALUE));
        itemFeatureSize = ((VectorWritable) msg
            .get(OnlineCF.Settings.MSG_VALUE)).getVector().getLength();
      } else if (msg.containsKey(OnlineCF.Settings.MSG_INP_USER_FEATURES)) {
        // send user feature
        String userId = ((Text) msg
            .get(OnlineCF.Settings.MSG_INP_USER_FEATURES)).toString();
        inpUsersFeatures.put(userId,
            (VectorWritable) msg.get(OnlineCF.Settings.MSG_VALUE));
        userFeatureSize = ((VectorWritable) msg
            .get(OnlineCF.Settings.MSG_VALUE)).getVector().getLength();
      }
    }
    if (inpItemsFeatures.size() > 0) {
      itemFeatureMatrix = new DenseDoubleMatrix(m_matrix_rank, itemFeatureSize,
          rnd);
    }
    if (inpUsersFeatures.size() > 0) {
      userFeatureMatrix = new DenseDoubleMatrix(m_matrix_rank, userFeatureSize,
          rnd);
    }
  }

  private void sendRequiredFeatures(
      BSPPeer<Text, PipesVectorWritable, Text, PipesVectorWritable, PipesMapWritable> peer)
      throws IOException, SyncException, InterruptedException {

    PipesMapWritable msg = null;
    int senderId = 0;

    while ((msg = peer.getCurrentMessage()) != null) {
      senderId = ((IntWritable) msg.get(OnlineCF.Settings.MSG_SENDER_ID)).get();
      PipesMapWritable resp = new PipesMapWritable();
      if (msg.containsKey(OnlineCF.Settings.MSG_INP_ITEM_FEATURES)) {
        // send item feature
        String itemId = ((Text) msg
            .get(OnlineCF.Settings.MSG_INP_ITEM_FEATURES)).toString()
            .substring(1);
        resp.put(OnlineCF.Settings.MSG_INP_ITEM_FEATURES, new Text(itemId));
        resp.put(OnlineCF.Settings.MSG_VALUE, inpItemsFeatures.get(itemId));
      } else if (msg.containsKey(OnlineCF.Settings.MSG_INP_USER_FEATURES)) {
        // send user feature
        String userId = ((Text) msg
            .get(OnlineCF.Settings.MSG_INP_USER_FEATURES)).toString()
            .substring(1);
        resp.put(OnlineCF.Settings.MSG_INP_USER_FEATURES, new Text(userId));
        resp.put(OnlineCF.Settings.MSG_VALUE, inpUsersFeatures.get(userId));
      }
      peer.send(peer.getPeerName(senderId), resp);
    }
  }

  private void computeValues() {
    // shuffling indexes
    int idx = 0;
    int idxValue = 0;
    int tmp = 0;
    for (int i = indexes.size(); i > 0; i--) {
      idx = Math.abs(rnd.nextInt()) % i;
      idxValue = indexes.get(idx);
      tmp = indexes.get(i - 1);
      indexes.set(i - 1, idxValue);
      indexes.set(idx, tmp);
    }

    // compute values
    OnlineUpdate.InputStructure inp = new OnlineUpdate.InputStructure();
    OnlineUpdate.OutputStructure out = null;
    Preference<String, String> pref = null;
    for (Integer prefIdx : indexes) {
      pref = preferences.get(prefIdx);

      VectorWritable userFactorizedValues = usersMatrix.get(pref.getUserId());
      VectorWritable itemFactorizedValues = itemsMatrix.get(pref.getItemId());
      VectorWritable userFeatures = (inpUsersFeatures != null) ? inpUsersFeatures
          .get(pref.getUserId()) : null;
      VectorWritable itemFeatures = (inpItemsFeatures != null) ? inpItemsFeatures
          .get(pref.getItemId()) : null;

      inp.user = userFactorizedValues;
      inp.item = itemFactorizedValues;
      inp.expectedScore = pref.getValue();
      inp.userFeatures = userFeatures;
      inp.itemFeatures = itemFeatures;
      inp.userFeatureFactorized = userFeatureMatrix;
      inp.itemFeatureFactorized = itemFeatureMatrix;

      out = function.compute(inp);

      usersMatrix.put(pref.getUserId(), out.userFactorized);
      itemsMatrix.put(pref.getItemId(), out.itemFactorized);
      userFeatureMatrix = out.userFeatureFactorized;
      itemFeatureMatrix = out.itemFeatureFactorized;
    }
  }

  private void normalizeWithBroadcastingValues(
      BSPPeer<Text, PipesVectorWritable, Text, PipesVectorWritable, PipesMapWritable> peer)
      throws IOException, SyncException, InterruptedException {
    // normalize item factorized values
    // normalize user/item feature matrix
    peer.sync();
    normalizeItemFactorizedValues(peer);
    peer.sync();

    if (itemFeatureMatrix != null) {
      // item feature factorized values should be normalized
      normalizeMatrix(peer, itemFeatureMatrix,
          OnlineCF.Settings.MSG_ITEM_FEATURE_MATRIX, true);
      peer.sync();
    }

    if (userFeatureMatrix != null) {
      // user feature factorized values should be normalized
      normalizeMatrix(peer, userFeatureMatrix,
          OnlineCF.Settings.MSG_USER_FEATURE_MATRIX, true);
      peer.sync();
    }
  }

  private void normalizeItemFactorizedValues(
      BSPPeer<Text, PipesVectorWritable, Text, PipesVectorWritable, PipesMapWritable> peer)
      throws IOException, SyncException, InterruptedException {
    // send item factorized matrices to selected peers
    sendItemFactorizedValues(peer);
    peer.sync();

    // receive item factorized matrices if this peer is selected and normalize
    // them
    HashMap<Text, LinkedList<IntWritable>> senderList = new HashMap<Text, LinkedList<IntWritable>>();
    HashMap<Text, DoubleVector> normalizedValues = new HashMap<Text, DoubleVector>();
    getNormalizedItemFactorizedValues(peer, normalizedValues, senderList);

    // send back normalized values to senders
    sendTo(peer, senderList, normalizedValues);
    peer.sync();

    // receive already normalized and synced data
    receiveSyncedItemFactorizedValues(peer);
  }

  private DoubleMatrix normalizeMatrix(
      BSPPeer<Text, PipesVectorWritable, Text, PipesVectorWritable, PipesMapWritable> peer,
      DoubleMatrix featureMatrix, IntWritable msgFeatureMatrix,
      boolean broadcast) throws IOException, SyncException,
      InterruptedException {
    // send to master peer
    PipesMapWritable msg = new PipesMapWritable();
    MatrixWritable mtx = new MatrixWritable(featureMatrix);
    msg.put(msgFeatureMatrix, mtx);
    String master = peer.getPeerName(peer.getNumPeers() / 2);
    peer.send(master, msg);
    peer.sync();

    // normalize
    DoubleMatrix res = null;
    if (peer.getPeerName().equals(master)) {
      res = new DenseDoubleMatrix(featureMatrix.getRowCount(),
          featureMatrix.getColumnCount(), 0);
      int incomingMsgCount = 0;
      while ((msg = peer.getCurrentMessage()) != null) {
        MatrixWritable tmp = (MatrixWritable) msg.get(msgFeatureMatrix);
        res.add(tmp.getMatrix());
        incomingMsgCount++;
      }
      res.divide(incomingMsgCount);
    }

    if (broadcast) {
      if (peer.getPeerName().equals(master)) {
        // broadcast to all
        msg = new PipesMapWritable();
        msg.put(msgFeatureMatrix, new MatrixWritable(res));
        // send to all
        for (String peerName : peer.getAllPeerNames()) {
          peer.send(peerName, msg);
        }
      }
      peer.sync();
      // receive normalized value from master
      msg = peer.getCurrentMessage();
      featureMatrix = ((MatrixWritable) msg.get(msgFeatureMatrix)).getMatrix();
    }
    return res;
  }

  private void sendItemFactorizedValues(
      BSPPeer<Text, PipesVectorWritable, Text, PipesVectorWritable, PipesMapWritable> peer)
      throws IOException, SyncException, InterruptedException {
    int peerCount = peer.getNumPeers();
    // item factorized values should be normalized
    IntWritable peerId = new IntWritable(peer.getPeerIndex());

    for (Map.Entry<String, VectorWritable> item : itemsMatrix.entrySet()) {
      PipesMapWritable msg = new PipesMapWritable();
      msg.put(OnlineCF.Settings.MSG_ITEM_MATRIX, new Text(item.getKey()));
      msg.put(OnlineCF.Settings.MSG_VALUE, item.getValue());
      msg.put(OnlineCF.Settings.MSG_SENDER_ID, peerId);
      peer.send(peer.getPeerName(item.getKey().hashCode() % peerCount), msg);
    }
  }

  private void getNormalizedItemFactorizedValues(
      BSPPeer<Text, PipesVectorWritable, Text, PipesVectorWritable, PipesMapWritable> peer,
      HashMap<Text, DoubleVector> normalizedValues,
      HashMap<Text, LinkedList<IntWritable>> senderList) throws IOException {

    HashMap<Text, Integer> normalizedValueCount = new HashMap<Text, Integer>();
    Text itemId = null;
    VectorWritable value = null;
    IntWritable senderId = null;
    PipesMapWritable msg = new PipesMapWritable();

    while ((msg = peer.getCurrentMessage()) != null) {
      itemId = (Text) msg.get(OnlineCF.Settings.MSG_ITEM_MATRIX);
      value = (VectorWritable) msg.get(OnlineCF.Settings.MSG_VALUE);
      senderId = (IntWritable) msg.get(OnlineCF.Settings.MSG_SENDER_ID);

      if (normalizedValues.containsKey(itemId) == false) {
        DenseDoubleVector tmp = new DenseDoubleVector(m_matrix_rank, 0.0);
        normalizedValues.put(itemId, tmp);
        normalizedValueCount.put(itemId, 0);
        senderList.put(itemId, new LinkedList<IntWritable>());
      }

      normalizedValues.put(itemId,
          normalizedValues.get(itemId).add(value.getVector()));
      normalizedValueCount.put(itemId, normalizedValueCount.get(itemId) + 1);
      senderList.get(itemId).add(senderId);
    }

    // normalize
    for (Map.Entry<Text, DoubleVector> e : normalizedValues.entrySet()) {
      double count = normalizedValueCount.get(e.getKey());
      e.setValue(e.getValue().multiply(1.0 / count));
    }
  }

  private void sendTo(
      BSPPeer<Text, PipesVectorWritable, Text, PipesVectorWritable, PipesMapWritable> peer,
      HashMap<Text, LinkedList<IntWritable>> senderList,
      HashMap<Text, DoubleVector> normalizedValues) throws IOException {

    for (Map.Entry<Text, DoubleVector> e : normalizedValues.entrySet()) {
      PipesMapWritable msgTmp = new PipesMapWritable();
      // send to interested peers
      msgTmp.put(OnlineCF.Settings.MSG_ITEM_MATRIX, e.getKey());
      msgTmp.put(OnlineCF.Settings.MSG_VALUE, new VectorWritable(e.getValue()));
      Iterator<IntWritable> iter = senderList.get(e.getKey()).iterator();
      while (iter.hasNext()) {
        peer.send(peer.getPeerName(iter.next().get()), msgTmp);
      }
    }
  }

  private void receiveSyncedItemFactorizedValues(
      BSPPeer<Text, PipesVectorWritable, Text, PipesVectorWritable, PipesMapWritable> peer)
      throws IOException {

    PipesMapWritable msg = new PipesMapWritable();
    Text itemId = null;
    // messages are arriving take them
    while ((msg = peer.getCurrentMessage()) != null) {
      itemId = (Text) msg.get(OnlineCF.Settings.MSG_ITEM_MATRIX);
      itemsMatrix.put(itemId.toString(),
          (VectorWritable) msg.get(OnlineCF.Settings.MSG_VALUE));
    }
  }

  private void saveModel(
      BSPPeer<Text, PipesVectorWritable, Text, PipesVectorWritable, PipesMapWritable> peer)
      throws IOException, SyncException, InterruptedException {

    // save user information
    LOG.info(peer.getPeerName() + ") saving " + usersMatrix.size() + " users");
    for (Map.Entry<String, VectorWritable> user : usersMatrix.entrySet()) {
      peer.write(
          new Text(OnlineCF.Settings.DFLT_MODEL_USER_DELIM + user.getKey()),
          new PipesVectorWritable(user.getValue()));
    }

    // broadcast item values, normalize and save
    sendItemFactorizedValues(peer);
    peer.sync();

    HashMap<Text, LinkedList<IntWritable>> senderList = new HashMap<Text, LinkedList<IntWritable>>();
    HashMap<Text, DoubleVector> normalizedValues = new HashMap<Text, DoubleVector>();
    getNormalizedItemFactorizedValues(peer, normalizedValues, senderList);

    saveItemFactorizedValues(peer, normalizedValues);

    // broadcast item and user feature matrix
    // normalize and save
    if (itemFeatureMatrix != null) {
      // save item features
      for (Map.Entry<String, VectorWritable> feature : inpItemsFeatures
          .entrySet()) {
        peer.write(new Text(OnlineCF.Settings.DFLT_MODEL_ITEM_FEATURES_DELIM
            + feature.getKey()), new PipesVectorWritable(feature.getValue()));
      }
      // item feature factorized values should be normalized
      DoubleMatrix res = normalizeMatrix(peer, itemFeatureMatrix,
          OnlineCF.Settings.MSG_ITEM_FEATURE_MATRIX, false);

      if (res != null) {
        Text key = new Text(
            OnlineCF.Settings.DFLT_MODEL_ITEM_MTX_FEATURES_DELIM
                + OnlineCF.Settings.MSG_ITEM_FEATURE_MATRIX.toString());
        peer.write(key, convertMatrixToVector(res));
      }
    }

    if (userFeatureMatrix != null) {
      // save user features
      // save item features
      for (Map.Entry<String, VectorWritable> feature : inpUsersFeatures
          .entrySet()) {
        peer.write(new Text(OnlineCF.Settings.DFLT_MODEL_USER_FEATURES_DELIM
            + feature.getKey()), new PipesVectorWritable(feature.getValue()));
      }
      // user feature factorized values should be normalized
      DoubleMatrix res = normalizeMatrix(peer, userFeatureMatrix,
          OnlineCF.Settings.MSG_USER_FEATURE_MATRIX, false);

      if (res != null) {
        Text key = new Text(
            OnlineCF.Settings.DFLT_MODEL_USER_MTX_FEATURES_DELIM
                + OnlineCF.Settings.MSG_USER_FEATURE_MATRIX.toString());
        peer.write(key, convertMatrixToVector(res));
      }
    }
  }

  private void saveItemFactorizedValues(
      BSPPeer<Text, PipesVectorWritable, Text, PipesVectorWritable, PipesMapWritable> peer,
      HashMap<Text, DoubleVector> normalizedValues) throws IOException {
    LOG.info(peer.getPeerName() + ") saving " + normalizedValues.size()
        + " items");
    for (Map.Entry<Text, DoubleVector> item : normalizedValues.entrySet()) {
      peer.write(new Text(OnlineCF.Settings.DFLT_MODEL_ITEM_DELIM
          + item.getKey().toString()), new PipesVectorWritable(item.getValue()));
    }
  }

  private PipesVectorWritable convertMatrixToVector(DoubleMatrix mat) {
    DoubleVector res = new DenseDoubleVector(mat.getRowCount()
        * mat.getColumnCount() + 1);
    int idx = 0;
    res.set(idx, m_matrix_rank);
    idx++;
    for (int i = 0; i < mat.getRowCount(); i++) {
      for (int j = 0; j < mat.getColumnCount(); j++) {
        res.set(idx, mat.get(i, j));
        idx++;
      }
    }
    return new PipesVectorWritable(res);
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

    conf.setInt(OnlineCF.Settings.CONF_ITERATION_COUNT, maxIteration);
    conf.setInt(OnlineCF.Settings.CONF_MATRIX_RANK, matrixRank);
    conf.setInt(OnlineCF.Settings.CONF_SKIP_COUNT, skipCount);

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
        preferencesIn, Text.class, PipesVectorWritable.class,
        CompressionType.NONE);

    for (Preference<Integer, Integer> taste : train_prefs) {
      double values[] = new double[2];
      values[0] = taste.getItemId();
      values[1] = taste.getValue().get();

      prefWriter.append(new Text(taste.getUserId().toString()),
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
