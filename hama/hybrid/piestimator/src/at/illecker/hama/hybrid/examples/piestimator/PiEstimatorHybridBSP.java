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
package at.illecker.hama.hybrid.examples.piestimator;

import java.io.IOException;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hama.HamaConfiguration;
import org.apache.hama.bsp.BSPJob;
import org.apache.hama.bsp.BSPJobClient;
import org.apache.hama.bsp.BSPPeer;
import org.apache.hama.bsp.ClusterStatus;
import org.apache.hama.bsp.FileOutputFormat;
import org.apache.hama.bsp.NullInputFormat;
import org.apache.hama.bsp.TextOutputFormat;
import org.apache.hama.bsp.gpu.HybridBSP;
import org.apache.hama.bsp.sync.SyncException;
import org.trifort.rootbeer.runtime.Context;
import org.trifort.rootbeer.runtime.Rootbeer;
import org.trifort.rootbeer.runtime.StatsRow;
import org.trifort.rootbeer.runtime.ThreadConfig;
import org.trifort.rootbeer.runtime.util.Stopwatch;

public class PiEstimatorHybridBSP extends
    HybridBSP<NullWritable, NullWritable, Text, DoubleWritable, LongWritable> {

  private static final Log LOG = LogFactory.getLog(PiEstimatorHybridBSP.class);
  private static final Path TMP_OUTPUT = new Path(
      "output/hama/hybrid/examples/piestimator/Hybrid-"
          + System.currentTimeMillis());

  public static final String CONF_DEBUG = "piestimator.hybrid.debug";
  public static final String CONF_ITERATIONS = "piestimator.hybrid.iterations";
  public static final String CONF_GPU_PERCENTAGE = "piestimator.hybrid.percentage";
  public static final String CONF_BLOCKSIZE = "piestimator.hybrid.blockSize";
  public static final String CONF_GRIDSIZE = "piestimator.hybrid.gridSize";

  // gridSize = amount of blocks and multiprocessors
  public static final int gridSize = 14;
  // blockSize = amount of threads
  public static final int blockSize = 1024;

  private static final long totalIterations = 100000 * blockSize * gridSize;

  private Configuration m_conf;
  private boolean m_isDebuggingEnabled;
  private FSDataOutputStream m_logger;

  private String m_masterTask;
  private long m_iterations;
  private int m_GPUPercentage; // percentage of input data 0 to 100
  private long m_iterationsPerCPUTask; // for CPU tasks
  private long m_iterationsPerGPUThread; // for GPU threads
  private int m_gridSize;
  private int m_blockSize;

  /********************************* CPU *********************************/
  @Override
  public void setup(
      BSPPeer<NullWritable, NullWritable, Text, DoubleWritable, LongWritable> peer)
      throws IOException {

    m_conf = peer.getConfiguration();
    int totalTaskNum = peer.getNumPeers();

    // Choose one as a master
    this.m_masterTask = peer.getPeerName(totalTaskNum / 2);
    this.m_isDebuggingEnabled = m_conf.getBoolean(CONF_DEBUG, false);
    this.m_iterations = Long.parseLong(m_conf.get(CONF_ITERATIONS));
    // default equally distributed
    this.m_GPUPercentage = Integer.parseInt(m_conf.get(CONF_GPU_PERCENTAGE));
    this.m_blockSize = Integer.parseInt(m_conf.get(CONF_BLOCKSIZE));
    this.m_gridSize = Integer.parseInt(m_conf.get(CONF_GRIDSIZE));

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

    // Compute work distributions
    // for GPU
    long iterationsPerGPUTask = (m_iterations * m_GPUPercentage) / 100;
    m_iterationsPerGPUThread = iterationsPerGPUTask
        / (m_blockSize * m_gridSize);

    // for CPU
    long iterationsPerCPU = m_iterations - iterationsPerGPUTask;
    int cpuTaskNum = totalTaskNum - m_conf.getInt("bsp.peers.gpu.num", 0);
    m_iterationsPerCPUTask = iterationsPerCPU / cpuTaskNum;

    // DEBUG
    if (m_isDebuggingEnabled) {
      m_logger.writeChars("PiEstimatorHybrid,cpuTaskNum=" + cpuTaskNum + "\n");
      m_logger.writeChars("PiEstimatorHybrid,GPUPercentage=" + m_GPUPercentage
          + "\n");
      m_logger.writeChars("PiEstimatorHybrid,CPUPercentage="
          + (100 - m_GPUPercentage) + "\n");
      m_logger.writeChars("PiEstimatorHybrid,iterationsPerCPUTask="
          + m_iterationsPerCPUTask + "\n");
      m_logger.writeChars("PiEstimatorHybrid,iterationsPerGPUTask="
          + iterationsPerGPUTask + "\n");
      m_logger.writeChars("PiEstimatorHybrid,iterationsPerGPUThread="
          + m_iterationsPerGPUThread + "\n");
    }
  }

  @Override
  public void bsp(
      BSPPeer<NullWritable, NullWritable, Text, DoubleWritable, LongWritable> peer)
      throws IOException, SyncException, InterruptedException {

    long seed = System.currentTimeMillis();
    LinearCongruentialRandomGenerator lcrg = new LinearCongruentialRandomGenerator(
        seed);

    long hits = 0;
    for (long i = 0; i < m_iterationsPerCPUTask; i++) {
      double x = 2.0 * lcrg.nextDouble() - 1.0;
      double y = 2.0 * lcrg.nextDouble() - 1.0;
      if ((x * x + y * y) <= 1.0) {
        hits++;
      }
    }

    // Send result to MasterTask
    peer.send(m_masterTask, new LongWritable(hits));
    peer.sync();
  }

  @Override
  public void cleanup(
      BSPPeer<NullWritable, NullWritable, Text, DoubleWritable, LongWritable> peer)
      throws IOException {

    // MasterTask writes out results
    if (peer.getPeerName().equals(m_masterTask)) {

      int numMessages = peer.getNumCurrentMessages();

      long totalHits = 0;
      LongWritable received;
      while ((received = peer.getCurrentMessage()) != null) {
        totalHits += received.get();
      }

      double pi = 4.0 * totalHits / (m_iterationsPerCPUTask * numMessages);

      // DEBUG
      if (m_isDebuggingEnabled) {
        m_logger.writeChars("BSP=PiEstimatorHybrid,Iterations=" + m_iterations
            + "\n");
        m_logger.writeChars("totalHits: " + totalHits + "\n");
        m_logger.writeChars("numMessages: " + numMessages + "\n");
        m_logger.writeChars("m_iterationsPerCPUTask: " + m_iterationsPerCPUTask
            + "\n");
        m_logger.writeChars("calculationsTotal: "
            + (m_iterationsPerCPUTask * numMessages) + "\n");
        m_logger.close();
      }

      peer.write(new Text("Estimated value of PI(3,14159265) using "
          + (m_iterationsPerCPUTask * numMessages)
          // + (peer.getNumPeers() * m_threadCount * m_iterations)
          + " points is"), new DoubleWritable(pi));
    }
  }

  /********************************* GPU *********************************/
  @Override
  public void setupGpu(
      BSPPeer<NullWritable, NullWritable, Text, DoubleWritable, LongWritable> peer)
      throws IOException, SyncException, InterruptedException {

    // Same cleanup as CPU
    setup(peer);
  }

  @Override
  public void bspGpu(
      BSPPeer<NullWritable, NullWritable, Text, DoubleWritable, LongWritable> peer,
      Rootbeer rootbeer) throws IOException, SyncException,
      InterruptedException {

    PiEstimatorKernel kernel = new PiEstimatorKernel(m_iterationsPerGPUThread,
        System.currentTimeMillis());

    // Run GPU Kernels
    Context context = rootbeer.createDefaultContext();
    Stopwatch watch = new Stopwatch();
    watch.start();
    rootbeer.run(kernel, new ThreadConfig(m_blockSize, m_gridSize, m_blockSize
        * m_gridSize), context);
    watch.stop();

    // Get GPU results
    long totalHits = 0;
    Result[] resultList = kernel.resultList.getList();
    for (Result result : resultList) {
      if (result == null) { // break at end of list
        break;
      }
      totalHits += result.hits;
    }

    // DEBUG
    if (m_isDebuggingEnabled) {
      m_logger.writeChars("BSP=PiEstimatorHybrid,Iterations=" + m_iterations
          + ",GPUTime=" + watch.elapsedTimeMillis() + "ms\n");
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
      }

      m_logger.writeChars("totalHits: " + totalHits + "\n");
      m_logger.writeChars("iterationsPerGPUThread: " + m_iterationsPerGPUThread
          + "\n");
      m_logger.writeChars("TotalIterationsOnGPU: " + m_iterationsPerGPUThread
          * m_blockSize * m_gridSize + "\n");
      m_logger.close();
    }

    // Send result to MasterTask
    peer.send(m_masterTask, new LongWritable(totalHits));
    peer.sync();
  }

  @Override
  public void cleanupGpu(
      BSPPeer<NullWritable, NullWritable, Text, DoubleWritable, LongWritable> peer)
      throws IOException {

    // Same cleanup as CPU
    cleanup(peer);
  }

  public static BSPJob createPiEstimatorHybridConf(Path outPath)
      throws IOException {
    return createPiEstimatorHybridConf(new HamaConfiguration(), outPath);
  }

  public static BSPJob createPiEstimatorHybridConf(
      HamaConfiguration initialConf, Path outPath) throws IOException {

    BSPJob job = new BSPJob(initialConf);
    // Set the job name
    job.setJobName("PiEstimatorHybrid");
    // set the BSP class which shall be executed
    job.setBspClass(PiEstimatorHybridBSP.class);
    // help Hama to locale the jar to be distributed
    job.setJarByClass(PiEstimatorHybridBSP.class);

    job.setInputFormat(NullInputFormat.class);

    job.setOutputFormat(TextOutputFormat.class);
    job.setOutputKeyClass(Text.class);
    job.setOutputValueClass(DoubleWritable.class);
    FileOutputFormat.setOutputPath(job, outPath);

    job.setMessageClass(LongWritable.class);

    job.set("bsp.child.java.opts", "-Xmx4G");

    return job;
  }

  public static void main(String[] args) throws InterruptedException,
      IOException, ClassNotFoundException {

    BSPJob job = createPiEstimatorHybridConf(TMP_OUTPUT);

    BSPJobClient jobClient = new BSPJobClient(job.getConfiguration());
    ClusterStatus cluster = jobClient.getClusterStatus(true);

    if (args.length > 0) {
      if (args.length == 4) {
        job.setNumBspTask(Integer.parseInt(args[0]));
        job.setNumBspGpuTask(Integer.parseInt(args[1]));
        job.set(CONF_ITERATIONS, args[2]);
        job.set(CONF_GPU_PERCENTAGE, args[3]);
      } else {
        System.out.println("Wrong argument size!");
        System.out.println("    Argument1=numBspTask");
        System.out.println("    Argument2=numBspGpuTask");
        System.out.println("    Argument3=totalIterations ("
            + PiEstimatorHybridBSP.totalIterations + ")");
        System.out.println("    Argument4=GPUPercentage (percentage of input)");
        return;
      }
    } else {
      job.setNumBspTask(cluster.getMaxTasks());
      // Enable one GPU task
      job.setNumBspGpuTask(1);
      job.set(CONF_ITERATIONS, "" + PiEstimatorHybridBSP.totalIterations);
      job.set(CONF_GPU_PERCENTAGE, "12");
    }

    LOG.info("TotalIterations: " + job.get(CONF_ITERATIONS));
    LOG.info("GPUPercentage: " + job.get(CONF_GPU_PERCENTAGE));
    LOG.info("BlockSize: " + blockSize);
    LOG.info("GridSize: " + gridSize);

    job.set(CONF_BLOCKSIZE, "" + blockSize);
    job.set(CONF_GRIDSIZE, "" + gridSize);
    job.setBoolean(CONF_DEBUG, true);

    LOG.info("NumBspTask: " + job.getNumBspTask());
    LOG.info("NumBspGpuTask: " + job.getNumBspGpuTask());

    long startTime = System.currentTimeMillis();
    if (job.waitForCompletion(true)) {
      System.out.println("Job Finished in "
          + (System.currentTimeMillis() - startTime) / 1000.0 + " seconds");

      printOutput(job);
    }
  }

  static void printOutput(BSPJob job) throws IOException {
    FileSystem fs = FileSystem.get(job.getConfiguration());
    FileStatus[] files = fs.listStatus(FileOutputFormat.getOutputPath(job));
    for (int i = 0; i < files.length; i++) {
      if (files[i].getLen() > 0) {
        System.out.println("File " + files[i].getPath());
        FSDataInputStream in = fs.open(files[i].getPath());
        IOUtils.copyBytes(in, System.out, job.getConfiguration(), false);
        in.close();
      }
    }
    // fs.delete(FileOutputFormat.getOutputPath(job), true);
  }
}
