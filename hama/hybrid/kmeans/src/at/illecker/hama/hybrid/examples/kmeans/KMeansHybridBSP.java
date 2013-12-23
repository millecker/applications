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
import java.util.Arrays;
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
import org.apache.hadoop.io.Writable;
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
  private static final Path CONF_CENTER_DIR = new Path(CONF_TMP_DIR, "centers");

  public static final String CONF_BLOCKSIZE = "kmeans.hybrid.blockSize";
  public static final String CONF_GRIDSIZE = "kmeans.hybrid.gridSize";

  // gridSize = amount of blocks and multiprocessors
  public static final int GRID_SIZE = 14;
  // blockSize = amount of threads
  public static final int BLOCK_SIZE = 1024;

  private boolean m_isDebuggingEnabled;
  private FSDataOutputStream m_logger;

  // a task local copy of our cluster centers
  private DoubleVector[] m_centers_cpu = null;
  private double[][] m_centers_gpu = null;

  // simple cache to speed up computation, because the algorithm is disk based
  // normally we want to rely on OS caching, but if not, we can cache in heap
  private List<DoubleVector> m_cache = new ArrayList<DoubleVector>();
  // numbers of maximum iterations to do
  private int m_maxIterations;

  private Configuration m_conf;

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

    this.m_conf = peer.getConfiguration();
    this.m_isDebuggingEnabled = m_conf.getBoolean(CONF_DEBUG, false);
    this.m_maxIterations = m_conf.getInt(CONF_MAX_ITERATIONS_KEY, -1);

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

    // Init center vectors
    Path centroids = new Path(m_conf.get(CONF_CENTER_IN_PATH));
    FileSystem fs = FileSystem.get(m_conf);

    final ArrayList<DoubleVector> centers = new ArrayList<DoubleVector>();
    SequenceFile.Reader reader = null;
    try {
      reader = new SequenceFile.Reader(fs, centroids, m_conf);
      PipesVectorWritable key = new PipesVectorWritable();
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

    Preconditions.checkArgument(centers.size() > 0,
        "Centers file must contain at least a single center!");

    this.m_centers_cpu = centers.toArray(new DoubleVector[centers.size()]);
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

      // Logging
      if (m_isDebuggingEnabled) {
        m_logger.writeChars("bsp,converged: " + converged + "\n");
        m_logger.flush();
      }

      peer.reopenInput();

      if (converged == 0) {
        break;
      }
      if ((m_maxIterations > 0) && (m_maxIterations < peer.getSuperstepCount())) {
        break;
      }
    }
    LOG.info("Finished! Writing the assignments...");

    recalculateAssignmentsAndWrite(peer);

    LOG.info("Done.");
  }

  private void assignCenters(
      BSPPeer<PipesVectorWritable, NullWritable, IntWritable, PipesVectorWritable, CenterMessage> peer)
      throws IOException {

    // each task has all the centers, if a center has been updated it
    // needs to be broadcasted.
    final DoubleVector[] newCenterArray = new DoubleVector[m_centers_cpu.length];
    final int[] summationCount = new int[m_centers_cpu.length];

    // if our cache is empty, we have to read it from disk first
    if (m_cache.isEmpty()) {
      final PipesVectorWritable key = new PipesVectorWritable();
      final NullWritable value = NullWritable.get();
      while (peer.readNext(key, value)) {
        DoubleVector deepCopy = key.getVector().deepCopy();
        m_cache.add(deepCopy);
        // but do the assignment directly
        assignCentersInternal(newCenterArray, summationCount, deepCopy);
      }
    } else {
      // now we can iterate in memory and check against the centers
      for (DoubleVector v : m_cache) {
        assignCentersInternal(newCenterArray, summationCount, v);
      }
    }

    // now send messages about the local updates to each other peer
    for (int i = 0; i < newCenterArray.length; i++) {
      if (newCenterArray[i] != null) {
        for (String peerName : peer.getAllPeerNames()) {
          peer.send(peerName, new CenterMessage(i, summationCount[i],
              newCenterArray[i]));
          // Logging
          if (m_isDebuggingEnabled) {
            m_logger.writeChars("assignCenters,sent,peerName=" + peerName
                + ",CenterMessage=" + i + "," + summationCount[i] + ","
                + Arrays.toString(newCenterArray[i].toArray()) + "\n");
            m_logger.flush();
          }
        }
      }
    }

  }

  private void assignCentersInternal(final DoubleVector[] newCenterArray,
      final int[] summationCount, final DoubleVector key) throws IOException {

    final int lowestDistantCenter = getNearestCenter(key);
    final DoubleVector clusterCenter = newCenterArray[lowestDistantCenter];

    if (clusterCenter == null) {
      newCenterArray[lowestDistantCenter] = key;
    } else {
      // add the vector to the center
      newCenterArray[lowestDistantCenter] = newCenterArray[lowestDistantCenter]
          .addUnsafe(key);
    }
    summationCount[lowestDistantCenter]++;
  }

  private int getNearestCenter(DoubleVector key) throws IOException {
    int lowestDistantCenter = 0;
    double lowestDistance = Double.MAX_VALUE;

    for (int i = 0; i < m_centers_cpu.length; i++) {
      final double estimatedDistance = measureEuclidianDistance(
          m_centers_cpu[i], key);

      // Logging
      if (m_isDebuggingEnabled) {
        m_logger.writeChars("getNearestCenter,estimatedDistance: "
            + estimatedDistance + "\n");
        m_logger.flush();
      }
      // check if we have a can assign a new center, because we
      // got a lower distance
      if (estimatedDistance < lowestDistance) {
        lowestDistance = estimatedDistance;
        lowestDistantCenter = i;
      }
    }
    // Logging
    if (m_isDebuggingEnabled) {
      m_logger.writeChars("getNearestCenter,lowestDistantCenter: "
          + lowestDistantCenter + "\n");
      m_logger.flush();
    }
    return lowestDistantCenter;
  }

  private double measureEuclidianDistance(DoubleVector vec1, DoubleVector vec2) {
    return Math.sqrt(vec2.subtractUnsafe(vec1).pow(2).sum());
  }

  private long updateCenters(
      BSPPeer<PipesVectorWritable, NullWritable, IntWritable, PipesVectorWritable, CenterMessage> peer)
      throws IOException {

    // this is the update step
    DoubleVector[] msgCenters = new DoubleVector[m_centers_cpu.length];
    int[] incrementSum = new int[m_centers_cpu.length];

    CenterMessage msg;
    // basically just summing incoming vectors
    while ((msg = peer.getCurrentMessage()) != null) {

      // Logging
      if (m_isDebuggingEnabled) {
        m_logger.writeChars("updateCenters,receive,CenterMessage="
            + msg.getCenterIndex() + "," + msg.getIncrementCounter() + ","
            + Arrays.toString(msg.getData().toArray()) + "\n");
        m_logger.flush();
      }

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
      final DoubleVector oldCenter = m_centers_cpu[i];
      if (msgCenters[i] != null) {

        double calculateError = oldCenter.subtractUnsafe(msgCenters[i]).abs()
            .sum();

        // Logging
        if (m_isDebuggingEnabled) {
          m_logger.writeChars("updateCenters,i: " + i + "\n");
          m_logger.writeChars("updateCenters,oldCenter: "
              + Arrays.toString(oldCenter.toArray()) + "\n");
          m_logger.writeChars("updateCenters,msgCenters[i]: "
              + Arrays.toString(msgCenters[i].toArray()) + "\n");
          m_logger.writeChars("updateCenters,calculateError: " + calculateError
              + "\n");
          m_logger.flush();
        }

        if (calculateError > 0.0d) {
          m_centers_cpu[i] = msgCenters[i];
          convergedCounter++;

          // Logging
          if (m_isDebuggingEnabled) {
            m_logger.writeChars("updateCenters,m_centers_cpu: "
                + Arrays.toString(msgCenters[i].toArray()) + "\n");
            m_logger.flush();
          }
        }

      }
    }
    return convergedCounter;
  }

  private void recalculateAssignmentsAndWrite(
      BSPPeer<PipesVectorWritable, NullWritable, IntWritable, PipesVectorWritable, CenterMessage> peer)
      throws IOException {

    IntWritable keyWrite = new IntWritable();
    for (DoubleVector v : m_cache) {
      final int lowestDistantCenter = getNearestCenter(v);
      keyWrite.set(lowestDistantCenter);
      peer.write(keyWrite, new PipesVectorWritable(v));
    }

    // just on the first task write the centers to filesystem to prevent
    // collisions
    if (peer.getPeerName().equals(peer.getPeerName(0))) {
      String pathString = m_conf.get(CONF_CENTER_OUT_PATH);
      if (pathString != null) {
        final SequenceFile.Writer dataWriter = SequenceFile
            .createWriter(FileSystem.get(m_conf), m_conf, new Path(pathString),
                PipesVectorWritable.class, NullWritable.class,
                CompressionType.NONE);
        final NullWritable value = NullWritable.get();

        for (DoubleVector center : m_centers_cpu) {
          dataWriter.append(new PipesVectorWritable(center), value);
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

    this.m_conf = peer.getConfiguration();
    this.m_isDebuggingEnabled = m_conf.getBoolean(CONF_DEBUG, false);
    this.m_maxIterations = m_conf.getInt(CONF_MAX_ITERATIONS_KEY, -1);

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

    // Init center vectors
    Path centroids = new Path(m_conf.get(CONF_CENTER_IN_PATH));
    FileSystem fs = FileSystem.get(m_conf);

    final ArrayList<double[]> centers = new ArrayList<double[]>();
    SequenceFile.Reader reader = null;
    try {
      reader = new SequenceFile.Reader(fs, centroids, m_conf);
      PipesVectorWritable key = new PipesVectorWritable();
      NullWritable value = NullWritable.get();
      while (reader.next(key, value)) {
        centers.add(key.getVector().toArray());
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    } finally {
      if (reader != null) {
        reader.close();
      }
    }

    Preconditions.checkArgument(centers.size() > 0,
        "Centers file must contain at least a single center!");

    // build double[][]
    this.m_centers_gpu = new double[centers.size()][centers.get(0).length];
    for (int i = 0; i < centers.size(); i++) {
      double[] vector = centers.get(i);
      for (int j = 0; j < vector.length; j++) {
        this.m_centers_gpu[i][j] = vector[j];
      }
    }

    // Logging
    if (m_isDebuggingEnabled) {
      for (int i = 0; i < m_centers_gpu.length; i++) {
        m_logger.writeChars("m_centers_gpu[" + i + "]: "
            + Arrays.toString(m_centers_gpu[i]) + "\n");
      }
    }
  }

  @Override
  public void bspGpu(
      BSPPeer<PipesVectorWritable, NullWritable, IntWritable, PipesVectorWritable, CenterMessage> peer,
      Rootbeer rootbeer) throws IOException, SyncException,
      InterruptedException {

    // Logging
    if (m_isDebuggingEnabled) {
      m_logger.writeChars("KMeansHybrid.bspGpu executed on GPU!\n");
      m_logger.writeChars("KMeansHybrid.bspGpu blockSize: " + m_blockSize
          + " gridSize: " + m_gridSize + "\n");
    }

    KMeansHybridKernel kernel = new KMeansHybridKernel(m_centers_gpu,
        m_maxIterations);

    rootbeer.setThreadConfig(m_blockSize, m_gridSize, m_blockSize * m_gridSize);

    // Run GPU Kernels
    Stopwatch watch = new Stopwatch();
    watch.start();
    rootbeer.runAll(kernel);
    watch.stop();

    // just on the first task write the centers to filesystem to prevent
    // collisions
    if (peer.getPeerName().equals(peer.getPeerName(0))) {
      String pathString = m_conf.get(CONF_CENTER_OUT_PATH);
      if (pathString != null) {
        final SequenceFile.Writer dataWriter = SequenceFile
            .createWriter(FileSystem.get(m_conf), m_conf, new Path(pathString),
                PipesVectorWritable.class, NullWritable.class,
                CompressionType.NONE);

        final NullWritable value = NullWritable.get();

        for (int i = 0; i < kernel.m_centers.length; i++) {
          dataWriter.append(new PipesVectorWritable(new DenseDoubleVector(
              kernel.m_centers[i])), value);
        }
        dataWriter.close();
      }
    }

    // Logging
    if (m_isDebuggingEnabled) {
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
      }

      m_logger.writeChars("KMeansHybrid,GPUTime=" + watch.elapsedTimeMillis()
          + "ms\n");
      m_logger.close();
    }
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
    conf.setBoolean("hama.pipes.logging", isDebugging);
    // Set CPU tasks
    conf.setInt("bsp.peers.num", numBspTask);
    // Set GPU tasks
    conf.setInt("bsp.peers.gpu.num", numGpuBspTask);
    // Set GPU blockSize and gridSize
    conf.set(CONF_BLOCKSIZE, "" + blockSize);
    conf.set(CONF_GRIDSIZE, "" + gridSize);
    // Set maxIterations for KMeans
    conf.setInt(CONF_MAX_ITERATIONS_KEY, maxIteration);

    LOG.info("NumBspTask: " + conf.getInt("bsp.peers.num", 0));
    LOG.info("NumGpuBspTask: " + conf.getInt("bsp.peers.gpu.num", 0));
    LOG.info("bsp.tasks.maximum: " + conf.get("bsp.tasks.maximum"));
    LOG.info("BlockSize: " + conf.get(CONF_BLOCKSIZE));
    LOG.info("GridSize: " + conf.get(CONF_GRIDSIZE));
    LOG.info("isDebugging: " + isDebugging);
    LOG.info("useTestExampleInput: " + useTestExampleInput);
    LOG.info("inputPath: " + CONF_INPUT_DIR);
    LOG.info("centersPath: " + CONF_CENTER_DIR);
    LOG.info("outputPath: " + CONF_OUTPUT_DIR);
    LOG.info("n: " + n);
    LOG.info("k: " + k);
    LOG.info("vectorDimension: " + vectorDimension);
    LOG.info("maxIteration: " + maxIteration);

    Path input = new Path(CONF_INPUT_DIR, "input.seq");
    Path centerIn = new Path(CONF_CENTER_DIR, "center_in.seq");
    Path centerOut = new Path(CONF_CENTER_DIR, "center_out.seq");
    conf.set(CONF_CENTER_IN_PATH, centerIn.toString());
    conf.set(CONF_CENTER_OUT_PATH, centerOut.toString());

    // prepare Input
    if (useTestExampleInput) {
      prepareTestInput(conf, input, centerIn);
    } else {
      prepareRandomInput(n, k, vectorDimension, conf, input, centerIn);
    }

    BSPJob job = createKMeansHybridBSPConf(conf, CONF_INPUT_DIR,
        CONF_OUTPUT_DIR);

    long startTime = System.currentTimeMillis();
    if (job.waitForCompletion(true)) {
      LOG.info("Job Finished in " + (System.currentTimeMillis() - startTime)
          / 1000.0 + " seconds");
      if (isDebugging) {
        printOutput(conf, new IntWritable(), new PipesVectorWritable());
        printFile(conf, centerOut, new PipesVectorWritable(),
            NullWritable.get());
      }
    }

  }

  /**
   * Create some random vectors as input and assign the first k vectors as
   * intial centers.
   */
  public static void prepareRandomInput(long n, int k, int vectorDimension,
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
    Random r = new Random(3337L);

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

  /**
   * Create testExample vectors and centers as input from
   * http://www.maplesoft.com/support/help/Maple/view.aspx?path=NAG/g03efc
   * 
   * n := 20: vectorDimension := 5: k := 3: maxIterations := 10:
   * 
   * x := Matrix([ [77.3, 13, 9.699999999999999, 1.5, 6.4], [82.5, 10, 7.5, 1.5,
   * 6.5], [66.90000000000001, 20.6, 12.5, 2.3, 7], [47.2, 33.8, 19, 2.8, 5.8],
   * [65.3, 20.5, 14.2, 1.9, 6.9], [83.3, 10, 6.7, 2.2, 7], [81.59999999999999,
   * 12.7, 5.7, 2.9, 6.7], [47.8, 36.5, 15.7, 2.3, 7.2], [48.6, 37.1, 14.3, 2.1,
   * 7.2], [61.6, 25.5, 12.9, 1.9, 7.3], [58.6, 26.5, 14.9, 2.4, 6.7], [69.3,
   * 22.3, 8.4, 4, 7], [61.8, 30.8, 7.4, 2.7, 6.4], [67.7, 25.3, 7, 4.8, 7.3],
   * [57.2, 31.2, 11.6, 2.4, 6.5], [67.2, 22.7, 10.1, 3.3, 6.2], [59.2, 31.2,
   * 9.6, 2.4, 6], [80.2, 13.2, 6.6, 2, 5.8], [82.2, 11.1, 6.7, 2.2, 7.2],
   * [69.7, 20.7, 9.6, 3.1, 5.9]], datatype=float[8], order='C_order'):
   * 
   * cmeans := Matrix( [[82.5, 10, 7.5, 1.5, 6.5], [47.8, 36.5, 15.7, 2.3, 7.2],
   * [67.2, 22.7, 10.1, 3.3, 6.2]], datatype=float[8], order='C_order'):
   * 
   * 
   * Results
   * 
   * cmeans := Matrix([ [81.1833333333333371, 11.6666666666666661,
   * 7.1499999999999947, 2.0500000000000027, 6.6000000000000052],
   * [47.8666666666666671, 35.8000000000000043, 16.3333333333333321,
   * 2.3999999999999992, 6.7333333333333340], [64.0454545454545610,
   * 25.2090909090909037, 10.7454545454545425, 2.83636363636363642,
   * 6.65454545454545521]]):
   * 
   * inc := Vector([0, 0, 2, 1, 2, 0, 0, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 0, 0,
   * 2]):
   * 
   * nic := Vector([6, 3, 11]):
   * 
   * css := Vector([46.5716666666666583, 20.3800000000000097,
   * 468.896363636363503]):
   * 
   */
  public static void prepareTestInput(Configuration conf, Path in, Path centerIn)
      throws IOException {
    FileSystem fs = FileSystem.get(conf);

    // Delete input files if already exist
    if (fs.exists(in)) {
      fs.delete(in, true);
    }
    if (fs.exists(centerIn)) {
      fs.delete(centerIn, true);
    }

    double[][] input = { { 77.3, 13, 9.699999999999999, 1.5, 6.4 },
        { 82.5, 10, 7.5, 1.5, 6.5 }, { 66.90000000000001, 20.6, 12.5, 2.3, 7 },
        { 47.2, 33.8, 19, 2.8, 5.8 }, { 65.3, 20.5, 14.2, 1.9, 6.9 },
        { 83.3, 10, 6.7, 2.2, 7 }, { 81.59999999999999, 12.7, 5.7, 2.9, 6.7 },
        { 47.8, 36.5, 15.7, 2.3, 7.2 }, { 48.6, 37.1, 14.3, 2.1, 7.2 },
        { 61.6, 25.5, 12.9, 1.9, 7.3 }, { 58.6, 26.5, 14.9, 2.4, 6.7 },
        { 69.3, 22.3, 8.4, 4, 7 }, { 61.8, 30.8, 7.4, 2.7, 6.4 },
        { 67.7, 25.3, 7, 4.8, 7.3 }, { 57.2, 31.2, 11.6, 2.4, 6.5 },
        { 67.2, 22.7, 10.1, 3.3, 6.2 }, { 59.2, 31.2, 9.6, 2.4, 6 },
        { 80.2, 13.2, 6.6, 2, 5.8 }, { 82.2, 11.1, 6.7, 2.2, 7.2 },
        { 69.7, 20.7, 9.6, 3.1, 5.9 } };
    double[][] centers = { { 82.5, 10, 7.5, 1.5, 6.5 },
        { 47.8, 36.5, 15.7, 2.3, 7.2 }, { 67.2, 22.7, 10.1, 3.3, 6.2 } };

    final NullWritable nullValue = NullWritable.get();

    // Write inputs
    LOG.info("inputs: ");
    final SequenceFile.Writer dataWriter = SequenceFile
        .createWriter(fs, conf, in, PipesVectorWritable.class,
            NullWritable.class, CompressionType.NONE);

    for (int i = 0; i < input.length; i++) {
      dataWriter.append(
          new PipesVectorWritable(new DenseDoubleVector(input[i])), nullValue);
      LOG.info("input[" + i + "]: " + Arrays.toString(input[i]));
    }

    dataWriter.close();

    // Write centers
    LOG.info("centers: ");
    final SequenceFile.Writer centerWriter = SequenceFile.createWriter(fs,
        conf, centerIn, PipesVectorWritable.class, NullWritable.class,
        CompressionType.NONE);

    for (int i = 0; i < centers.length; i++) {
      centerWriter.append(new PipesVectorWritable(new DenseDoubleVector(
          centers[i])), nullValue);
      LOG.info("center[" + i + "]: " + Arrays.toString(centers[i]));
    }

    centerWriter.close();
  }

  static void printOutput(Configuration conf, Writable key, Writable value)
      throws IOException {
    FileSystem fs = CONF_OUTPUT_DIR.getFileSystem(conf);
    FileStatus[] files = fs.listStatus(CONF_OUTPUT_DIR);
    for (int i = 0; i < files.length; i++) {
      if (files[i].getLen() > 0) {
        printFile(conf, files[i].getPath(), key, value);
      }
    }
    // fs.delete(FileOutputFormat.getOutputPath(job), true);
  }

  static void printFile(Configuration conf, Path file, Writable key,
      Writable value) throws IOException {
    FileSystem fs = CONF_OUTPUT_DIR.getFileSystem(conf);
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
