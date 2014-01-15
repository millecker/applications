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
package at.illecker.hama.hybrid.examples.testrootbeer;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

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
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.SequenceFile.CompressionType;
import org.apache.hama.HamaConfiguration;
import org.apache.hama.bsp.BSPJob;
import org.apache.hama.bsp.BSPPeer;
import org.apache.hama.bsp.FileOutputFormat;
import org.apache.hama.bsp.SequenceFileInputFormat;
import org.apache.hama.bsp.SequenceFileOutputFormat;
import org.apache.hama.bsp.gpu.HybridBSP;
import org.apache.hama.bsp.sync.SyncException;

import edu.syr.pcpratts.rootbeer.runtime.Rootbeer;
import edu.syr.pcpratts.rootbeer.runtime.StatsRow;
import edu.syr.pcpratts.rootbeer.runtime.util.Stopwatch;

public class TestRootbeerHybridBSP
    extends
    HybridBSP<IntWritable, IntWritable, NullWritable, IntWritable, NullWritable> {

  private static final Log LOG = LogFactory.getLog(TestRootbeerHybridBSP.class);
  private static final Path CONF_TMP_DIR = new Path(
      "output/hama/hybrid/examples/testrootbeer/hybrid-"
          + System.currentTimeMillis());
  private static final Path CONF_INPUT_DIR = new Path(CONF_TMP_DIR, "input");
  private static final Path CONF_OUTPUT_DIR = new Path(CONF_TMP_DIR, "output");
  public static final String CONF_EXAMPLE_PATH = "testrootbeer.example.path";
  // GridSize = max 14 Multiprocessors (192 CUDA Cores/MP = 2688 CUDA Cores)
  // BlockSize = max 1024
  // 40 registers -> max blockSize 768
  // 45 registers -> max blockSize 640
  // 48 registers -> max blockSize 640
  public static final int CONF_BLOCK_SIZE = 1024;
  public static final int CONF_GRID_SIZE = 14;

  @Override
  public void bsp(
      BSPPeer<IntWritable, IntWritable, NullWritable, IntWritable, NullWritable> peer)
      throws IOException, SyncException, InterruptedException {

    long startTime = System.currentTimeMillis();

    // test input
    int[] input = new int[CONF_BLOCK_SIZE * CONF_GRID_SIZE];
    IntWritable key = new IntWritable();
    IntWritable value = new IntWritable();
    while (peer.readNext(key, value)) {
      input[key.get()] = value.get();
    }

    String peerName = peer.getPeerName();

    long stopTime = System.currentTimeMillis();

    /*
     * // test sequenceFileReader Path example = new
     * Path(peer.getConfiguration().get(CONF_EXAMPLE_PATH)); SequenceFile.Reader
     * reader = null; try { reader = new SequenceFile.Reader(fs, example,
     * peer.getConfiguration()); int i = 0; while (reader.next(key, value)) {
     * outStream.writeChars("sequenceFileReader: key: '" + key.get() +
     * "' value: '" + value.get() + "'\n"); if (i < summation.size()) {
     * summation.set(i, summation.get(i) + value.get()); } i++; } } catch
     * (IOException e) { throw new RuntimeException(e); } finally { if (reader
     * != null) { reader.close(); } } // test output NullWritable nullValue =
     * NullWritable.get(); for (Integer i : summation) { value.set(i);
     * outStream.writeChars("output: key: '" + value.get() + "'\n");
     * peer.write(nullValue, value); }
     */

    // Debug output
    BSPJob job = new BSPJob((HamaConfiguration) peer.getConfiguration());
    FileSystem fs = FileSystem.get(peer.getConfiguration());
    FSDataOutputStream outStream = fs.create(new Path(FileOutputFormat
        .getOutputPath(job), peer.getTaskId() + ".log"));

    outStream.writeChars("TestRootbeerHybridBSP.bsp executed on CPU!\n");
    outStream.writeChars("TestRootbeerHybridBSP,CPUTime="
        + ((stopTime - startTime) / 1000.0) + " seconds\n");
    outStream.writeChars("TestRootbeerHybridBSP,CPUTime="
        + (stopTime - startTime) + " ms\n");
    outStream
        .writeChars("TestRootbeerHybridBSP,peerName: '" + peerName + "'\n");
    // outStream.writeChars("TestRootbeerHybridBSP,input: '"
    // + Arrays.toString(input) + "'\n");
    outStream.writeChars("getAllPeerNames: '"
        + Arrays.toString(peer.getAllPeerNames()) + "'\n");

    // Verify input
    peer.reopenInput();
    while (peer.readNext(key, value)) {
      Assert.assertEquals(value.get(), input[key.get()]);
    }

    outStream.writeChars("TestRootbeerHybridBSP,input verified!'\n");
    outStream.close();
  }

  @Override
  public void bspGpu(
      BSPPeer<IntWritable, IntWritable, NullWritable, IntWritable, NullWritable> peer,
      Rootbeer rootbeer) throws IOException, SyncException,
      InterruptedException {

    TestRootbeerKernel kernel = new TestRootbeerKernel(peer.getConfiguration()
        .get(CONF_EXAMPLE_PATH), CONF_BLOCK_SIZE * CONF_GRID_SIZE);

    rootbeer.setThreadConfig(CONF_BLOCK_SIZE, CONF_GRID_SIZE, CONF_BLOCK_SIZE
        * CONF_GRID_SIZE);

    // Run GPU Kernels
    Stopwatch watch = new Stopwatch();
    watch.start();
    rootbeer.runAll(kernel);
    watch.stop();

    // Debug output
    BSPJob job = new BSPJob((HamaConfiguration) peer.getConfiguration());
    FileSystem fs = FileSystem.get(peer.getConfiguration());
    FSDataOutputStream outStream = fs.create(new Path(FileOutputFormat
        .getOutputPath(job), peer.getTaskId() + ".log"));

    outStream.writeChars("TestRootbeerHybridBSP.bspGpu executed on GPU!\n");
    List<StatsRow> stats = rootbeer.getStats();
    for (StatsRow row : stats) {
      outStream.writeChars("  StatsRow:\n");
      outStream.writeChars("    init time: " + row.getInitTime() + "\n");
      outStream.writeChars("    serial time: " + row.getSerializationTime()
          + "\n");
      outStream.writeChars("    exec time: " + row.getExecutionTime() + "\n");
      outStream.writeChars("    deserial time: " + row.getDeserializationTime()
          + "\n");
      outStream.writeChars("    num blocks: " + row.getNumBlocks() + "\n");
      outStream.writeChars("    num threads: " + row.getNumThreads() + "\n");
    }

    outStream.writeChars("TestRootbeerHybridBSP,GPUTime="
        + watch.elapsedTimeMillis() + " ms\n");
    outStream.writeChars("TestRootbeerHybridBSP,peerName: '" + kernel.peerName
        + "'\n");
    // outStream.writeChars("TestRootbeerHybridBSP,input: '"
    // + Arrays.toString(kernel.input) + "'\n");

    // Verify input
    peer.reopenInput();
    IntWritable key = new IntWritable();
    IntWritable value = new IntWritable();
    while (peer.readNext(key, value)) {
      Assert.assertEquals(value.get(), kernel.input[key.get()]);
    }

    outStream.writeChars("TestRootbeerHybridBSP,input verified!'\n");
    outStream.close();
  }

  public static BSPJob createTestRootbeerHybridBSPConf(Path inPath, Path outPath)
      throws IOException {
    return createTestRootbeerHybridBSPConf(new HamaConfiguration(), inPath,
        outPath);
  }

  public static BSPJob createTestRootbeerHybridBSPConf(Configuration conf,
      Path inPath, Path outPath) throws IOException {

    BSPJob job = new BSPJob(new HamaConfiguration(conf),
        TestRootbeerHybridBSP.class);
    // Set the job name
    job.setJobName("TestRootbeerHybrid Example");
    // set the BSP class which shall be executed
    job.setBspClass(TestRootbeerHybridBSP.class);
    // help Hama to locale the jar to be distributed
    job.setJarByClass(TestRootbeerHybridBSP.class);

    job.setInputFormat(SequenceFileInputFormat.class);
    job.setInputKeyClass(IntWritable.class);
    job.setInputValueClass(IntWritable.class);
    job.setInputPath(inPath);

    job.setOutputFormat(SequenceFileOutputFormat.class);
    job.setOutputKeyClass(NullWritable.class);
    job.setOutputValueClass(IntWritable.class);
    job.setOutputPath(outPath);

    job.setMessageClass(NullWritable.class);

    job.set("bsp.child.java.opts", "-Xmx4G");

    return job;
  }

  private static void prepareInput(Configuration conf, FileSystem fs,
      Path path, int n, int maxVal) throws IOException {

    SequenceFile.Writer inputWriter = SequenceFile.createWriter(fs, conf, path,
        IntWritable.class, IntWritable.class, CompressionType.NONE);
    LOG.info("prepareInput path: " + path.toString());

    Random r = new Random();
    for (int i = 0; i < n; i++) {
      int val = r.nextInt(maxVal);
      inputWriter.append(new IntWritable(i), new IntWritable(val));
      // LOG.info("prepareInput key: '" + i + "' value: '" + val + "'");
    }
    inputWriter.close();
  }

  static void printOutput(BSPJob job, FileSystem fs) throws IOException {
    FileStatus[] files = fs.listStatus(FileOutputFormat.getOutputPath(job));
    for (int i = 0; i < files.length; i++) {
      if (files[i].getLen() > 0) {
        System.out.println("File " + files[i].getPath());
        SequenceFile.Reader reader = null;
        try {
          reader = new SequenceFile.Reader(fs, files[i].getPath(),
              job.getConfiguration());

          NullWritable key = NullWritable.get();
          IntWritable value = new IntWritable();

          while (reader.next(key, value)) {
            System.out.println("key: '" + key + "' value: '" + value.get()
                + "'\n");
          }
        } catch (IOException e) {

          FSDataInputStream in = fs.open(files[i].getPath());
          IOUtils.copyBytes(in, System.out, job.getConfiguration(), false);
          in.close();

        } finally {
          if (reader != null) {
            reader.close();
          }
        }
      }
    }
    // fs.delete(FileOutputFormat.getOutputPath(job), true);
  }

  public static void main(String[] args) throws InterruptedException,
      IOException, ClassNotFoundException {

    Configuration conf = new HamaConfiguration();

    if (args.length > 0) {
      if (args.length == 1) {
        conf.setInt("bsp.peers.num", Integer.parseInt(args[0]));
      } else {
        System.out.println("Wrong argument size!");
        System.out.println("    Argument1=numBspTask");
        return;
      }
    } else {
      // BSPJobClient jobClient = new BSPJobClient(conf);
      // ClusterStatus cluster = jobClient.getClusterStatus(true);
      // job.setNumBspTask(cluster.getMaxTasks());
      conf.setInt("bsp.peers.num", 1); // (1 CPU and) 1 GPU task only
    }
    // Enable one GPU task
    conf.setInt("bsp.peers.gpu.num", 1);
    conf.setBoolean("hama.pipes.logging", false);

    LOG.info("NumBspTask: " + conf.getInt("bsp.peers.num", 0));
    LOG.info("NumBspGpuTask: " + conf.getInt("bsp.peers.gpu.num", 0));
    LOG.info("bsp.tasks.maximum: " + conf.get("bsp.tasks.maximum"));
    LOG.info("inputPath: " + CONF_INPUT_DIR);
    LOG.info("outputPath: " + CONF_OUTPUT_DIR);

    Path input = new Path(CONF_INPUT_DIR, "input.seq");
    Path example = new Path(CONF_INPUT_DIR, "/example/example.seq");
    conf.set(CONF_EXAMPLE_PATH, example.toString());

    FileSystem fs = FileSystem.get(conf);
    prepareInput(conf, fs, input, CONF_BLOCK_SIZE * CONF_GRID_SIZE, 100);
    prepareInput(conf, fs, example, CONF_BLOCK_SIZE * CONF_GRID_SIZE, 100);

    BSPJob job = createTestRootbeerHybridBSPConf(conf, CONF_INPUT_DIR,
        CONF_OUTPUT_DIR);

    long startTime = System.currentTimeMillis();
    if (job.waitForCompletion(true)) {
      printOutput(job, fs);
      LOG.info("Job Finished in " + (System.currentTimeMillis() - startTime)
          / 1000.0 + " seconds");
    }
  }

}
