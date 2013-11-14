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
package at.illecker.hama.hybrid.examples.hellohybrid;

import java.io.IOException;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.io.NullWritable;
import org.apache.hama.HamaConfiguration;
import org.apache.hama.bsp.BSPJob;
import org.apache.hama.bsp.BSPJobClient;
import org.apache.hama.bsp.BSPPeer;
import org.apache.hama.bsp.ClusterStatus;
import org.apache.hama.bsp.FileOutputFormat;
import org.apache.hama.bsp.NullInputFormat;
import org.apache.hama.bsp.NullOutputFormat;
import org.apache.hama.bsp.gpu.HybridBSP;
import org.apache.hama.bsp.sync.SyncException;

import edu.syr.pcpratts.rootbeer.runtime.Rootbeer;
import edu.syr.pcpratts.rootbeer.runtime.StatsRow;
import edu.syr.pcpratts.rootbeer.runtime.util.Stopwatch;

public class HelloHybridBSP
    extends
    HybridBSP<NullWritable, NullWritable, NullWritable, NullWritable, NullWritable> {

  private static final Log LOG = LogFactory.getLog(HelloHybridBSP.class);
  private static final Path TMP_OUTPUT = new Path(
      "output/hama/hybrid/examples/hellohybrid-" + System.currentTimeMillis());

  @Override
  public Class<NullWritable> getMessageClass() {
    return NullWritable.class;
  }

  @Override
  public void bsp(
      BSPPeer<NullWritable, NullWritable, NullWritable, NullWritable, NullWritable> peer)
      throws IOException, SyncException, InterruptedException {

    BSPJob job = new BSPJob((HamaConfiguration) peer.getConfiguration());
    FileSystem fs = FileSystem.get(peer.getConfiguration());
    FSDataOutputStream outStream = fs.create(new Path(FileOutputFormat
        .getOutputPath(job), peer.getTaskId() + ".log"));

    outStream.writeChars("HelloHybrid.bsp executed on CPU!\n");
    outStream.close();
  }

  @Override
  public void bspGpu(
      BSPPeer<NullWritable, NullWritable, NullWritable, NullWritable, NullWritable> peer,
      Rootbeer rootbeer) throws IOException, SyncException, InterruptedException {

    BSPJob job = new BSPJob((HamaConfiguration) peer.getConfiguration());
    FileSystem fs = FileSystem.get(peer.getConfiguration());
    FSDataOutputStream outStream = fs.create(new Path(FileOutputFormat
        .getOutputPath(job), peer.getTaskId() + ".log"));

    outStream.writeChars("HelloHybrid.bspGpu executed on GPU!\n");

    HelloHybridKernel kernel = new HelloHybridKernel(1);
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

    outStream.writeChars("HelloHybridKernel,Result=" + kernel.result
        + ",GPUTime=" + watch.elapsedTimeMillis() + "ms\n");
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

  public static void main(String[] args) throws InterruptedException,
      IOException, ClassNotFoundException {
    // BSP job configuration
    HamaConfiguration conf = new HamaConfiguration();

    BSPJob job = new BSPJob(conf);
    // Set the job name
    job.setJobName("HelloHybrid Example");
    // set the BSP class which shall be executed
    job.setBspClass(HelloHybridBSP.class);
    // help Hama to locale the jar to be distributed
    job.setJarByClass(HelloHybridBSP.class);

    job.setInputFormat(NullInputFormat.class);
    job.setOutputKeyClass(NullWritable.class);
    job.setOutputValueClass(NullWritable.class);
    job.setOutputFormat(NullOutputFormat.class);
    FileOutputFormat.setOutputPath(job, TMP_OUTPUT);

    BSPJobClient jobClient = new BSPJobClient(conf);
    ClusterStatus cluster = jobClient.getClusterStatus(true);

    if (args.length > 0) {
      if (args.length == 1) {
        job.setNumBspTask(Integer.parseInt(args[0]));
      } else {
        System.out.println("Wrong argument size!");
        System.out.println("    Argument1=numBspTask");
        return;
      }
    } else {
      job.setNumBspTask(cluster.getMaxTasks());
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
