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
package at.illecker.hama.hybrid.examples.kmeans;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.SequenceFile.CompressionType;
import org.apache.hama.HamaConfiguration;
import org.apache.hama.bsp.BSPJob;
import org.apache.hama.bsp.BSPJobClient;
import org.apache.hama.bsp.BSPPeer;
import org.apache.hama.bsp.ClusterStatus;
import org.apache.hama.bsp.FileOutputFormat;
import org.apache.hama.bsp.SequenceFileInputFormat;
import org.apache.hama.bsp.SequenceFileOutputFormat;
import org.apache.hama.bsp.gpu.HybridBSP;
import org.apache.hama.bsp.sync.SyncException;
import org.apache.hama.commons.io.PipesVectorWritable;
import org.apache.hama.commons.io.VectorWritable;
import org.apache.hama.commons.math.DenseDoubleVector;
import org.apache.hama.commons.math.DoubleVector;

import com.google.common.base.Preconditions;

import edu.syr.pcpratts.rootbeer.runtime.Rootbeer;
import edu.syr.pcpratts.rootbeer.runtime.StatsRow;
import edu.syr.pcpratts.rootbeer.runtime.util.Stopwatch;

public class KMeansHybridBSP
    extends
    HybridBSP<PipesVectorWritable, NullWritable, IntWritable, PipesVectorWritable, CenterMessage> {

  private static final Log LOG = LogFactory.getLog(KMeansHybridBSP.class);

  public static final String CONF_DEBUG = "kmeans.is.debugging";
  public static final String CONF_MAX_ITERATIONS_KEY = "kmeans.max.iterations";
  public static final String CONF_CENTER_IN_PATH = "kmeans.center.in.path";
  public static final String CONF_CENTER_OUT_PATH = "kmeans.center.out.path";
  private static final Path CONF_TMP_DIR = new Path(
      "output/hama/hybrid/examples/kmeans/hybrid-" + System.currentTimeMillis());
  private static final Path CONF_INPUT_DIR = new Path(CONF_TMP_DIR, "input");
  private static final Path CONF_OUTPUT_DIR = new Path(CONF_TMP_DIR, "output");

  public static final String CONF_BLOCKSIZE = "kmeans.hybrid.blockSize";
  public static final String CONF_GRIDSIZE = "kmeans.hybrid.gridSize";

  // gridSize = amount of blocks and multiprocessors
  public static final int GRID_SIZE = 14;
  // blockSize = amount of threads
  public static final int BLOCK_SIZE = 1024;

  private boolean m_isDebuggingEnabled;
  private FSDataOutputStream m_logger;

  // a task local copy of our cluster centers
  private DoubleVector[] centers;
  // simple cache to speed up computation, because the algorithm is disk based
  private List<DoubleVector> cache;
  // numbers of maximum iterations to do
  private int maxIterations;

  private Configuration conf;

  private int m_gridSize;
  private int m_blockSize;

  @Override
  public Class<CenterMessage> getMessageClass() {
    return CenterMessage.class;
  }

  /********************************* CPU *********************************/
  @Override
  public void setup(
      BSPPeer<PipesVectorWritable, NullWritable, IntWritable, PipesVectorWritable, CenterMessage> peer)
      throws IOException {

    conf = peer.getConfiguration();
    this.m_isDebuggingEnabled = conf.getBoolean(CONF_DEBUG, false);

    // Init logging
    if (m_isDebuggingEnabled) {
      try {
        FileSystem fs = FileSystem.get(conf);
        m_logger = fs.create(new Path(FileOutputFormat
            .getOutputPath(new BSPJob((HamaConfiguration) conf))
            + "/BSP_"
            + peer.getTaskId() + ".log"));

      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    Path centroids = new Path(conf.get(CONF_CENTER_IN_PATH));
    FileSystem fs = FileSystem.get(conf);

    final ArrayList<DoubleVector> centers = new ArrayList<DoubleVector>();
    SequenceFile.Reader reader = null;
    try {
      reader = new SequenceFile.Reader(fs, centroids, conf);
      VectorWritable key = new VectorWritable();
      NullWritable value = NullWritable.get();
      while (reader.next(key, value)) {
        DoubleVector center = key.getVector();
        centers.add(center);
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    } finally {
      if (reader != null) {
        reader.close();
      }
    }

    // TODO rename precondition
    Preconditions.checkArgument(centers.size() > 0,
        "Centers file must contain at least a single center!");

    this.centers = centers.toArray(new DoubleVector[centers.size()]);

    // TODO
    // distanceMeasurer = new EuclidianDistance();

    maxIterations = conf.getInt(CONF_MAX_ITERATIONS_KEY, -1);

    // normally we want to rely on OS caching, but if not, we can cache in heap
    cache = new ArrayList<DoubleVector>();
  }

  @Override
  public void bsp(
      BSPPeer<PipesVectorWritable, NullWritable, IntWritable, PipesVectorWritable, CenterMessage> peer)
      throws IOException, SyncException, InterruptedException {

    long converged;
    while (true) {
      assignCenters(peer);

      peer.sync();

      converged = updateCenters(peer);

      peer.reopenInput();
      if (converged == 0)
        break;
      if (maxIterations > 0 && maxIterations < peer.getSuperstepCount())
        break;
    }
    LOG.info("Finished! Writing the assignments...");
    recalculateAssignmentsAndWrite(peer);
    LOG.info("Done.");

    // Logging
    // if (m_isDebuggingEnabled) {
    // m_logger.writeChars("bsp,input,key=" + aKey + ",value="
    // + aVector.getVector().toString() + "\n");
    // m_logger.flush();
    // }
  }

  private long updateCenters(
      BSPPeer<VectorWritable, NullWritable, IntWritable, VectorWritable, CenterMessage> peer)
      throws IOException {
    // this is the update step
    DoubleVector[] msgCenters = new DoubleVector[centers.length];
    int[] incrementSum = new int[centers.length];
    CenterMessage msg;
    // basically just summing incoming vectors
    while ((msg = peer.getCurrentMessage()) != null) {
      DoubleVector oldCenter = msgCenters[msg.getCenterIndex()];
      DoubleVector newCenter = msg.getData();
      incrementSum[msg.getCenterIndex()] += msg.getIncrementCounter();
      if (oldCenter == null) {
        msgCenters[msg.getCenterIndex()] = newCenter;
      } else {
        msgCenters[msg.getCenterIndex()] = oldCenter.addUnsafe(newCenter);
      }
    }
    // divide by how often we globally summed vectors
    for (int i = 0; i < msgCenters.length; i++) {
      // and only if we really have an update for c
      if (msgCenters[i] != null) {
        msgCenters[i] = msgCenters[i].divide(incrementSum[i]);
      }
    }
    // finally check for convergence by the absolute difference
    long convergedCounter = 0L;
    for (int i = 0; i < msgCenters.length; i++) {
      final DoubleVector oldCenter = centers[i];
      if (msgCenters[i] != null) {
        double calculateError = oldCenter.subtractUnsafe(msgCenters[i]).abs()
            .sum();
        if (calculateError > 0.0d) {
          centers[i] = msgCenters[i];
          convergedCounter++;
        }
      }
    }
    return convergedCounter;
  }

  private void assignCenters(
      BSPPeer<VectorWritable, NullWritable, IntWritable, VectorWritable, CenterMessage> peer)
      throws IOException {
    // each task has all the centers, if a center has been updated it
    // needs to be broadcasted.
    final DoubleVector[] newCenterArray = new DoubleVector[centers.length];
    final int[] summationCount = new int[centers.length];

    // if our cache is not enabled, iterate over the disk items
    if (cache == null) {
      // we have an assignment step
      final NullWritable value = NullWritable.get();
      final VectorWritable key = new VectorWritable();
      while (peer.readNext(key, value)) {
        assignCentersInternal(newCenterArray, summationCount, key.getVector()
            .deepCopy());
      }
    } else {
      // if our cache is enabled but empty, we have to read it from disk first
      if (cache.isEmpty()) {
        final NullWritable value = NullWritable.get();
        final VectorWritable key = new VectorWritable();
        while (peer.readNext(key, value)) {
          DoubleVector deepCopy = key.getVector().deepCopy();
          cache.add(deepCopy);
          // but do the assignment directly
          assignCentersInternal(newCenterArray, summationCount, deepCopy);
        }
      } else {
        // now we can iterate in memory and check against the centers
        for (DoubleVector v : cache) {
          assignCentersInternal(newCenterArray, summationCount, v);
        }
      }
    }

    // now send messages about the local updates to each other peer
    for (int i = 0; i < newCenterArray.length; i++) {
      if (newCenterArray[i] != null) {
        for (String peerName : peer.getAllPeerNames()) {
          peer.send(peerName, new CenterMessage(i, summationCount[i],
              newCenterArray[i]));
        }
      }
    }
  }

  private void assignCentersInternal(final DoubleVector[] newCenterArray,
      final int[] summationCount, final DoubleVector key) {
    final int lowestDistantCenter = getNearestCenter(key);
    final DoubleVector clusterCenter = newCenterArray[lowestDistantCenter];

    if (clusterCenter == null) {
      newCenterArray[lowestDistantCenter] = key;
    } else {
      // add the vector to the center
      newCenterArray[lowestDistantCenter] = newCenterArray[lowestDistantCenter]
          .addUnsafe(key);
      summationCount[lowestDistantCenter]++;
    }
  }

  private int getNearestCenter(DoubleVector key) {
    int lowestDistantCenter = 0;
    double lowestDistance = Double.MAX_VALUE;

    for (int i = 0; i < centers.length; i++) {
      final double estimatedDistance = distanceMeasurer.measureDistance(
          centers[i], key);
      // check if we have a can assign a new center, because we
      // got a lower distance
      if (estimatedDistance < lowestDistance) {
        lowestDistance = estimatedDistance;
        lowestDistantCenter = i;
      }
    }
    return lowestDistantCenter;
  }

  private void recalculateAssignmentsAndWrite(
      BSPPeer<VectorWritable, NullWritable, IntWritable, VectorWritable, CenterMessage> peer)
      throws IOException {
    final NullWritable value = NullWritable.get();
    // also use our cache to speed up the final writes if exists
    if (cache == null) {
      final VectorWritable key = new VectorWritable();
      IntWritable keyWrite = new IntWritable();
      while (peer.readNext(key, value)) {
        final int lowestDistantCenter = getNearestCenter(key.getVector());
        keyWrite.set(lowestDistantCenter);
        peer.write(keyWrite, key);
      }
    } else {
      IntWritable keyWrite = new IntWritable();
      for (DoubleVector v : cache) {
        final int lowestDistantCenter = getNearestCenter(v);
        keyWrite.set(lowestDistantCenter);
        peer.write(keyWrite, new VectorWritable(v));
      }
    }
    // just on the first task write the centers to filesystem to prevent
    // collisions
    if (peer.getPeerName().equals(peer.getPeerName(0))) {
      String pathString = conf.get(CONF_CENTER_OUT_PATH);
      if (pathString != null) {
        final SequenceFile.Writer dataWriter = SequenceFile.createWriter(
            FileSystem.get(conf), conf, new Path(pathString),
            VectorWritable.class, NullWritable.class, CompressionType.NONE);
        for (DoubleVector center : centers) {
          dataWriter.append(new VectorWritable(center), value);
        }
        dataWriter.close();
      }
    }
  }

  /********************************* GPU *********************************/
  @Override
  public void setupGpu(
      BSPPeer<PipesVectorWritable, NullWritable, IntWritable, PipesVectorWritable, CenterMessage> peer)
      throws IOException, SyncException, InterruptedException {

    HamaConfiguration conf = peer.getConfiguration();
    this.m_isDebuggingEnabled = conf.getBoolean(CONF_DEBUG, false);

    this.m_blockSize = Integer.parseInt(peer.getConfiguration().get(
        CONF_BLOCKSIZE));

    this.m_gridSize = Integer.parseInt(peer.getConfiguration().get(
        CONF_GRIDSIZE));

    // Init logging
    if (m_isDebuggingEnabled) {
      try {
        FileSystem fs = FileSystem.get(conf);
        m_logger = fs.create(new Path(FileOutputFormat
            .getOutputPath(new BSPJob((HamaConfiguration) conf))
            + "/BSP_"
            + peer.getTaskId() + ".log"));

      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    // TODO
  }

  @Override
  public void bspGpu(
      BSPPeer<PipesVectorWritable, NullWritable, IntWritable, PipesVectorWritable, CenterMessage> peer,
      Rootbeer rootbeer) throws IOException, SyncException,
      InterruptedException {

    // Logging
    if (m_isDebuggingEnabled) {
      m_logger
          .writeChars("MatrixMultiplicationHybrid.bspGpu executed on GPU!\n");
      m_logger.writeChars("MatrixMultiplicationHybrid.bspGpu blockSize: "
          + m_blockSize + " gridSize: " + m_gridSize + "\n");
    }

    KMeansHybridKernel kernel = new KMeansHybridKernel();
    // 1 Kernel within 1 Block
    rootbeer.setThreadConfig(1, 1, 1);
    // rootbeer.setThreadConfig(m_blockSize, m_gridSize, m_blockSize *
    // m_gridSize);

    // Run GPU Kernels
    Stopwatch watch = new Stopwatch();
    watch.start();
    rootbeer.runAll(kernel);
    watch.stop();

    List<StatsRow> stats = rootbeer.getStats();
    for (StatsRow row : stats) {
      m_logger.writeChars("  StatsRow:\n");
      m_logger.writeChars("    init time: " + row.getInitTime() + "\n");
      m_logger.writeChars("    serial time: " + row.getSerializationTime()
          + "\n");
      m_logger.writeChars("    exec time: " + row.getExecutionTime() + "\n");
      m_logger.writeChars("    deserial time: " + row.getDeserializationTime()
          + "\n");
      m_logger.writeChars("    num blocks: " + row.getNumBlocks() + "\n");
      m_logger.writeChars("    num threads: " + row.getNumThreads() + "\n");
    }

    m_logger.writeChars("MatrixMultiplicationHybrid,GPUTime="
        + watch.elapsedTimeMillis() + "ms\n");
    m_logger.writeChars("MatrixMultiplicationHybrid,peerName: '"
        + kernel.m_peerName + "'\n");
    m_logger.writeChars("MatrixMultiplicationHybrid,masterTask: '"
        + kernel.m_masterTask + "'\n");
    m_logger.close();
  }

  static void printOutput(Configuration conf) throws IOException {
    FileSystem fs = CONF_OUTPUT_DIR.getFileSystem(conf);
    FileStatus[] files = fs.listStatus(CONF_OUTPUT_DIR);
    for (int i = 0; i < files.length; i++) {
      if (files[i].getLen() > 0) {
        if (files[i].getPath().getName().endsWith(".log")) {
          System.out.println("File " + files[i].getPath());
          // FSDataInputStream in = fs.open(files[i].getPath());
          // IOUtils.copyBytes(in, System.out, conf, false);
          // in.close();
        }
      }
    }
    // fs.delete(FileOutputFormat.getOutputPath(job), true);
  }

  public static BSPJob createKMeansHybridBSPConf(Path inPath, Path outPath)
      throws IOException {
    return createKMeansHybridBSPConf(new HamaConfiguration(), inPath, outPath);
  }

  public static BSPJob createKMeansHybridBSPConf(Configuration conf,
      Path inPath, Path outPath) throws IOException {

    BSPJob job = new BSPJob(new HamaConfiguration(conf), KMeansHybridBSP.class);
    // Set the job name
    job.setJobName("KMeansHybrid Clustering");
    // set the BSP class which shall be executed
    job.setBspClass(KMeansHybridBSP.class);
    // help Hama to locale the jar to be distributed
    job.setJarByClass(KMeansHybridBSP.class);

    job.setInputFormat(SequenceFileInputFormat.class);
    job.setInputKeyClass(PipesVectorWritable.class);
    job.setInputValueClass(NullWritable.class);
    job.setInputPath(inPath);

    job.setOutputFormat(SequenceFileOutputFormat.class);
    job.setOutputKeyClass(IntWritable.class);
    job.setOutputValueClass(PipesVectorWritable.class);
    job.setOutputPath(outPath);

    job.set("bsp.child.java.opts", "-Xmx4G");

    return job;
  }

  public static void main(String[] args) throws Exception {

    // Defaults
    long n = 100000; // input vectors
    int k = 10; // start vectors
    int vectorDimension = 2;
    int maxIteration = 10;
    boolean isDebugging = true;

    Configuration conf = new HamaConfiguration();
    BSPJobClient jobClient = new BSPJobClient(conf);
    ClusterStatus cluster = jobClient.getClusterStatus(true);

    if (args.length > 0) {
      if (args.length == 6) {
        conf.setInt("bsp.peers.num", Integer.parseInt(args[0]));
        n = Long.parseLong(args[1]);
        k = Integer.parseInt(args[2]);
        vectorDimension = Integer.parseInt(args[3]);
        maxIteration = Integer.parseInt(args[4]);
        isDebugging = Boolean.parseBoolean(args[5]);

      } else {
        System.out.println("Wrong argument size!");
        System.out.println("    Argument1=numBspTask");
        System.out.println("    Argument2=n | Number of input vectors");
        System.out.println("    Argument3=k | Number of start vectors");
        System.out
            .println("    Argument4=vectorDimension | Dimension of each vector");
        System.out
            .println("    Argument5=maxIterations | Number of maximal iterations");
        System.out
            .println("    Argument6=debug | Enable debugging (true|false)");
        return;
      }
    } else {
      conf.setInt("bsp.peers.num", 1); // cluster.getMaxTasks());
      // Enable one GPU task
      conf.setInt("bsp.peers.gpu.num", 1);
    }

    conf.setInt(CONF_MAX_ITERATIONS_KEY, maxIteration);
    conf.setBoolean(CONF_DEBUG, isDebugging);

    conf.set(CONF_BLOCKSIZE, "" + BLOCK_SIZE);
    conf.set(CONF_GRIDSIZE, "" + GRID_SIZE);

    LOG.info("NumBspTask: " + conf.getInt("bsp.peers.num", 0));
    LOG.info("NumGpuBspTask: " + conf.getInt("bsp.peers.gpu.num", 0));
    LOG.info("bsp.tasks.maximum: " + conf.get("bsp.tasks.maximum"));
    LOG.info("isDebugging: " + isDebugging);
    LOG.info("inputPath: " + CONF_INPUT_DIR);
    LOG.info("outputPath: " + CONF_OUTPUT_DIR);
    LOG.info("n: " + n);
    LOG.info("k: " + k);
    LOG.info("vectorDimension: " + vectorDimension);
    LOG.info("maxIteration: " + maxIteration);

    Path input = new Path(CONF_INPUT_DIR, "input.seq");
    Path centerIn = new Path(CONF_INPUT_DIR, "center.seq");
    Path centerOut = new Path(CONF_OUTPUT_DIR, "center.seq");
    conf.set(CONF_CENTER_IN_PATH, centerIn.toString());
    conf.set(CONF_CENTER_OUT_PATH, centerOut.toString());

    prepareInput(n, k, vectorDimension, conf, input, centerIn);

    BSPJob job = createKMeansHybridBSPConf(conf, CONF_INPUT_DIR,
        CONF_OUTPUT_DIR);

    long startTime = System.currentTimeMillis();
    if (job.waitForCompletion(true)) {
      LOG.info("Job Finished in " + (System.currentTimeMillis() - startTime)
          / 1000.0 + " seconds");
    }

    if (isDebugging) {
      printOutput(conf);
    }
  }

  /**
   * Create some random vectors as input and assign the first k vectors as
   * intial centers.
   */
  public static void prepareInput(long n, int k, int vectorDimension,
      Configuration conf, Path in, Path centerIn) throws IOException {

    FileSystem fs = FileSystem.get(conf);

    if (fs.exists(in)) {
      fs.delete(in, true);
    }
    if (fs.exists(centerIn)) {
      fs.delete(centerIn, true);
    }

    // Center inputs
    final SequenceFile.Writer centerWriter = SequenceFile.createWriter(fs,
        conf, centerIn, PipesVectorWritable.class, NullWritable.class,
        CompressionType.NONE);

    // Vector inputs
    final SequenceFile.Writer dataWriter = SequenceFile
        .createWriter(fs, conf, in, PipesVectorWritable.class,
            NullWritable.class, CompressionType.NONE);

    final NullWritable value = NullWritable.get();
    Random r = new Random();

    for (long i = 0; i < n; i++) {

      double[] arr = new double[vectorDimension];
      for (int d = 0; d < vectorDimension; d++) {
        arr[d] = r.nextInt((int) n);
      }

      PipesVectorWritable vector = new PipesVectorWritable(
          new DenseDoubleVector(arr));
      dataWriter.append(vector, value);

      if (k > i) {
        centerWriter.append(vector, value);
      } else if (k == i) {
        centerWriter.close();
      }
    }
    dataWriter.close();
  }

}
