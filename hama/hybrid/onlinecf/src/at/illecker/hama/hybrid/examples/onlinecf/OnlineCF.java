/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.PriorityQueue;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.Text;
import org.apache.hama.HamaConfiguration;
import org.apache.hama.bsp.BSPJob;
import org.apache.hama.commons.io.PipesVectorWritable;
import org.apache.hama.commons.io.VectorWritable;
import org.apache.hama.commons.math.DoubleVector;
import org.apache.hama.commons.math.SquareVectorFunction;
import org.apache.hama.commons.util.KeyValuePair;
import org.apache.hama.ml.recommendation.Preference;
import org.apache.hama.ml.recommendation.Recommender;
import org.apache.hama.ml.recommendation.RecommenderIO;
import org.apache.hama.ml.recommendation.cf.function.MeanAbsError;
import org.apache.hama.ml.recommendation.cf.function.OnlineUpdate.InputStructure;

public class OnlineCF implements Recommender, RecommenderIO {

  protected static Log LOG = LogFactory.getLog(OnlineCF.class);

  public static final String CONF_ITERATION_COUNT = "ml.recommender.cf.iterations";
  public static final String CONF_MATRIX_RANK = "ml.recommender.cf.rank";
  public static final String CONF_SKIP_COUNT = "ml.recommender.cf.skip.count";
  public static final String CONF_ONLINE_UPDATE_FUNCTION = "ml.recommender.cf.func.ou";

  public static final String CONF_INPUT_PATH = "ml.recommender.cf.input.path";
  public static final String CONF_OUTPUT_PATH = "ml.recommender.cf.output.path";

  // default values
  public static final int DFLT_ITERATION_COUNT = 100;
  public static final int DFLT_MATRIX_RANK = 10;
  public static final int DFLT_SKIP_COUNT = 5;

  public static final String DFLT_MODEL_USER_DELIM = "u";
  public static final String DFLT_MODEL_ITEM_DELIM = "i";

  HamaConfiguration conf = new HamaConfiguration();

  // used only if model is loaded in memory
  private HashMap<Long, PipesVectorWritable> m_modelUserFactorizedValues = new HashMap<Long, PipesVectorWritable>();
  private HashMap<Long, PipesVectorWritable> m_modelItemFactorizedValues = new HashMap<Long, PipesVectorWritable>();
  private String m_modelPath = null;
  private boolean m_isLazyLoadModel = false;

  /**
   * iteration count for matrix factorization
   * 
   * @param count - iteration count
   */
  public void setIteration(int count) {
    conf.setInt(CONF_ITERATION_COUNT, count);
  }

  /**
   * Setting matrix rank for factorization
   * 
   * @param rank - matrix rank
   */
  public void setMatrixRank(int rank) {
    conf.setInt(CONF_MATRIX_RANK, rank);
  }

  /**
   * Online CF needs normalization of values this configuration is set after how
   * many iteration of calculation values should be normalized between different
   * items
   * 
   * @param count - skip count before doing convergence
   */
  public void setSkipCount(int count) {
    conf.setInt(CONF_SKIP_COUNT, count);
  }

  @Override
  public void setInputPreferences(String path) {
    conf.set(CONF_INPUT_PATH, path);
  }

  @Override
  public void setOutputPath(String path) {
    conf.set(CONF_OUTPUT_PATH, path);
  }

  @Override
  public void setInputUserFeatures(String path) {
    // TODO Auto-generated method stub

  }

  @Override
  public void setInputItemFeatures(String path) {
    // TODO Auto-generated method stub

  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean train() {
    try {
      String input = conf.get(CONF_INPUT_PATH, null);
      String output = conf.get(CONF_OUTPUT_PATH, null);

      BSPJob job = OnlineCFTrainHybridBSP.createOnlineCFTrainHybridBSPConf(
          new Path(input), new Path(output));

      return job.waitForCompletion(true);

    } catch (IOException e) {
      e.printStackTrace();
    } catch (InterruptedException e) {
      e.printStackTrace();
    } catch (ClassNotFoundException e) {
      e.printStackTrace();
    }
    return false;
  }

  @Override
  public boolean save() {
    // default behaivor is saving after training,
    // we cannot hold model in memory after bsp
    return true;
  }

  @Override
  public boolean load(String path, boolean lazy) {
    this.m_isLazyLoadModel = lazy;
    this.m_modelPath = path;

    if (lazy == false) {
      Path dataPath = new Path(m_modelPath);
      Configuration conf = new Configuration();
      try {
        FileSystem fs = dataPath.getFileSystem(conf);
        LinkedList<Path> files = new LinkedList<Path>();

        if (!fs.exists(dataPath)) {
          this.m_isLazyLoadModel = false;
          this.m_modelPath = null;
          return false;
        }

        if (!fs.isFile(dataPath)) {
          for (int i = 0; i < 100000; i++) {
            Path partFile = new Path(m_modelPath + "/part-"
                + String.valueOf(100000 + i).substring(1, 6));
            if (fs.exists(partFile)) {
              files.add(partFile);
            } else {
              break;
            }
          }
        } else {
          files.add(dataPath);
        }

        LOG.info("loading model from " + path);
        for (Path file : files) {
          SequenceFile.Reader reader = new SequenceFile.Reader(fs, file, conf);

          Text key = new Text();
          PipesVectorWritable value = new PipesVectorWritable();
          String strKey = null;
          Long actualKey = null;
          String firstSymbol = null;

          while (reader.next(key, value) != false) {
            strKey = key.toString();
            firstSymbol = strKey.substring(0, 1);
            try {
              actualKey = Long.valueOf(strKey.substring(1));
            } catch (Exception e) {
              actualKey = new Long(0);
            }

            if (firstSymbol.equals(OnlineCF.DFLT_MODEL_ITEM_DELIM)) {
              // LOG.info("loaded itemId: " + actualKey + " itemVector: "
              // + value.getVector());
              m_modelItemFactorizedValues.put(actualKey,
                  new PipesVectorWritable(value));
            } else if (firstSymbol.equals(OnlineCF.DFLT_MODEL_USER_DELIM)) {
              // LOG.info("loaded userId: " + actualKey + " userVector: "
              // + value.getVector());
              m_modelUserFactorizedValues.put(actualKey,
                  new PipesVectorWritable(value));
            } else {
              // unknown
              continue;
            }
          }
          reader.close();
        }

        LOG.info("loaded: " + m_modelUserFactorizedValues.size() + " users, "
            + m_modelItemFactorizedValues.size() + " items");
        // for (Long user : m_modelUserFactorizedValues.keySet()) {
        // LOG.info("userId: " + user + " userVector: "
        // + m_modelUserFactorizedValues.get(user));
        // }
        // for (Long item : m_modelItemFactorizedValues.keySet()) {
        // LOG.info("itemId: " + item + " itemVector: "
        // + m_modelItemFactorizedValues.get(item));
        // }

      } catch (Exception e) {
        e.printStackTrace();
        this.m_isLazyLoadModel = false;
        this.m_modelPath = null;
        return false;
      }
    }
    return true;
  }

  @Override
  public double estimatePreference(long userId, long itemId) {
    if (m_isLazyLoadModel == false) {

      InputStructure e = new InputStructure();
      e.item = this.m_modelItemFactorizedValues.get(itemId);
      e.user = this.m_modelUserFactorizedValues.get(userId);
      if (e.item == null || e.user == null) {
        return 0;
      }

      return new MeanAbsError().predict(e);
    }
    return 0;
  }

  @Override
  public List<Preference<Long, Long>> getMostPreferredItems(long userId,
      int count) {

    // TODO
    // needs features
    return null;
  }

  public double calculateUserSimilarity(long user1, long user2) {
    // TODO Auto-generated method stub
    return 0;
  }

  public List<KeyValuePair<Long, Double>> getMostSimilarUsers(long user,
      int count) {

    Comparator<KeyValuePair<Long, Double>> similarityComparator = new Comparator<KeyValuePair<Long, Double>>() {

      @Override
      public int compare(KeyValuePair<Long, Double> arg0,
          KeyValuePair<Long, Double> arg1) {
        double difference = arg0.getValue().doubleValue()
            - arg1.getValue().doubleValue();
        return (int) (100000 * difference);
      }
    };

    PriorityQueue<KeyValuePair<Long, Double>> queue = new PriorityQueue<KeyValuePair<Long, Double>>(
        count, similarityComparator);

    LinkedList<KeyValuePair<Long, Double>> results = new LinkedList<KeyValuePair<Long, Double>>();
    for (Long candidateUser : m_modelUserFactorizedValues.keySet()) {
      double similarity = calculateUserSimilarity(user, candidateUser);
      KeyValuePair<Long, Double> targetUser = new KeyValuePair<Long, Double>(
          candidateUser, similarity);
      queue.add(targetUser);
    }
    results.addAll(queue);
    return results;
  }

  public double calculateItemSimilarity(long item1, long item2) {
    VectorWritable itm1 = this.m_modelUserFactorizedValues.get(Long
        .valueOf(item1));
    VectorWritable itm2 = this.m_modelUserFactorizedValues.get(Long
        .valueOf(item2));
    if (itm1 == null || itm2 == null) {
      return Double.MAX_VALUE;
    }

    DoubleVector itm1Vector = itm1.getVector();
    DoubleVector itm2Vector = itm2.getVector();

    // Euclidean distance
    return Math.pow(
        itm1Vector.subtract(itm2Vector)
            .applyToElements(new SquareVectorFunction()).sum(), 0.5);
  }

  public List<KeyValuePair<Long, Double>> getMostSimilarItems(long item,
      int count) {

    Comparator<KeyValuePair<Long, Double>> similarityComparator = new Comparator<KeyValuePair<Long, Double>>() {

      @Override
      public int compare(KeyValuePair<Long, Double> arg0,
          KeyValuePair<Long, Double> arg1) {
        double difference = arg0.getValue().doubleValue()
            - arg1.getValue().doubleValue();
        return (int) (100000 * difference);
      }
    };
    PriorityQueue<KeyValuePair<Long, Double>> queue = new PriorityQueue<KeyValuePair<Long, Double>>(
        count, similarityComparator);
    LinkedList<KeyValuePair<Long, Double>> results = new LinkedList<KeyValuePair<Long, Double>>();
    for (Long candidateItem : m_modelItemFactorizedValues.keySet()) {
      double similarity = calculateItemSimilarity(item, candidateItem);
      KeyValuePair<Long, Double> targetItem = new KeyValuePair<Long, Double>(
          candidateItem, similarity);
      queue.add(targetItem);
    }
    results.addAll(queue);
    return results;
  }

}
