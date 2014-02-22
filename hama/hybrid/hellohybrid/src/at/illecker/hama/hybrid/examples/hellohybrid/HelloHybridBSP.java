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
import org.apache.hama.HamaConfiguration;
import org.apache.hama.bsp.BSPJob;
import org.apache.hama.bsp.BSPPeer;
import org.apache.hama.bsp.FileOutputFormat;
import org.apache.hama.bsp.SequenceFileInputFormat;
import org.apache.hama.bsp.SequenceFileOutputFormat;
import org.apache.hama.bsp.gpu.HybridBSP;
import org.apache.hama.bsp.sync.SyncException;
import org.trifort.rootbeer.runtime.Context;
import org.trifort.rootbeer.runtime.Rootbeer;
import org.trifort.rootbeer.runtime.StatsRow;
import org.trifort.rootbeer.runtime.ThreadConfig;
import org.trifort.rootbeer.runtime.util.Stopwatch;

public class HelloHybridBSP
    extends
    HybridBSP<IntWritable, NullWritable, IntWritable, NullWritable, NullWritable> {

  private static final Log LOG = LogFactory.getLog(HelloHybridBSP.class);
  private static final Path CONF_TMP_DIR = new Path(
      "output/hama/hybrid/examples/hellohybrid/hybrid-"
          + System.currentTimeMillis());
  private static final Path CONF_INPUT_DIR = new Path(CONF_TMP_DIR, "input");
  private static final Path CONF_OUTPUT_DIR = new Path(CONF_TMP_DIR, "output");
  public static final String CONF_EXAMPLE_PATH = "hellohybrid.example.path";
  public static final int CONF_N = 10;

  @Override
  public void bsp(
      BSPPeer<IntWritable, NullWritable, IntWritable, NullWritable, NullWritable> peer)
      throws IOException, SyncException, InterruptedException {

    BSPJob job = new BSPJob((HamaConfiguration) peer.getConfiguration());
    FileSystem fs = FileSystem.get(peer.getConfiguration());
    FSDataOutputStream outStream = fs.create(new Path(FileOutputFormat
        .getOutputPath(job), peer.getTaskId() + ".log"));

    outStream.writeChars("HelloHybrid.bsp executed on CPU!\n");

    ArrayList<Integer> summation = new ArrayList<Integer>();

    // test input
    IntWritable key = new IntWritable();
    NullWritable nullValue = NullWritable.get();

    while (peer.readNext(key, nullValue)) {
      outStream.writeChars("input: key: '" + key.get() + "'\n");
      summation.add(key.get());
    }

    // test sequenceFileReader
    Path example = new Path(peer.getConfiguration().get(CONF_EXAMPLE_PATH));
    SequenceFile.Reader reader = null;
    try {
      reader = new SequenceFile.Reader(fs, example, peer.getConfiguration());

      int i = 0;
      while (reader.next(key, nullValue)) {
        outStream.writeChars("sequenceFileReader: key: '" + key.get() + "'\n");
        if (i < summation.size()) {
          summation.set(i, summation.get(i) + key.get());
        }
        i++;
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    } finally {
      if (reader != null) {
        reader.close();
      }
    }

    // test output
    for (Integer i : summation) {
      key.set(i);
      outStream.writeChars("output: key: '" + key.get() + "'\n");
      peer.write(key, nullValue);
    }

    // test getAllPeerNames
    outStream.writeChars("getAllPeerNames: '"
        + Arrays.toString(peer.getAllPeerNames()) + "'\n");

    // test String.split
    String splitString = "boo:and:foo";
    String[] splits;

    outStream.writeChars("splitString: '" + splitString + "'\n");

    splits = splitString.split(":");
    outStream.writeChars("split(\":\") len: " + splits.length + " values: '"
        + Arrays.toString(splits) + "'\n");

    splits = splitString.split(":", 2);
    outStream.writeChars("split(\":\",2) len: " + splits.length + " values: '"
        + Arrays.toString(splits) + "'\n");

    splits = splitString.split(":", 5);
    outStream.writeChars("split(\":\",5) len: " + splits.length + " values: '"
        + Arrays.toString(splits) + "'\n");

    splits = splitString.split(":", -2);
    outStream.writeChars("split(\":\",-2) len: " + splits.length + " values: '"
        + Arrays.toString(splits) + "'\n");

    splits = splitString.split(";");
    outStream.writeChars("split(\";\") len: " + splits.length + " values: '"
        + Arrays.toString(splits) + "'\n");

    outStream.close();
  }

  @Override
  public void bspGpu(
      BSPPeer<IntWritable, NullWritable, IntWritable, NullWritable, NullWritable> peer,
      Rootbeer rootbeer) throws IOException, SyncException,
      InterruptedException {

    BSPJob job = new BSPJob((HamaConfiguration) peer.getConfiguration());
    FileSystem fs = FileSystem.get(peer.getConfiguration());
    FSDataOutputStream outStream = fs.create(new Path(FileOutputFormat
        .getOutputPath(job), peer.getTaskId() + ".log"));

    outStream.writeChars("HelloHybrid.bspGpu executed on GPU!\n");

    HelloHybridKernel kernel = new HelloHybridKernel(peer.getConfiguration()
        .get(CONF_EXAMPLE_PATH), CONF_N, "boo:and:foo", ":");

    // Run GPU Kernels
    Context context = rootbeer.createDefaultContext();
    Stopwatch watch = new Stopwatch();
    watch.start();
    // 1 Kernel within 1 Block
    rootbeer.run(kernel, new ThreadConfig(1, 1, 1), context);
    watch.stop();

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

    outStream.writeChars("HelloHybridKernel,GPUTime="
        + watch.elapsedTimeMillis() + "ms\n");
    outStream.writeChars("HelloHybridKernel,peerName: '" + kernel.peerName
        + "'\n");
    outStream.writeChars("HelloHybridKernel,numPeers: '" + kernel.numPeers
        + "'\n");
    outStream.writeChars("HelloHybridKernel,summation: '"
        + Arrays.toString(kernel.summation) + "'\n");
    outStream.writeChars("HelloHybridKernel,getAllPeerNames: '"
        + Arrays.toString(kernel.allPeerNames) + "'\n");

    // test String.split
    outStream.writeChars("HelloHybridKernel,splitString: '"
        + kernel.splitString + "'\n");
    outStream.writeChars("HelloHybridKernel,split(\"" + kernel.delimiter
        + "\") len: " + kernel.splits1.length + " values: '"
        + Arrays.toString(kernel.splits1) + "'\n");
    outStream.writeChars("HelloHybridKernel,split(\"" + kernel.delimiter
        + "\",2) len: " + kernel.splits2.length + " values: '"
        + Arrays.toString(kernel.splits2) + "'\n");
    outStream.writeChars("HelloHybridKernel,split(\"" + kernel.delimiter
        + "\",5) len: " + kernel.splits3.length + " values: '"
        + Arrays.toString(kernel.splits3) + "'\n");
    outStream.writeChars("HelloHybridKernel,split(\"" + kernel.delimiter
        + "\",-2) len: " + kernel.splits4.length + " values: '"
        + Arrays.toString(kernel.splits4) + "'\n");
    outStream.writeChars("HelloHybridKernel,split(\";\") len: "
        + kernel.splits5.length + " values: '"
        + Arrays.toString(kernel.splits5) + "'\n");

    outStream.close();
  }

  public static BSPJob createHelloHybridBSPConf(Path inPath, Path outPath)
      throws IOException {
    return createHelloHybridBSPConf(new HamaConfiguration(), inPath, outPath);
  }

  public static BSPJob createHelloHybridBSPConf(Configuration conf,
      Path inPath, Path outPath) throws IOException {

    BSPJob job = new BSPJob(new HamaConfiguration(conf), HelloHybridBSP.class);
    // Set the job name
    job.setJobName("HelloHybrid Example");
    // set the BSP class which shall be executed
    job.setBspClass(HelloHybridBSP.class);
    // help Hama to locale the jar to be distributed
    job.setJarByClass(HelloHybridBSP.class);

    job.setInputFormat(SequenceFileInputFormat.class);
    job.setInputKeyClass(IntWritable.class);
    job.setInputValueClass(NullWritable.class);
    job.setInputPath(inPath);

    job.setOutputFormat(SequenceFileOutputFormat.class);
    job.setOutputKeyClass(IntWritable.class);
    job.setOutputValueClass(NullWritable.class);
    job.setOutputPath(outPath);

    job.setMessageClass(NullWritable.class);
    job.set("bsp.child.java.opts", "-Xmx4G");

    return job;
  }

  private static void prepareInput(Configuration conf, Path inputPath,
      Path exampleFile, int n) throws IOException {
    FileSystem fs = inputPath.getFileSystem(conf);

    // Create input file writers depending on bspTaskNum
    int bspTaskNum = conf.getInt("bsp.peers.num", 1);
    SequenceFile.Writer[] inputWriters = new SequenceFile.Writer[bspTaskNum];
    for (int i = 0; i < bspTaskNum; i++) {
      Path inputFile = new Path(inputPath, "input" + i + ".seq");
      LOG.info("inputFile: " + inputFile.toString());
      inputWriters[i] = SequenceFile.createWriter(fs, conf, inputFile,
          IntWritable.class, NullWritable.class, CompressionType.NONE);
    }

    // Create example file writer
    SequenceFile.Writer exampleWriter = SequenceFile.createWriter(fs, conf,
        exampleFile, IntWritable.class, NullWritable.class,
        CompressionType.NONE);

    // Write random values to input files and example
    IntWritable inputKey = new IntWritable();
    NullWritable nullValue = NullWritable.get();
    Random r = new Random();
    for (long i = 0; i < n; i++) {
      inputKey.set(r.nextInt(n));
      for (int j = 0; j < inputWriters.length; j++) {
        inputWriters[j].append(inputKey, nullValue);
      }
      inputKey.set(r.nextInt(n));
      exampleWriter.append(inputKey, nullValue);
    }

    // Close file writers
    for (int j = 0; j < inputWriters.length; j++) {
      inputWriters[j].close();
    }
    exampleWriter.close();
  }

  static void printOutput(BSPJob job, Path path) throws IOException {
    FileSystem fs = path.getFileSystem(job.getConfiguration());
    FileStatus[] files = fs.listStatus(path);
    for (int i = 0; i < files.length; i++) {
      if (files[i].getLen() > 0) {
        System.out.println("File " + files[i].getPath());
        SequenceFile.Reader reader = null;
        try {
          reader = new SequenceFile.Reader(fs, files[i].getPath(),
              job.getConfiguration());

          IntWritable key = new IntWritable();
          NullWritable value = NullWritable.get();
          while (reader.next(key, value)) {
            System.out.println("key: '" + key.get() + "' value: '" + value
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
      conf.setInt("bsp.peers.num", 2); // 1 CPU and 1 GPU
    }
    // Enable one GPU task
    conf.setInt("bsp.peers.gpu.num", 1);
    conf.setBoolean("hama.pipes.logging", true);

    LOG.info("NumBspTask: " + conf.getInt("bsp.peers.num", 0));
    LOG.info("NumBspGpuTask: " + conf.getInt("bsp.peers.gpu.num", 0));
    LOG.info("bsp.tasks.maximum: " + conf.get("bsp.tasks.maximum"));
    LOG.info("inputPath: " + CONF_INPUT_DIR);
    LOG.info("outputPath: " + CONF_OUTPUT_DIR);

    Path example = new Path(CONF_INPUT_DIR.getParent(), "example.seq");
    conf.set(CONF_EXAMPLE_PATH, example.toString());
    LOG.info("exampleFile: " + example.toString());

    prepareInput(conf, CONF_INPUT_DIR, example, CONF_N);

    BSPJob job = createHelloHybridBSPConf(conf, CONF_INPUT_DIR, CONF_OUTPUT_DIR);

    long startTime = System.currentTimeMillis();
    if (job.waitForCompletion(true)) {
      LOG.info("Job Finished in " + (System.currentTimeMillis() - startTime)
          / 1000.0 + " seconds");

      // printOutput(job, CONF_INPUT_DIR);
      // printOutput(job, example);
      printOutput(job, FileOutputFormat.getOutputPath(job));
    }
  }

}
