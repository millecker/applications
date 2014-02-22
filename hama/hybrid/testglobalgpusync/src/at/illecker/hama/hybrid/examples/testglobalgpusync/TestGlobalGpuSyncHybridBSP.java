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
package at.illecker.hama.hybrid.examples.testglobalgpusync;

import java.io.IOException;
import java.util.List;

import junit.framework.Assert;

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
import org.apache.hama.HamaConfiguration;
import org.apache.hama.bsp.BSPJob;
import org.apache.hama.bsp.BSPPeer;
import org.apache.hama.bsp.NullInputFormat;
import org.apache.hama.bsp.NullOutputFormat;
import org.apache.hama.bsp.gpu.HybridBSP;
import org.apache.hama.bsp.sync.SyncException;
import org.trifort.rootbeer.runtime.Context;
import org.trifort.rootbeer.runtime.Rootbeer;
import org.trifort.rootbeer.runtime.StatsRow;
import org.trifort.rootbeer.runtime.ThreadConfig;
import org.trifort.rootbeer.runtime.util.Stopwatch;

public class TestGlobalGpuSyncHybridBSP
    extends
    HybridBSP<NullWritable, NullWritable, NullWritable, NullWritable, IntWritable> {

  private static final Log LOG = LogFactory
      .getLog(TestGlobalGpuSyncHybridBSP.class);

  public static final String CONF_BLOCK_SIZE = "testglobalgpusync.hybrid.blockSize";
  public static final String CONF_GRID_SIZE = "testglobalgpusync.hybrid.gridSize";
  public static final String CONF_TMP_DIR = "testglobalgpusync.tmp.path";
  private static final Path TMP_DIR = new Path(
      "output/hama/hybrid/examples/testglobalgpusync/hybrid-"
          + System.currentTimeMillis());

  // GridSize = max 14 Multiprocessors (192 CUDA Cores/MP = 2688 CUDA Cores)
  // BlockSize = max 1024
  // 40 registers -> max blockSize 768
  // 45 registers -> max blockSize 640
  // 48 registers -> max blockSize 640
  public static final int BLOCK_SIZE = 14;
  public static final int GRID_SIZE = 14;

  private String m_masterTask;

  @Override
  public void setup(
      BSPPeer<NullWritable, NullWritable, NullWritable, NullWritable, IntWritable> peer)
      throws IOException {

    // Choose one as a master, who sorts the matrix rows at the end
    // m_masterTask = peer.getPeerName(peer.getNumPeers() / 2);
    // TODO task must be 0 otherwise write out does NOT work!
    this.m_masterTask = peer.getPeerName(0);
  }

  @Override
  public void bsp(
      BSPPeer<NullWritable, NullWritable, NullWritable, NullWritable, IntWritable> peer)
      throws IOException, SyncException, InterruptedException {

    // Debug output
    HamaConfiguration conf = peer.getConfiguration();
    FileSystem fs = FileSystem.get(peer.getConfiguration());
    FSDataOutputStream outStream = fs.create(new Path(conf.get(CONF_TMP_DIR),
        peer.getTaskId() + ".log"));
    outStream.writeChars("TestGlobalGpuSycHybridBSP.bsp executed on CPU!\n");

    peer.send(m_masterTask, new IntWritable(peer.getPeerIndex()));

    peer.sync();

    // If master, fetch messages
    if (peer.getPeerName().equals(m_masterTask)) {
      peer.getNumCurrentMessages();

      int msgCount = peer.getNumCurrentMessages();
      for (int i = 0; i < msgCount; i++) {
        int id = peer.getCurrentMessage().get();
        outStream.writeChars(id + "\n");
      }
    }

    outStream.close();
  }

  @Override
  public void setupGpu(
      BSPPeer<NullWritable, NullWritable, NullWritable, NullWritable, IntWritable> peer)
      throws IOException {
    this.setup(peer);
  }

  @Override
  public void bspGpu(
      BSPPeer<NullWritable, NullWritable, NullWritable, NullWritable, IntWritable> peer,
      Rootbeer rootbeer) throws IOException, SyncException,
      InterruptedException {

    HamaConfiguration conf = peer.getConfiguration();
    int blockSize = Integer.parseInt(conf.get(CONF_BLOCK_SIZE));
    int gridSize = Integer.parseInt(conf.get(CONF_GRID_SIZE));

    TestGlobalGpuSyncKernel kernel = new TestGlobalGpuSyncKernel(m_masterTask);

    // Run GPU Kernels
    Context context = rootbeer.createDefaultContext();
    Stopwatch watch = new Stopwatch();
    watch.start();
    rootbeer.run(kernel, new ThreadConfig(blockSize, gridSize, blockSize
        * gridSize), context);
    watch.stop();

    // Debug output
    FileSystem fs = FileSystem.get(conf);
    FSDataOutputStream outStream = fs.create(new Path(conf.get(CONF_TMP_DIR),
        peer.getTaskId() + ".log"));

    outStream.writeChars("TestGlobalGpuSycHybridBSP.bspGpu executed on GPU!\n");
    List<StatsRow> stats = context.getStats();
    for (StatsRow row : stats) {
      outStream.writeChars("  StatsRow:\n");
      outStream.writeChars("    serial time: " + row.getSerializationTime()
          + "\n");
      outStream.writeChars("    exec time: " + row.getExecutionTime() + "\n");
      outStream.writeChars("    deserial time: " + row.getDeserializationTime()
          + "\n");
      outStream.writeChars("    num blocks: " + row.getNumBlocks() + "\n");
      outStream.writeChars("    num threads: " + row.getNumThreads() + "\n");
    }

    outStream.writeChars("TestGlobalGpuSycHybridBSP,GPUTime="
        + watch.elapsedTimeMillis() + " ms\n");
    outStream.writeChars("TestGlobalGpuSycHybridBSP,BlockSize=" + blockSize + "\n");
    outStream.writeChars("TestGlobalGpuSycHybridBSP,GridSize=" + gridSize + "\n");
    outStream.writeChars("TestGlobalGpuSycHybridBSP,TotalThreads="
        + (blockSize * gridSize) + "\n");
    outStream.writeChars("TestGlobalGpuSycHybridBSP,MessageCount="
        + kernel.messageCount + "\n");
    outStream.writeChars("TestGlobalGpuSycHybridBSP,MessageSum="
        + kernel.messageSum + "\n");

    Assert.assertEquals((blockSize * gridSize), kernel.messageCount);
    int n = (blockSize * gridSize) - 1;
    Assert.assertEquals((n * (n + 1)) / 2, kernel.messageSum);
    outStream.writeChars("TestGlobalGpuSycHybridBSP.bspGpu: messages verified!'\n");
    outStream.close();
  }

  public static BSPJob createTestGlobalGpuSyncHybridBSPConf()
      throws IOException {
    return createTestGlobalGpuSyncHybridBSPConf(new HamaConfiguration());
  }

  public static BSPJob createTestGlobalGpuSyncHybridBSPConf(Configuration conf)
      throws IOException {

    BSPJob job = new BSPJob(new HamaConfiguration(conf),
        TestGlobalGpuSyncHybridBSP.class);
    // Set the job name
    job.setJobName("TestGlobalGpuSyncHybridBSP Example");
    // set the BSP class which shall be executed
    job.setBspClass(TestGlobalGpuSyncHybridBSP.class);
    // help Hama to locale the jar to be distributed
    job.setJarByClass(TestGlobalGpuSyncHybridBSP.class);

    job.setInputFormat(NullInputFormat.class);
    job.setOutputFormat(NullOutputFormat.class);

    job.setMessageClass(IntWritable.class);

    job.set("bsp.child.java.opts", "-Xmx4G");

    return job;
  }

  static void printOutput(BSPJob job, FileSystem fs, Path path)
      throws IOException {
    FileStatus[] files = fs.listStatus(path);
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

  public static void main(String[] args) throws InterruptedException,
      IOException, ClassNotFoundException {

    // Defaults
    int numBspTask = 1;
    int numGpuBspTask = 1;
    int blockSize = BLOCK_SIZE;
    int gridSize = GRID_SIZE;
    boolean isDebugging = false;

    Configuration conf = new HamaConfiguration();

    if (args.length > 0) {
      if (args.length == 5) {
        numBspTask = Integer.parseInt(args[0]);
        numGpuBspTask = Integer.parseInt(args[1]);
        blockSize = Integer.parseInt(args[2]);
        gridSize = Integer.parseInt(args[3]);
        isDebugging = Boolean.parseBoolean(args[4]);
      } else {
        System.out.println("Wrong argument size!");
        System.out.println("    Argument1=numBspTask");
        System.out.println("    Argument2=numGpuBspTask");
        System.out.println("    Argument3=blockSize");
        System.out.println("    Argument4=gridSize");
        System.out
            .println("    Argument5=debug | Enable debugging (true|false=default)");
        return;
      }
    }

    // Set config variables
    conf.setBoolean("hama.pipes.logging", isDebugging);
    // Set CPU tasks
    conf.setInt("bsp.peers.num", numBspTask);
    // Set GPU tasks
    conf.setInt("bsp.peers.gpu.num", numGpuBspTask);
    // Set GPU blockSize and gridSize
    conf.set(CONF_BLOCK_SIZE, "" + blockSize);
    conf.set(CONF_GRID_SIZE, "" + gridSize);
    conf.set(CONF_TMP_DIR, TMP_DIR.toString());

    LOG.info("NumBspTask: " + conf.getInt("bsp.peers.num", 0));
    LOG.info("NumGpuBspTask: " + conf.getInt("bsp.peers.gpu.num", 0));
    LOG.info("bsp.tasks.maximum: " + conf.get("bsp.tasks.maximum"));
    LOG.info("BlockSize: " + conf.get(CONF_BLOCK_SIZE));
    LOG.info("GridSize: " + conf.get(CONF_GRID_SIZE));
    LOG.info("TempDir: " + conf.get(CONF_TMP_DIR));
    LOG.info("isDebugging: " + conf.getBoolean("hama.pipes.logging", false));

    BSPJob job = createTestGlobalGpuSyncHybridBSPConf(conf);

    long startTime = System.currentTimeMillis();
    if (job.waitForCompletion(true)) {
      LOG.info("Job Finished in " + (System.currentTimeMillis() - startTime)
          / 1000.0 + " seconds");
      printOutput(job, FileSystem.get(conf), new Path(conf.get(CONF_TMP_DIR)));
    }
  }

}
