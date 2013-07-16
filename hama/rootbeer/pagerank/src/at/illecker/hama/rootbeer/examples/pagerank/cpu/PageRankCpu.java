package at.illecker.hama.rootbeer.examples.pagerank.cpu;

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
import java.io.IOException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hama.HamaConfiguration;
import org.apache.hama.bsp.HashPartitioner;
import org.apache.hama.bsp.SequenceFileInputFormat;
import org.apache.hama.bsp.TextArrayWritable;
import org.apache.hama.bsp.TextOutputFormat;
import org.apache.hama.graph.AverageAggregator;
import org.apache.hama.graph.Edge;
import org.apache.hama.graph.GraphJob;
import org.apache.hama.graph.Vertex;
import org.apache.hama.graph.VertexInputReader;

/**
 * @author Real pagerank with dangling node contribution from
 *         https://github.com/apache
 *         /hama/blob/trunk/examples/src/main/java/org/apache
 *         /hama/examples/PageRank.java
 * 
 *         100000 nodes / 1000000 edges (8 cores 8GB mem) using 8 bsp tasks:
 * 
 *         pagerank Hama 0.6.2: 98.794 secs - Hama 0.5.0: 84.925secs
 * 
 */

public class PageRankCpu {
  private static final Log LOG = LogFactory.getLog(PageRankCpu.class);

  public static class PageRankVertexCpu extends
      Vertex<Text, NullWritable, DoubleWritable> {

    // DAMPING_FACTOR: the probability, at any step,
    // that the person will continue
    static double DAMPING_FACTOR = 0.85;
    static double MAXIMUM_CONVERGENCE_ERROR = 0.001;

    @Override
    public void setup(Configuration conf) {
      String val = conf.get("hama.pagerank.alpha");
      if (val != null) {
        DAMPING_FACTOR = Double.parseDouble(val);
      }
      val = conf.get("hama.graph.max.convergence.error");
      if (val != null) {
        MAXIMUM_CONVERGENCE_ERROR = Double.parseDouble(val);
      }
    }

    @Override
    public void compute(Iterable<DoubleWritable> messages) throws IOException {
      // initialize this vertex to 1 / count of global vertices in this
      // graph
      if (this.getSuperstepCount() == 0) {
        this.setValue(new DoubleWritable(1.0 / this.getNumVertices()));

      } else if (this.getSuperstepCount() >= 1) {

        double sum = 0;
        for (DoubleWritable msg : messages) {
          sum += msg.get();
        }
        double alpha = (1.0d - DAMPING_FACTOR) / this.getNumVertices();
        this.setValue(new DoubleWritable(alpha + (sum * DAMPING_FACTOR)));

      }

      // if we have not reached our global error yet, then proceed.
      DoubleWritable globalError = getLastAggregatedValue(0);
      if (globalError != null && this.getSuperstepCount() > 2
          && MAXIMUM_CONVERGENCE_ERROR > globalError.get()) {
        voteToHalt();
        return;
      }

      // in each superstep we are going to send a new rank to our
      // neighbours
      sendMessageToNeighbors(new DoubleWritable(this.getValue().get()
          / this.getEdges().size()));
    }

  }

  public static class PagerankSeqReader
      extends
      VertexInputReader<Text, TextArrayWritable, Text, NullWritable, DoubleWritable> {
    @Override
    public boolean parseVertex(Text key, TextArrayWritable value,
        Vertex<Text, NullWritable, DoubleWritable> vertex) throws Exception {
      vertex.setVertexID(key);

      for (Writable v : value.get()) {
        vertex.addEdge(new Edge<Text, NullWritable>((Text) v, null));
      }

      return true;
    }
  }

  public static GraphJob createJob(String[] args, HamaConfiguration conf)
      throws IOException {
    GraphJob job = new GraphJob(conf, PageRankCpu.class);
    job.setJobName("Pagerank CPU");

    job.setVertexClass(PageRankVertexCpu.class);
    job.setInputPath(new Path(args[0]));
    job.setOutputPath(new Path(args[1]));

    // set the defaults
    job.setMaxIteration(30);
    job.set("hama.pagerank.alpha", "0.85");
    // reference vertices to itself, because we don't have a dangling node
    // contribution here
    job.set("hama.graph.self.ref", "true");
    job.set("hama.graph.max.convergence.error", "0.001");

    if (args.length == 3) {
      job.setNumBspTask(Integer.parseInt(args[2]));
    } else {
      job.setNumBspTask(1);
    }

    LOG.info("DEBUG: NumBspTask: " + job.getNumBspTask());
    LOG.info("DEBUG: bsp.job.split.file: " + job.get("bsp.job.split.file"));
    LOG.info("DEBUG: bsp.peers.num: " + job.get("bsp.peers.num"));
    LOG.info("DEBUG: bsp.tasks.maximum: " + job.get("bsp.tasks.maximum"));
    LOG.info("DEBUG: bsp.input.dir: " + job.get("bsp.input.dir"));

    // error
    job.setAggregatorClass(AverageAggregator.class);

    // Vertex reader
    job.setVertexInputReaderClass(PagerankSeqReader.class);

    job.setVertexIDClass(Text.class);
    job.setVertexValueClass(DoubleWritable.class);
    job.setEdgeValueClass(NullWritable.class);

    job.setInputFormat(SequenceFileInputFormat.class);

    job.setPartitioner(HashPartitioner.class);
    job.setOutputFormat(TextOutputFormat.class);
    job.setOutputKeyClass(Text.class);
    job.setOutputValueClass(DoubleWritable.class);
    return job;
  }

  private static void printUsage() {
    System.out.println("Usage: <input> <output> [tasks]");
    System.exit(-1);
  }

  public static void main(String[] args) throws IOException,
      InterruptedException, ClassNotFoundException {
    if (args.length < 2)
      printUsage();

    HamaConfiguration conf = new HamaConfiguration(new Configuration());
    GraphJob pageJob = createJob(args, conf);

    long startTime = System.currentTimeMillis();
    if (pageJob.waitForCompletion(true)) {
      System.out.println("Job Finished in "
          + (System.currentTimeMillis() - startTime) / 1000.0 + " seconds");
    }
  }
}
