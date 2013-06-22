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
package at.illecker.hama.rootbeer.examples.matrixmultiplication.cpu;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

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
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.join.CompositeInputFormat;
import org.apache.hadoop.mapred.join.TupleWritable;
import org.apache.hama.HamaConfiguration;
import org.apache.hama.bsp.BSP;
import org.apache.hama.bsp.BSPJob;
import org.apache.hama.bsp.BSPJobClient;
import org.apache.hama.bsp.BSPPeer;
import org.apache.hama.bsp.ClusterStatus;
import org.apache.hama.bsp.FileOutputFormat;
import org.apache.hama.bsp.NullInputFormat;
import org.apache.hama.bsp.SequenceFileInputFormat;
import org.apache.hama.bsp.SequenceFileOutputFormat;
import org.apache.hama.bsp.TextOutputFormat;
import org.apache.hama.bsp.sync.SyncException;
import org.apache.mahout.math.VectorWritable;

import at.illecker.hama.rootbeer.examples.piestimator.PiEstimatorCpuBSP;

/**
 * @author MatrixMultiplication based on Mahout https://github.com/apache/mahout
 *         /blob/trunk/core/src/main/java/org/apache
 *         /mahout/math/hadoop/MatrixMultiplicationJob.java
 * 
 */

public class MatrixMultiplicationCpuBSP
		extends
		BSP<IntWritable, TupleWritable, IntWritable, VectorWritable, NullWritable> {

	private static final Log LOG = LogFactory
			.getLog(MatrixMultiplicationCpuBSP.class);

	private static final String OUT_CARD = "output.vector.cardinality";
	private static final String DEBUG = "matrixmultiplication.cpu.debug";

	private static final Path OUTPUT_DIR = new Path(
			"output/hadoop/rootbeer/examples/matrixmultiplication/CPU-"
					+ System.currentTimeMillis());

	private static final Path MATRIX_A_PATH = new Path(
			"input/hadoop/rootbeer/examples/MatrixA.seq");
	private static final Path MATRIX_B_PATH = new Path(
			"input/hadoop/rootbeer/examples/MatrixB.seq");
	private static final Path MATRIX_C_PATH = new Path(OUTPUT_DIR
			+ "/MatrixC.seq");

	@Override
	public void setup(
			BSPPeer<IntWritable, TupleWritable, IntWritable, VectorWritable, NullWritable> peer)
			throws IOException {

	}

	@Override
	public void bsp(
			BSPPeer<IntWritable, TupleWritable, IntWritable, VectorWritable, NullWritable> peer)
			throws IOException, SyncException, InterruptedException {

	}

	@Override
	public void cleanup(
			BSPPeer<IntWritable, TupleWritable, IntWritable, VectorWritable, NullWritable> peer)
			throws IOException {

	}

	static void printOutput(Configuration conf) throws IOException {
		FileSystem fs = OUTPUT_DIR.getFileSystem(conf);
		FileStatus[] files = fs.listStatus(OUTPUT_DIR);
		for (int i = 0; i < files.length; i++) {
			if (files[i].getLen() > 0) {
				System.out.println("File " + files[i].getPath());
				if (files[i].getPath().getName().endsWith(".log")) {
					FSDataInputStream in = fs.open(files[i].getPath());
					IOUtils.copyBytes(in, System.out, conf, false);
					in.close();
				}
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
		job.setJobName("MatrixMultiplicationCPU");
		// set the BSP class which shall be executed
		job.setBspClass(MatrixMultiplicationCpuBSP.class);
		// help Hama to locale the jar to be distributed
		job.setJarByClass(MatrixMultiplicationCpuBSP.class);

		job.setInputFormat(CompositeInputFormat.class);
		job.set("mapred.join.expr", CompositeInputFormat.compose("inner",
				SequenceFileInputFormat.class, aPath, bPath));

		job.setOutputFormat(SequenceFileOutputFormat.class);
		job.setOutputKeyClass(IntWritable.class);
		job.setOutputValueClass(VectorWritable.class);
		FileOutputFormat.setOutputPath(conf, outPath);
		FileOutputFormat.setOutputPath(job, TMP_OUTPUT);

		job.setInt(OUT_CARD, outCardinality);

		job.set("bsp.child.java.opts", "-Xmx4G");

		BSPJobClient jobClient = new BSPJobClient(conf);
		ClusterStatus cluster = jobClient.getClusterStatus(true);

		if (args.length > 0) {
			if (args.length == 3) {
				job.setNumBspTask(Integer.parseInt(args[0]));
				job.set("piestimator.threadCount", args[1]);
				job.set("piestimator.iterations", args[2]);
			} else {
				System.out.println("Wrong argument size!");
				System.out.println("    Argument1=NumBspTask");
				System.out.println("    Argument2=hellorootbeer.kernelCount");
				System.out.println("    Argument2=hellorootbeer.iterations");
				return;
			}
		} else {
			job.setNumBspTask(cluster.getMaxTasks());
			job.set("piestimator.threadCount", ""
					+ PiEstimatorCpuBSP.threadCount);
			job.set("piestimator.iterations", "" + PiEstimatorCpuBSP.iterations);
		}
		LOG.info("NumBspTask: " + job.getNumBspTask());
		LOG.info("ThreadCount: " + job.get("piestimator.threadCount"));
		LOG.info("Iterations: " + job.get("piestimator.iterations"));

		addOption("numRowsA", "nra",
				"Number of rows of the first input matrix", true);
		addOption("numColsA", "nca",
				"Number of columns of the first input matrix", true);

		addOption("numRowsB", "nrb",
				"Number of rows of the second input matrix", true);
		addOption("numColsB", "ncb",
				"Number of columns of the second input matrix", true);

		addOption("debug", "db", "Enable debugging (true|false)", false);

		Map<String, List<String>> argMap = parseArguments(strings);
		if (argMap == null) {
			return -1;
		}

		int numRowsA = Integer.parseInt(getOption("numRowsA"));
		int numColsA = Integer.parseInt(getOption("numColsA"));
		int numRowsB = Integer.parseInt(getOption("numRowsB"));
		int numColsB = Integer.parseInt(getOption("numColsB"));

		boolean isDebugging = Boolean.parseBoolean(getOption("debug"));
		LOG.info("numRowsA: " + numRowsA);
		LOG.info("numColsA: " + numColsA);
		LOG.info("numRowsB: " + numRowsB);
		LOG.info("numColsB: " + numColsB);
		LOG.info("isDebugging: " + isDebugging);
		LOG.info("outputPath: " + OUTPUT_DIR);

		if (numColsA != numRowsB) {
			throw new CardinalityException(numColsA, numRowsB);
		}

		Configuration conf = new Configuration(getConf());
		conf.setBoolean(DEBUG, isDebugging);

		// Create random DistributedRowMatrix
		// use constant seeds to get reproducable results
		// Matrix A is stored transposed
		DistributedRowMatrix.createRandomDistributedRowMatrix(conf, numRowsA,
				numColsA, new Random(42L), MATRIX_A_PATH, true);
		DistributedRowMatrix.createRandomDistributedRowMatrix(conf, numRowsB,
				numColsB, new Random(1337L), MATRIX_B_PATH, false);

		// Load DistributedRowMatrix a and b
		DistributedRowMatrix a = new DistributedRowMatrix(MATRIX_A_PATH,
				OUTPUT_DIR, numRowsA, numColsA);
		a.setConf(conf);

		DistributedRowMatrix b = new DistributedRowMatrix(MATRIX_B_PATH,
				OUTPUT_DIR, numRowsB, numColsB);
		b.setConf(conf);

		// MatrixMultiply all within a new MapReduce job
		long startTime = System.currentTimeMillis();
		DistributedRowMatrix c = a.multiplyMapReduce(b, MATRIX_C_PATH, false,
				true);
		System.out.println("MatrixMultiplicationCpu using Hadoop finished in "
				+ (System.currentTimeMillis() - startTime) / 1000.0
				+ " seconds");

		if (isDebugging) {
			System.out.println("Matrix A:");
			a.printDistributedRowMatrix();
			System.out.println("Matrix B:");
			b.printDistributedRowMatrix();
			System.out.println("Matrix C:");
			c.printDistributedRowMatrix();

			printOutput(conf);
		}
	}
}
