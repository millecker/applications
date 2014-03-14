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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
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
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.SequenceFile.CompressionType;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hama.Constants;
import org.apache.hama.HamaConfiguration;
import org.apache.hama.bsp.BSPJob;
import org.apache.hama.bsp.BSPPeer;
import org.apache.hama.bsp.FileOutputFormat;
import org.apache.hama.bsp.HashPartitioner;
import org.apache.hama.bsp.SequenceFileInputFormat;
import org.apache.hama.bsp.SequenceFileOutputFormat;
import org.apache.hama.bsp.gpu.HybridBSP;
import org.apache.hama.bsp.sync.SyncException;
import org.apache.hama.commons.io.PipesVectorWritable;
import org.apache.hama.commons.io.VectorWritable;
import org.apache.hama.commons.math.DenseDoubleVector;
import org.apache.hama.commons.math.DoubleVector;
import org.apache.hama.ml.recommendation.Preference;
import org.trifort.rootbeer.runtime.Context;
import org.trifort.rootbeer.runtime.Rootbeer;
import org.trifort.rootbeer.runtime.StatsRow;
import org.trifort.rootbeer.runtime.ThreadConfig;
import org.trifort.rootbeer.runtime.util.Stopwatch;

public class OnlineCFTrainHybridBSP
    extends
    HybridBSP<LongWritable, PipesVectorWritable, Text, PipesVectorWritable, ItemMessage> {

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
  public static final int BLOCK_SIZE = 256;

  public static final double ALPHA = 0.01;

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
  private int m_matrixRank = 0;
  private int m_skipCount = 0;

  // Input Preferences
  private ArrayList<Preference<Long, Long>> m_preferences = new ArrayList<Preference<Long, Long>>();
  private ArrayList<Integer> m_indexes = new ArrayList<Integer>();

  // Randomly generated depending on matrix rank,
  // will be computed runtime and represents trained model
  // userId, factorized value
  private HashMap<Long, PipesVectorWritable> m_usersMatrix = new HashMap<Long, PipesVectorWritable>();
  // itemId, factorized value
  private HashMap<Long, PipesVectorWritable> m_itemsMatrix = new HashMap<Long, PipesVectorWritable>();

  private Random m_rnd = new Random(32L);

  /********************************* CPU *********************************/
  @Override
  public void setup(
      BSPPeer<LongWritable, PipesVectorWritable, Text, PipesVectorWritable, ItemMessage> peer)
      throws IOException {

    long startTime = System.currentTimeMillis();

    this.m_conf = peer.getConfiguration();
    this.m_isDebuggingEnabled = m_conf.getBoolean(CONF_DEBUG, false);

    this.m_maxIterations = m_conf.getInt(OnlineCF.CONF_ITERATION_COUNT,
        OnlineCF.DFLT_ITERATION_COUNT);

    this.m_matrixRank = m_conf.getInt(OnlineCF.CONF_MATRIX_RANK,
        OnlineCF.DFLT_MATRIX_RANK);

    this.m_skipCount = m_conf.getInt(OnlineCF.CONF_SKIP_COUNT,
        OnlineCF.DFLT_SKIP_COUNT);

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
      BSPPeer<LongWritable, PipesVectorWritable, Text, PipesVectorWritable, ItemMessage> peer)
      throws IOException, SyncException, InterruptedException {

    long startTime = System.currentTimeMillis();

    // Fetch inputs
    collectInput(peer);

    // DEBUG
    if (m_isDebuggingEnabled) {
      m_logger.writeChars("collected: " + this.m_usersMatrix.size()
          + " users, " + this.m_itemsMatrix.size() + " items, "
          + this.m_preferences.size() + " preferences\n");

      m_logger.writeChars("preferences: length: " + this.m_preferences.size()
          + "\n");
      for (Preference<Long, Long> p : this.m_preferences) {
        m_logger.writeChars("userId: '" + p.getUserId() + "' itemId: '"
            + p.getItemId() + "' value: '" + p.getValue().get() + "'\n");
      }
      m_logger.writeChars("indexes: length: " + this.m_indexes.size()
          + " indexes: " + Arrays.toString(this.m_indexes.toArray()) + "\n");

      m_logger.writeChars("usersMatrix: length: " + this.m_usersMatrix.size()
          + "\n");
      for (Map.Entry<Long, PipesVectorWritable> e : this.m_usersMatrix
          .entrySet()) {
        m_logger.writeChars("key: '" + e.getKey() + "' value: '"
            + e.getValue().toString() + "'\n");
      }
      m_logger.writeChars("itemsMatrix: length: " + this.m_itemsMatrix.size()
          + "\n");
      for (Map.Entry<Long, PipesVectorWritable> e : this.m_itemsMatrix
          .entrySet()) {
        m_logger.writeChars("key: '" + e.getKey() + "' value: '"
            + e.getValue().toString() + "'\n");
      }
    }

    // calculation steps
    for (int i = 0; i < m_maxIterations; i++) {

      // computeUserValues();
      // computeItemValues();
      computeAllValues();

      // DEBUG
      // m_logger.writeChars("values after computeValues(" + i + ")\n");
      // m_logger.writeChars("usersMatrix: length: " + this.m_usersMatrix.size()
      // + "\n");
      // for (Map.Entry<Long, PipesVectorWritable> e : this.m_usersMatrix
      // .entrySet()) {
      // m_logger.writeChars("key: '" + e.getKey() + "' value: '"
      // + e.getValue().toString() + "'\n");
      // }
      // m_logger.writeChars("itemsMatrix: length: " + this.m_itemsMatrix.size()
      // + "\n");
      // for (Map.Entry<Long, PipesVectorWritable> e : this.m_itemsMatrix
      // .entrySet()) {
      // m_logger.writeChars("key: '" + e.getKey() + "' value: '"
      // + e.getValue().toString() + "'\n");
      // }
      // m_logger.writeChars("indexes: length: " + this.m_indexes.size()
      // + " indexes: " + Arrays.toString(this.m_indexes.toArray()) + "\n");

      if ((i + 1) % m_skipCount == 0) {
        // DEBUG
        // m_logger.writeChars("normalizeWithBroadcastingValues: i: " + i +
        // "\n");

        normalizeWithBroadcastingValues(peer);
      }
    }

    // Save Model
    // TODO why is each task saving all items?
    // after exchanging items all tasks should have the same items! -> duplicate
    // saves

    // save user information
    if (m_isDebuggingEnabled) {
      m_logger.writeChars("saving " + m_usersMatrix.size() + " users\n");
    }
    for (Map.Entry<Long, PipesVectorWritable> user : m_usersMatrix.entrySet()) {
      if (m_isDebuggingEnabled) {
        m_logger.writeChars("user: " + user.getKey() + " vector: "
            + user.getValue().getVector() + "\n");
      }
      peer.write(new Text("u" + user.getKey()), user.getValue());
    }

    // save item information
    if (m_isDebuggingEnabled) {
      m_logger.writeChars("saving " + m_itemsMatrix.size() + " items\n");
    }
    for (Map.Entry<Long, PipesVectorWritable> item : m_itemsMatrix.entrySet()) {
      if (m_isDebuggingEnabled) {
        m_logger.writeChars("item: " + item.getKey() + " vector: "
            + item.getValue().getVector() + "\n");
      }
      peer.write(new Text("i" + item.getKey()), item.getValue());
    }

    this.m_bspTimeCpu = System.currentTimeMillis() - startTime;

    // Set times in config
    HamaConfiguration config = peer.getConfiguration();
    config.setLong("setup.time", m_setupTimeCpu);
    config.setLong("bsp.time", m_bspTimeCpu);

    // Logging
    if (m_isDebuggingEnabled) {
      m_logger.writeChars("OnlineCFTrainHybridBSP,setupTimeCpu="
          + this.m_setupTimeCpu + " ms\n");
      m_logger.writeChars("OnlineCFTrainHybridBSP,setupTimeCpu="
          + (this.m_setupTimeCpu / 1000.0) + " seconds\n");
      m_logger.writeChars("OnlineCFTrainHybridBSP,bspTimeCpu="
          + this.m_bspTimeCpu + " ms\n");
      m_logger.writeChars("OnlineCFTrainHybridBSP,bspTimeCpu="
          + (this.m_bspTimeCpu / 1000.0) + " seconds\n");
      m_logger.close();
    }
    LOG.info("OnlineCFTrainHybridBSP,setupTimeCpu=" + this.m_setupTimeCpu
        + " ms");
    LOG.info("OnlineCFTrainHybridBSP,setupTimeCpu="
        + (this.m_setupTimeCpu / 1000.0) + " seconds");
    LOG.info("OnlineCFTrainHybridBSP,bspTimeCpu=" + this.m_bspTimeCpu + " ms");
    LOG.info("OnlineCFTrainHybridBSP,bspTimeCpu="
        + (this.m_bspTimeCpu / 1000.0) + " seconds");

  }

  private void collectInput(
      BSPPeer<LongWritable, PipesVectorWritable, Text, PipesVectorWritable, ItemMessage> peer)
      throws IOException {

    LongWritable key = new LongWritable();
    PipesVectorWritable value = new PipesVectorWritable();
    int counter = 0;

    while (peer.readNext(key, value)) {
      long actualId = key.get();

      // parse as <k:userId, v:(itemId, score)>
      long itemId = (long) value.getVector().get(0);
      double score = value.getVector().get(1);

      if (m_usersMatrix.containsKey(actualId) == false) {
        DenseDoubleVector vals = new DenseDoubleVector(m_matrixRank);
        for (int i = 0; i < m_matrixRank; i++) {
          vals.set(i, m_rnd.nextDouble());
        }
        m_usersMatrix.put(actualId, new PipesVectorWritable(vals));
      }

      if (m_itemsMatrix.containsKey(itemId) == false) {
        DenseDoubleVector vals = new DenseDoubleVector(m_matrixRank);
        for (int i = 0; i < m_matrixRank; i++) {
          vals.set(i, m_rnd.nextDouble());
        }
        m_itemsMatrix.put(itemId, new PipesVectorWritable(vals));
      }
      m_preferences.add(new Preference<Long, Long>(actualId, itemId, score));
      m_indexes.add(counter);
      counter++;
    }
  }

  private void computeUserValues() throws IOException {
    // compute values
    for (Integer prefIdx : m_indexes) {
      Preference<Long, Long> pref = m_preferences.get(prefIdx);

      // function input
      PipesVectorWritable in_userFactorizedValues = m_usersMatrix.get(pref
          .getUserId());
      PipesVectorWritable in_itemFactorizedValues = m_itemsMatrix.get(pref
          .getItemId());

      // function output
      VectorWritable out_userFactorized;

      // DEBUG
      // m_logger.writeChars("preferenceIdx: " + prefIdx + " - (a,b,r) = ("
      // + pref.getUserId() + "," + pref.getItemId() + ","
      // + pref.getValue().get() + ")\n");
      // m_logger.writeChars("userVector: "
      // + in_userFactorizedValues.getVector().toString() + "\n");
      // m_logger.writeChars("itemVector: "
      // + in_itemFactorizedValues.getVector().toString() + "\n");

      // MeanAbsError function
      // below vectors are all size of MATRIX_RANK
      DoubleVector bbl_vl_yb = in_itemFactorizedValues.getVector();
      DoubleVector aal_ml_xa = in_userFactorizedValues.getVector();

      // calculated score
      double calculatedScore = aal_ml_xa.multiply(bbl_vl_yb).sum();
      double expectedScore = pref.getValue().get();
      double scoreDifference = expectedScore - calculatedScore;

      // DEBUG
      // m_logger.writeChars("expectedScore: " + expectedScore + "\n");
      // m_logger.writeChars("calculatedScore: " + calculatedScore + "\n");
      // m_logger.writeChars("scoreDifference: " + scoreDifference + "\n");

      // α_al ← α_al + 2τ * (β_bl + ν_l: * y_b:)(r − R)
      // users ← user + userFactorization (will be used later)
      DoubleVector userFactorization = bbl_vl_yb.multiply(2 * ALPHA
          * scoreDifference);
      DoubleVector users = in_userFactorizedValues.getVector().add(
          userFactorization);
      out_userFactorized = new VectorWritable(users);

      // DEBUG
      // m_logger.writeChars("Update userVector: "
      // + out_userFactorized.getVector().toString() + "\n");

      // update function output
      m_usersMatrix.put(pref.getUserId(), new PipesVectorWritable(
          out_userFactorized));
    }
  }

  private void computeItemValues() throws IOException {
    // compute values
    for (Integer prefIdx : m_indexes) {
      Preference<Long, Long> pref = m_preferences.get(prefIdx);

      // function input
      PipesVectorWritable in_userFactorizedValues = m_usersMatrix.get(pref
          .getUserId());
      PipesVectorWritable in_itemFactorizedValues = m_itemsMatrix.get(pref
          .getItemId());

      // function output
      VectorWritable out_itemFactorized;

      // DEBUG
      // m_logger.writeChars("preferenceIdx: " + prefIdx + " - (a,b,r) = ("
      // + pref.getUserId() + "," + pref.getItemId() + ","
      // + pref.getValue().get() + ")\n");
      // m_logger.writeChars("userVector: "
      // + in_userFactorizedValues.getVector().toString() + "\n");
      // m_logger.writeChars("itemVector: "
      // + in_itemFactorizedValues.getVector().toString() + "\n");

      // MeanAbsError function
      // below vectors are all size of MATRIX_RANK
      DoubleVector bbl_vl_yb = in_itemFactorizedValues.getVector();
      DoubleVector aal_ml_xa = in_userFactorizedValues.getVector();

      // calculated score
      double calculatedScore = aal_ml_xa.multiply(bbl_vl_yb).sum();
      double expectedScore = pref.getValue().get();
      double scoreDifference = expectedScore - calculatedScore;

      // DEBUG
      // m_logger.writeChars("expectedScore: " + expectedScore + "\n");
      // m_logger.writeChars("calculatedScore: " + calculatedScore + "\n");
      // m_logger.writeChars("scoreDifference: " + scoreDifference + "\n");

      // β_bl ← β_bl + 2τ * (α_al + μ_l: * x_a:)(r − R)
      // items ← item + itemFactorization (will be used later)
      DoubleVector itemFactorization = aal_ml_xa.multiply(2 * ALPHA
          * scoreDifference);
      DoubleVector items = in_itemFactorizedValues.getVector().add(
          itemFactorization);
      out_itemFactorized = new VectorWritable(items);

      // DEBUG
      // m_logger.writeChars("Update itemVector: "
      // + out_itemFactorized.getVector().toString() + "\n");

      // update function output
      m_itemsMatrix.put(pref.getItemId(), new PipesVectorWritable(
          out_itemFactorized));
    }
  }

  private void computeAllValues() throws IOException {
    // shuffling indexes
    int idx = 0;
    int idxValue = 0;
    int tmp = 0;
    for (int i = m_indexes.size(); i > 0; i--) {
      idx = Math.abs(m_rnd.nextInt()) % i;
      idxValue = m_indexes.get(idx);
      tmp = m_indexes.get(i - 1);
      m_indexes.set(i - 1, idxValue);
      m_indexes.set(idx, tmp);
    }

    // compute values
    for (Integer prefIdx : m_indexes) {
      Preference<Long, Long> pref = m_preferences.get(prefIdx);

      // function input
      PipesVectorWritable in_userFactorizedValues = m_usersMatrix.get(pref
          .getUserId());
      PipesVectorWritable in_itemFactorizedValues = m_itemsMatrix.get(pref
          .getItemId());

      // function output
      VectorWritable out_userFactorized;
      VectorWritable out_itemFactorized;

      // DEBUG
      // m_logger.writeChars("preferenceIdx: " + prefIdx + " - (a,b,r) = ("
      // + pref.getUserId() + "," + pref.getItemId() + ","
      // + pref.getValue().get() + ")\n");
      // m_logger.writeChars("alpa_al: "
      // + in_userFactorizedValues.getVector().toString() + "\n");
      // m_logger.writeChars("beta_bl: "
      // + in_itemFactorizedValues.getVector().toString() + "\n");

      // MeanAbsError function
      // below vectors are all size of MATRIX_RANK
      DoubleVector bbl_vl_yb = in_itemFactorizedValues.getVector();
      DoubleVector aal_ml_xa = in_userFactorizedValues.getVector();

      // calculated score
      double calculatedScore = aal_ml_xa.multiply(bbl_vl_yb).sum();
      double expectedScore = pref.getValue().get();
      double scoreDifference = 0.0;
      scoreDifference = expectedScore - calculatedScore;

      // DEBUG
      // m_logger.writeChars("expectedScore: " + expectedScore
      // + " calculatedScore: " + calculatedScore + " scoreDifference: "
      // + scoreDifference + "\n");

      // α_al ← α_al + 2τ * (β_bl + ν_l: * y_b:)(r − R)
      // users ← user + userFactorization (will be used later)
      DoubleVector userFactorization = bbl_vl_yb.multiply(2 * ALPHA
          * scoreDifference);
      DoubleVector users = in_userFactorizedValues.getVector().add(
          userFactorization);
      out_userFactorized = new VectorWritable(users);

      // β_bl ← β_bl + 2τ * (α_al + μ_l: * x_a:)(r − R)
      // items ← item + itemFactorization (will be used later)
      DoubleVector itemFactorization = aal_ml_xa.multiply(2 * ALPHA
          * scoreDifference);
      DoubleVector items = in_itemFactorizedValues.getVector().add(
          itemFactorization);
      out_itemFactorized = new VectorWritable(items);

      // DEBUG
      // m_logger.writeChars("UPDATE alpa_al: "
      // + out_userFactorized.getVector().toString() + "\n");
      // m_logger.writeChars("UPDATE beta_bl: "
      // + out_itemFactorized.getVector().toString() + "\n");

      // update function output
      m_usersMatrix.put(pref.getUserId(), new PipesVectorWritable(
          out_userFactorized));
      m_itemsMatrix.put(pref.getItemId(), new PipesVectorWritable(
          out_itemFactorized));
    }
  }

  private void normalizeWithBroadcastingValues(
      BSPPeer<LongWritable, PipesVectorWritable, Text, PipesVectorWritable, ItemMessage> peer)
      throws IOException, SyncException, InterruptedException {

    int peerCount = peer.getNumPeers();
    int peerId = peer.getPeerIndex();
    String[] allPeerNames = peer.getAllPeerNames();

    if (peerCount > 1) {
      // DEBUG
      // m_logger.writeChars("normalizeWithBroadcastingValues peerCount: "
      // + peerCount + " peerId: " + peerId + "\n");

      HashMap<Long, LinkedList<Integer>> senderList = new HashMap<Long, LinkedList<Integer>>();
      HashMap<Long, DoubleVector> normalizedValues = new HashMap<Long, DoubleVector>();
      HashMap<Long, Integer> normalizedValueCount = new HashMap<Long, Integer>();

      // Step 1)
      // send item matrices to selected peers
      for (Map.Entry<Long, PipesVectorWritable> item : m_itemsMatrix.entrySet()) {

        int toPeerId = item.getKey().hashCode() % peerCount;
        // don't send item to itself
        if (toPeerId != peerId) {
          // m_logger.writeChars("sendItem itemId: " + item.getKey()
          // + " toPeerId: " + toPeerId + " value: "
          // + item.getValue().getVector() + "\n");

          peer.send(allPeerNames[toPeerId], new ItemMessage(peerId, item
              .getKey().longValue(), item.getValue().getVector()));

        } else {
          normalizedValues.put(item.getKey(), item.getValue().getVector());
          normalizedValueCount.put(item.getKey(), 1);
          senderList.put(item.getKey(), new LinkedList<Integer>());
        }
      }
      peer.sync();

      // Step 2)
      // receive item matrices if this peer is selected
      ItemMessage msg;
      while ((msg = peer.getCurrentMessage()) != null) {
        int senderId = msg.getSenderId();
        long itemId = msg.getItemId();
        DoubleVector vector = msg.getVector();

        // m_logger.writeChars("receiveItem itemId: " + itemId + " fromPeerId: "
        // + senderId + " value: " + vector + "\n");

        normalizedValues.put(itemId, normalizedValues.get(itemId).add(vector));
        normalizedValueCount.put(itemId, normalizedValueCount.get(itemId) + 1);
        senderList.get(itemId).add(senderId);
      }

      // Step 3)
      // normalize
      for (Map.Entry<Long, DoubleVector> e : normalizedValues.entrySet()) {
        double count = normalizedValueCount.get(e.getKey());
        e.setValue(e.getValue().multiply(1.0 / count));
        // m_logger.writeChars("normalize itemId: " + e.getKey() + " NewValue: "
        // + e.getValue() + "\n");
      }

      // Step 4)
      // send back normalized values to senders
      for (Map.Entry<Long, DoubleVector> e : normalizedValues.entrySet()) {
        msg = new ItemMessage(peerId, e.getKey(), e.getValue());

        // send to interested peers
        Iterator<Integer> iter = senderList.get(e.getKey()).iterator();
        while (iter.hasNext()) {
          int toPeerId = iter.next();
          // m_logger.writeChars("sendNormalizedBack itemId: " + e.getKey()
          // + " toPeerId: " + toPeerId + " value: " + e.getValue() + "\n");
          peer.send(allPeerNames[toPeerId], msg);
        }

        // update items matrix
        m_itemsMatrix.put(e.getKey(), new PipesVectorWritable(e.getValue()));
        // m_logger.writeChars("updateItems itemId: " + e.getKey() + " value: "
        // + e.getValue() + "\n");
      }
      peer.sync();

      // Step 5)
      // receive already normalized and update data
      while ((msg = peer.getCurrentMessage()) != null) {
        // m_logger.writeChars("updateItems itemId: " + msg.getItemId()
        // + " fromPeerId: " + msg.getSenderId() + " value: "
        // + msg.getVector() + "\n");
        m_itemsMatrix.put(msg.getItemId(),
            new PipesVectorWritable(msg.getVector()));
      }

    }
  }

  /********************************* GPU *********************************/
  @Override
  public void setupGpu(
      BSPPeer<LongWritable, PipesVectorWritable, Text, PipesVectorWritable, ItemMessage> peer)
      throws IOException, SyncException, InterruptedException {

    long startTime = System.currentTimeMillis();

    this.m_conf = peer.getConfiguration();
    this.m_isDebuggingEnabled = m_conf.getBoolean(CONF_DEBUG, false);

    this.m_maxIterations = m_conf.getInt(OnlineCF.CONF_ITERATION_COUNT,
        OnlineCF.DFLT_ITERATION_COUNT);

    this.m_matrixRank = m_conf.getInt(OnlineCF.CONF_MATRIX_RANK,
        OnlineCF.DFLT_MATRIX_RANK);

    this.m_skipCount = m_conf.getInt(OnlineCF.CONF_SKIP_COUNT,
        OnlineCF.DFLT_SKIP_COUNT);

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
      BSPPeer<LongWritable, PipesVectorWritable, Text, PipesVectorWritable, ItemMessage> peer,
      Rootbeer rootbeer) throws IOException, SyncException,
      InterruptedException {

    long startTime = System.currentTimeMillis();

    // Fetch inputs
    collectInput(peer);

    // DEBUG
    if (m_isDebuggingEnabled) {
      m_logger.writeChars("collected: " + m_usersMatrix.size() + " users, "
          + m_itemsMatrix.size() + " items, " + m_preferences.size()
          + " preferences\n");
    }

    // Convert preferences to UserItemMap
    GpuUserItemMap userItemMap = new GpuUserItemMap(m_preferences.size());
    if (m_isDebuggingEnabled) {
      m_logger
          .writeChars("userItemMap: length: " + m_preferences.size() + "\n");
    }
    for (Preference<Long, Long> p : this.m_preferences) {
      userItemMap.put(p.getUserId(), p.getItemId(), p.getValue().get());
      if (m_isDebuggingEnabled) {
        m_logger.writeChars("userItemMap userId: '" + p.getUserId()
            + "' itemId: '" + p.getItemId() + "' value: '" + p.getValue().get()
            + "'\n");
      }
    }

    // Convert usersMatrix to GpuVectorMap
    GpuVectorMap usersMatrixMap = new GpuVectorMap(m_usersMatrix.size());
    if (m_isDebuggingEnabled) {
      m_logger.writeChars("usersMatrixMap: length: " + m_usersMatrix.size()
          + "\n");
    }
    Iterator<Entry<Long, PipesVectorWritable>> userIt = m_usersMatrix
        .entrySet().iterator();
    while (userIt.hasNext()) {
      Entry<Long, PipesVectorWritable> entry = userIt.next();
      long userId = entry.getKey();
      DoubleVector vector = entry.getValue().getVector();
      usersMatrixMap.put(userId, vector.toArray());

      if (m_isDebuggingEnabled) {
        m_logger.writeChars("usersMatrixMap userId: '" + userId + " value: '"
            + Arrays.toString(vector.toArray()) + "'\n");
      }
    }

    // Convert itemsMatrix to GpuVectorMap
    GpuVectorMap itemsMatrixMap = new GpuVectorMap(m_itemsMatrix.size());
    GpuIntegerMap counterMap = new GpuIntegerMap(m_itemsMatrix.size());

    if (m_isDebuggingEnabled) {
      m_logger.writeChars("itemsMatrixMap: length: " + m_itemsMatrix.size()
          + "\n");
    }
    Iterator<Entry<Long, PipesVectorWritable>> itemIt = m_itemsMatrix
        .entrySet().iterator();
    while (itemIt.hasNext()) {
      Entry<Long, PipesVectorWritable> entry = itemIt.next();
      long itemId = entry.getKey();
      DoubleVector vector = entry.getValue().getVector();
      itemsMatrixMap.put(itemId, vector.toArray());
      counterMap.put(itemId, 0);

      if (m_isDebuggingEnabled) {
        m_logger.writeChars("itemsMatrixMap itemId: '" + itemId + " value: '"
            + Arrays.toString(vector.toArray()) + "'\n");
      }
    }

    // Run GPU Kernels
    OnlineCFTrainHybridKernel kernel = new OnlineCFTrainHybridKernel(
        userItemMap, usersMatrixMap, itemsMatrixMap, counterMap,
        m_usersMatrix.size(), m_itemsMatrix.size(), ALPHA, m_matrixRank,
        m_maxIterations, m_skipCount, peer.getNumPeers(), peer.getPeerIndex(),
        peer.getAllPeerNames());

    Context context = rootbeer.createDefaultContext();
    Stopwatch watch = new Stopwatch();
    watch.start();
    rootbeer.run(kernel, new ThreadConfig(m_blockSize, m_gridSize, m_blockSize
        * m_gridSize), context);
    watch.stop();

    // Save Model
    // save user information
    if (m_isDebuggingEnabled) {
      m_logger.writeChars("saving " + m_usersMatrix.size() + " users\n");
    }
    for (Long userId : m_usersMatrix.keySet()) {
      if (m_isDebuggingEnabled) {
        m_logger.writeChars("user: " + userId + " vector: "
            + Arrays.toString(usersMatrixMap.get(userId)) + "\n");
      }
      peer.write(new Text("u" + userId), new PipesVectorWritable(
          new DenseDoubleVector(usersMatrixMap.get(userId))));
    }

    // save item information
    if (m_isDebuggingEnabled) {
      m_logger.writeChars("saving " + m_itemsMatrix.size() + " items\n");
    }
    for (Long itemId : m_itemsMatrix.keySet()) {
      if (m_isDebuggingEnabled) {
        m_logger.writeChars("item: " + itemId + " vector: "
            + Arrays.toString(itemsMatrixMap.get(itemId)) + "\n");
      }
      peer.write(new Text("i" + itemId), new PipesVectorWritable(
          new DenseDoubleVector(itemsMatrixMap.get(itemId))));
    }

    this.m_bspTimeGpu = System.currentTimeMillis() - startTime;

    // Set times in config
    HamaConfiguration config = peer.getConfiguration();
    config.setLong("setup.time", m_setupTimeCpu);
    config.setLong("bsp.time", m_bspTimeCpu);

    // Logging
    if (m_isDebuggingEnabled) {
      m_logger.writeChars("OnlineCFTrainHybridBSP.bspGpu executed on GPU!\n");
      m_logger.writeChars("OnlineCFTrainHybridBSP.bspGpu blockSize: "
          + m_blockSize + " gridSize: " + m_gridSize + "\n");
      m_logger.writeChars("OnlineCFTrainHybridBSP,setupTimeGpu="
          + this.m_setupTimeGpu + " ms\n");
      m_logger.writeChars("OnlineCFTrainHybridBSP,setupTimeGpu="
          + (this.m_setupTimeGpu / 1000.0) + " seconds\n");
      m_logger.writeChars("OnlineCFTrainHybridBSP,bspTimeGpu="
          + this.m_bspTimeGpu + " ms\n");
      m_logger.writeChars("OnlineCFTrainHybridBSP,bspTimeGpu="
          + (this.m_bspTimeGpu / 1000.0) + " seconds\n");

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
        m_logger.writeChars("GPUTime: " + watch.elapsedTimeMillis() + " ms"
            + "\n");
      }

      m_logger.close();
    }

    // Logging
    List<StatsRow> stats = context.getStats();
    for (StatsRow row : stats) {
      LOG.info("  StatsRow:");
      LOG.info("    serial time: " + row.getSerializationTime());
      LOG.info("    exec time: " + row.getExecutionTime());
      LOG.info("    deserial time: " + row.getDeserializationTime());
      LOG.info("    num blocks: " + row.getNumBlocks());
      LOG.info("    num threads: " + row.getNumThreads());
      LOG.info("GPUTime: " + watch.elapsedTimeMillis() + " ms");
    }
    LOG.info("OnlineCFTrainHybridBSP.bspGpu executed on GPU!");
    LOG.info("OnlineCFTrainHybridBSP.bspGpu blockSize: " + m_blockSize
        + " gridSize: " + m_gridSize);
    LOG.info("OnlineCFTrainHybridBSP,setupTimeGpu=" + this.m_setupTimeGpu
        + " ms");
    LOG.info("OnlineCFTrainHybridBSP,setupTimeGpu="
        + (this.m_setupTimeGpu / 1000.0) + " seconds");
    LOG.info("OnlineCFTrainHybridBSP,bspTimeGpu=" + this.m_bspTimeGpu + " ms");
    LOG.info("OnlineCFTrainHybridBSP,bspTimeGpu="
        + (this.m_bspTimeGpu / 1000.0) + " seconds");

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
    int numBspTask = 1; // CPU + GPU tasks
    int numGpuBspTask = 1; // GPU tasks
    int blockSize = BLOCK_SIZE;
    int gridSize = GRID_SIZE;

    int maxIteration = 3; // 150;
    int matrixRank = 3;
    int skipCount = 1;

    boolean useTestExampleInput = true;
    boolean isDebugging = true;
    String inputFile = "";

    Configuration conf = new HamaConfiguration();
    FileSystem fs = FileSystem.get(conf);

    // Set numBspTask to maxTasks
    // BSPJobClient jobClient = new BSPJobClient(conf);
    // ClusterStatus cluster = jobClient.getClusterStatus(true);
    // numBspTask = cluster.getMaxTasks();

    if ((args.length > 0) && (args.length >= 9)) {
      numBspTask = Integer.parseInt(args[0]);
      numGpuBspTask = Integer.parseInt(args[1]);
      blockSize = Integer.parseInt(args[2]);
      gridSize = Integer.parseInt(args[3]);

      maxIteration = Integer.parseInt(args[4]);
      matrixRank = Integer.parseInt(args[5]);
      skipCount = Integer.parseInt(args[6]);

      useTestExampleInput = Boolean.parseBoolean(args[7]);
      isDebugging = Boolean.parseBoolean(args[8]);

      // optional parameters
      if (args.length > 9) {
        inputFile = args[9];
      }

    } else {
      System.out.println("Wrong argument size!");
      System.out.println("    Argument1=numBspTask");
      System.out.println("    Argument2=numGpuBspTask");
      System.out.println("    Argument3=blockSize");
      System.out.println("    Argument4=gridSize");

      System.out
          .println("    Argument5=maxIterations | Number of maximal iterations ("
              + maxIteration + ")");
      System.out.println("    Argument6=matrixRank | matrixRank (" + matrixRank
          + ")");
      System.out.println("    Argument7=skipCount | skipCount (" + skipCount
          + ")");
      System.out
          .println("    Argument8=testExample | Use testExample input (true|false=default)");
      System.out
          .println("    Argument9=debug | Enable debugging (true|false=default)");
      System.out
          .println("    Argument10=inputFile (optional) | MovieLens inputFile");
      return;
    }

    // Check if inputFile exists
    if ((!inputFile.isEmpty()) && (!new File(inputFile).exists())) {
      System.out.println("Error: inputFile: " + inputFile + " does not exist!");
      return;
    }

    // Check if blockSize < matrixRank when using GPU
    if ((numGpuBspTask > 0) && (blockSize < matrixRank)) {
      System.out.println("Error: BlockSize < matrixRank");
      return;
    }

    // Set config variables
    conf.setBoolean(CONF_DEBUG, isDebugging);
    conf.setBoolean("hama.pipes.logging", isDebugging);
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
    List<Preference<Long, Long>> testPrefs = null;
    if (useTestExampleInput) {

      if (inputFile.isEmpty()) { // no inputFile

        Path preferencesIn = new Path(CONF_INPUT_DIR, "preferences_in.seq");
        testPrefs = prepareInputData(conf, fs, CONF_INPUT_DIR, preferencesIn);

      } else { // parse inputFile and return first entries for testing

        Path preferencesIn = new Path(CONF_INPUT_DIR, "preferences_in.seq");
        testPrefs = convertInputData(conf, fs, CONF_INPUT_DIR, preferencesIn,
            inputFile);
      }
    }

    BSPJob job = createOnlineCFTrainHybridBSPConf(conf, CONF_INPUT_DIR,
        CONF_OUTPUT_DIR);

    long startTime = System.currentTimeMillis();
    if (job.waitForCompletion(true)) {

      LOG.info("Job Finished in " + (System.currentTimeMillis() - startTime)
          / 1000.0 + " seconds");
      // TODO configuration
      LOG.info("OnlineCFTrainHybridBSP,setupTimeCpu="
          + job.getConfiguration().get("setup.time") + " ms\n");
      LOG.info("OnlineCFTrainHybridBSP,bspTimeCpu="
          + job.getConfiguration().get("bsp.time") + " ms\n");

      if (useTestExampleInput) {
        OnlineCF recommender = new OnlineCF();
        recommender.load(CONF_OUTPUT_DIR.toString(), false);

        int correct = 0;
        for (Preference<Long, Long> test : testPrefs) {
          double actual = test.getValue().get();
          double estimated = recommender.estimatePreference(test.getUserId(),
              test.getItemId());

          if (testPrefs.size() <= 20) {
            LOG.info("estimatePreference of userId: " + test.getUserId()
                + " itemId: " + test.getItemId() + " actual: " + actual);
            LOG.info("estimated: " + estimated + " error: "
                + Math.abs(actual - estimated));
          }
          correct += (Math.abs(actual - estimated) < 0.5) ? 1 : 0;
        }

        LOG.info("assertEquals(expected: " + (testPrefs.size() * 0.75) + " == "
            + correct + " actual) with delta: 1");
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
  public static List<Preference<Long, Long>> prepareInputData(
      Configuration conf, FileSystem fs, Path in, Path preferencesIn)
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

    List<Preference<Long, Long>> test_prefs = new ArrayList<Preference<Long, Long>>();
    test_prefs.add(new Preference<Long, Long>(1l, 3l, 3.5));
    test_prefs.add(new Preference<Long, Long>(2l, 4l, 1));
    test_prefs.add(new Preference<Long, Long>(3l, 4l, 1));
    test_prefs.add(new Preference<Long, Long>(3l, 5l, 3.5));

    // Delete input files if already exist
    if (fs.exists(in)) {
      fs.delete(in, true);
    }
    if (fs.exists(preferencesIn)) {
      fs.delete(preferencesIn, true);
    }

    final SequenceFile.Writer prefWriter = SequenceFile.createWriter(fs, conf,
        preferencesIn, LongWritable.class, PipesVectorWritable.class,
        CompressionType.NONE);

    for (Preference<Integer, Integer> taste : train_prefs) {
      double values[] = new double[2];
      values[0] = taste.getItemId();
      values[1] = taste.getValue().get();
      prefWriter.append(new LongWritable(taste.getUserId()),
          new PipesVectorWritable(new DenseDoubleVector(values)));
    }
    prefWriter.close();

    return test_prefs;
  }

  /**
   * convertInputData
   * 
   */
  public static List<Preference<Long, Long>> convertInputData(
      Configuration conf, FileSystem fs, Path in, Path preferencesIn,
      String inputFile) throws IOException {

    List<Preference<Long, Long>> test_prefs = new ArrayList<Preference<Long, Long>>();
    int maxTestPrefs = 10;

    // Delete input files if already exist
    if (fs.exists(in)) {
      fs.delete(in, true);
    }
    if (fs.exists(preferencesIn)) {
      fs.delete(preferencesIn, true);
    }

    final SequenceFile.Writer prefWriter = SequenceFile.createWriter(fs, conf,
        preferencesIn, LongWritable.class, PipesVectorWritable.class,
        CompressionType.NONE);

    BufferedReader br = new BufferedReader(new FileReader(inputFile));
    String line;
    while ((line = br.readLine()) != null) {
      String[] values = line.split("\\t");
      long userId = Long.parseLong(values[0]);
      long itemId = Long.parseLong(values[1]);
      double rating = Double.parseDouble(values[2]);
      // System.out.println("userId: " + userId + " itemId: " + itemId
      // + " rating: " + rating);

      double vector[] = new double[2];
      vector[0] = itemId;
      vector[1] = rating;
      prefWriter.append(new LongWritable(userId), new PipesVectorWritable(
          new DenseDoubleVector(vector)));

      // Add test preferences
      maxTestPrefs--;
      if (maxTestPrefs > 0) {
        test_prefs.add(new Preference<Long, Long>(userId, itemId, rating));
      }

    }
    br.close();
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
