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
package at.illecker.hama.hybrid.examples.summation;

import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Random;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.io.Text;
import org.apache.hama.HamaConfiguration;
import org.apache.hama.bsp.BSPJob;
import org.apache.hama.bsp.BSPPeer;
import org.apache.hama.bsp.FileInputFormat;
import org.apache.hama.bsp.FileOutputFormat;
import org.apache.hama.bsp.KeyValueTextInputFormat;
import org.apache.hama.bsp.SequenceFileOutputFormat;
import org.apache.hama.bsp.gpu.HybridBSP;
import org.apache.hama.bsp.sync.SyncException;

import edu.syr.pcpratts.rootbeer.runtime.Rootbeer;
import edu.syr.pcpratts.rootbeer.runtime.StatsRow;
import edu.syr.pcpratts.rootbeer.runtime.util.Stopwatch;

public class SummationBSP extends
    HybridBSP<Text, Text, Text, DoubleWritable, DoubleWritable> {

  private static final Log LOG = LogFactory.getLog(SummationBSP.class);
  private static final Path INPUT_PATH = new Path(
      "input/hama/hybrid/examples/summation");
  private static final Path TMP_OUTPUT = new Path(
      "output/hama/hybrid/examples/summation-" + System.currentTimeMillis());
  public static final int DOUBLE_PRECISION = 6;

  private String m_masterTask;

  @Override
  public void setup(
      BSPPeer<Text, Text, Text, DoubleWritable, DoubleWritable> peer)
      throws IOException, SyncException, InterruptedException {
    // Choose first as master
    this.m_masterTask = peer.getPeerName(0);
  }

  @Override
  public void setupGpu(
      BSPPeer<Text, Text, Text, DoubleWritable, DoubleWritable> peer)
      throws IOException, SyncException, InterruptedException {
    setup(peer);
  }

  @Override
  public void bsp(BSPPeer<Text, Text, Text, DoubleWritable, DoubleWritable> peer)
      throws IOException, SyncException, InterruptedException {

    BSPJob job = new BSPJob((HamaConfiguration) peer.getConfiguration());
    FileSystem fs = FileSystem.get(peer.getConfiguration());
    FSDataOutputStream outStream = fs.create(new Path(FileOutputFormat
        .getOutputPath(job), peer.getTaskId() + ".log"));

    outStream.writeChars("SummationBSP.bsp executed on CPU!\n");

    double intermediateSum = 0.0;
    Text key = new Text();
    Text value = new Text();

    while (peer.readNext(key, value)) {
      outStream.writeChars("SummationBSP.bsp key: " + key + " value: " + value
          + "\n");
      intermediateSum += Double.parseDouble(value.toString());
    }

    outStream.writeChars("SummationBSP.bsp send intermediateSum: "
        + intermediateSum + "\n");

    peer.send(m_masterTask, new DoubleWritable(intermediateSum));
    peer.sync();

    // Consume messages
    if (peer.getPeerName().equals(m_masterTask)) {
      outStream.writeChars("SummationBSP.bsp consume messages...\n");

      double sum = 0.0;
      int msg_count = peer.getNumCurrentMessages();

      for (int i = 0; i < msg_count; i++) {
        DoubleWritable msg = peer.getCurrentMessage();
        outStream.writeChars("SummationBSP.bsp message: " + msg.get() + "\n");
        sum += msg.get();
      }

      outStream.writeChars("SummationBSP.bsp write Sum: " + sum + "\n");
      peer.write(new Text("Sum"), new DoubleWritable(sum));
    }
    outStream.close();
  }

  @Override
  public void bspGpu(
      BSPPeer<Text, Text, Text, DoubleWritable, DoubleWritable> peer,
      Rootbeer rootbeer) throws IOException, SyncException,
      InterruptedException {

    BSPJob job = new BSPJob((HamaConfiguration) peer.getConfiguration());
    FileSystem fs = FileSystem.get(peer.getConfiguration());
    FSDataOutputStream outStream = fs.create(new Path(FileOutputFormat
        .getOutputPath(job), peer.getTaskId() + ".log"));

    outStream.writeChars("SummationBSP.bspGpu executed on GPU!\n");

    SummationKernel kernel = new SummationKernel(m_masterTask);
    // 1 Kernel within 1 Block
    rootbeer.setThreadConfig(1, 1, 1);

    // Run GPU Kernels
    Stopwatch watch = new Stopwatch();
    watch.start();
    rootbeer.runAll(kernel);
    watch.stop();

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

    outStream.writeChars("SummationBSP,GPUTime=" + watch.elapsedTimeMillis()
        + "ms\n");
    outStream.writeChars("SummationBSP,peerName: '" + kernel.peerName + "'\n");
    outStream.writeChars("SummationBSP,numPeers: '" + kernel.numPeers + "'\n");
    outStream.close();
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

  static BigDecimal writeSummationInputFile(FileSystem fs, Path dir)
      throws IOException {
    DataOutputStream out = fs.create(new Path(dir, "part0"));
    Random rand = new Random();
    double rangeMin = 0;
    double rangeMax = 100;
    BigDecimal sum = new BigDecimal(0);
    // loop between 50 and 149 times
    for (int i = 0; i < rand.nextInt(100) + 50; i++) {
      // generate key value pair inputs
      double randomValue = rangeMin + (rangeMax - rangeMin) * rand.nextDouble();
      String truncatedValue = new BigDecimal(randomValue).setScale(
          DOUBLE_PRECISION, BigDecimal.ROUND_DOWN).toString();

      String line = "key" + (i + 1) + "\t" + truncatedValue + "\n";
      out.writeBytes(line);

      sum = sum.add(new BigDecimal(truncatedValue));
      LOG.info("input[" + i + "]: '" + line + "' sum: " + sum.toString());
    }
    out.close();
    return sum;
  }

  @Override
  public Class<DoubleWritable> getMessageClass() {
    return DoubleWritable.class;
  }

  static BSPJob getSummationJob(HamaConfiguration conf) throws IOException {
    BSPJob bsp = new BSPJob(conf);
    bsp.setInputFormat(KeyValueTextInputFormat.class);
    bsp.setInputKeyClass(Text.class);
    bsp.setInputValueClass(Text.class);
    bsp.setOutputFormat(SequenceFileOutputFormat.class);
    bsp.setOutputKeyClass(Text.class);
    bsp.setOutputValueClass(DoubleWritable.class);
    bsp.set("bsp.message.class", DoubleWritable.class.getName());
    return bsp;
  }

  public static void main(String[] args) throws InterruptedException,
      IOException, ClassNotFoundException {
    // BSP job configuration
    HamaConfiguration conf = new HamaConfiguration();
    BSPJob job = getSummationJob(conf);
    // Set the job name
    job.setJobName("HybridSummation Example");
    // set the BSP class which shall be executed
    job.setBspClass(SummationBSP.class);
    // help Hama to locale the jar to be distributed
    job.setJarByClass(SummationBSP.class);

    FileInputFormat.setInputPaths(job, INPUT_PATH);
    FileOutputFormat.setOutputPath(job, TMP_OUTPUT);

    FileSystem fs = FileSystem.get(conf);

    // Generate Summation input
    fs.delete(INPUT_PATH, true);
    BigDecimal sum = writeSummationInputFile(fs, INPUT_PATH);
    LOG.info("Sum: " + sum.toString());

    // BSPJobClient jobClient = new BSPJobClient(conf);
    // ClusterStatus cluster = jobClient.getClusterStatus(true);

    if (args.length > 0) {
      if (args.length == 1) {
        job.setNumBspTask(Integer.parseInt(args[0]));
      } else {
        System.out.println("Wrong argument size!");
        System.out.println("    Argument1=numBspTask");
        return;
      }
    } else {
      // job.setNumBspTask(cluster.getMaxTasks());
      job.setNumBspTask(2); // 1 CPU and 1 GPU
    }
    job.setNumBspGpuTask(1);

    LOG.info("NumBspTask: " + job.getNumBspTask());
    LOG.info("NumBspGpuTask: " + job.getNumBspGpuTask());

    long startTime = System.currentTimeMillis();
    if (job.waitForCompletion(true)) {
      printOutput(job);
      System.out.println("Job Finished in "
          + (System.currentTimeMillis() - startTime) / 1000.0 + " seconds");
    }
  }

}
