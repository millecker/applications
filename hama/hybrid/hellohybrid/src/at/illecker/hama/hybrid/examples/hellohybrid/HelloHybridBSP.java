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

public class HelloHybridBSP
    extends
    HybridBSP<NullWritable, NullWritable, NullWritable, NullWritable, NullWritable> {

  private static final Log LOG = LogFactory.getLog(HelloHybridBSP.class);
  private static final Path TMP_OUTPUT = new Path(
      "output/hama/hybrid/examples/hellohybrid-" + System.currentTimeMillis());

  @Override
  public void bsp(
      BSPPeer<NullWritable, NullWritable, NullWritable, NullWritable, NullWritable> peer)
      throws IOException, SyncException, InterruptedException {

    BSPJob job = new BSPJob((HamaConfiguration) peer.getConfiguration());
    FileSystem fs = FileSystem.get(peer.getConfiguration());
    FSDataOutputStream outStream = fs.create(new Path(FileOutputFormat
        .getOutputPath(job), peer.getTaskId() + ".log"));

    outStream.writeChars("HelloHybrid.bsp executed!\n");
    outStream.close();
  }

  @Override
  public void bspGpu(
      BSPPeer<NullWritable, NullWritable, NullWritable, NullWritable, NullWritable> peer)
      throws IOException, SyncException, InterruptedException {

    BSPJob job = new BSPJob((HamaConfiguration) peer.getConfiguration());
    FileSystem fs = FileSystem.get(peer.getConfiguration());
    FSDataOutputStream outStream = fs.create(new Path(FileOutputFormat
        .getOutputPath(job), peer.getTaskId() + ".log"));

    outStream.writeChars("HelloHybrid.bspGpu executed!\n");
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

    LOG.info("NumBspTask: " + job.getNumBspTask());

    long startTime = System.currentTimeMillis();
    if (job.waitForCompletion(true)) {
      printOutput(job);
      System.out.println("Job Finished in "
          + (System.currentTimeMillis() - startTime) / 1000.0 + " seconds");
    }
  }
}
