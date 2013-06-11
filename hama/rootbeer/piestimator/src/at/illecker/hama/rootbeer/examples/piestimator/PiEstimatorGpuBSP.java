package at.illecker.hama.rootbeer.examples.piestimator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hama.HamaConfiguration;
import org.apache.hama.bsp.BSP;
import org.apache.hama.bsp.BSPJob;
import org.apache.hama.bsp.BSPJobClient;
import org.apache.hama.bsp.BSPPeer;
import org.apache.hama.bsp.ClusterStatus;
import org.apache.hama.bsp.FileOutputFormat;
import org.apache.hama.bsp.NullInputFormat;
import org.apache.hama.bsp.TextOutputFormat;
import org.apache.hama.bsp.sync.SyncException;

import edu.syr.pcpratts.rootbeer.runtime.Kernel;
import edu.syr.pcpratts.rootbeer.runtime.Rootbeer;
import edu.syr.pcpratts.rootbeer.runtime.StatsRow;
import edu.syr.pcpratts.rootbeer.runtime.util.Stopwatch;

/**
 * @author PiEstimator Monte Carlo computation of pi
 *         http://de.wikipedia.org/wiki/Monte-Carlo-Algorithmus
 * 
 *         Generate random points in the square [-1,1] X [-1,1]. The fraction of
 *         these that lie in the unit disk x^2 + y^2 <= 1 will be approximately
 *         pi/4.
 */

public class PiEstimatorGpuBSP extends
		BSP<NullWritable, NullWritable, Text, DoubleWritable, DoubleWritable> {
	public static final Log LOG = LogFactory.getLog(PiEstimatorGpuBSP.class);
	private String masterTask;
	private static final long m_iterations = 10000;
	private static final int m_kernelCount = 1000;

	@Override
	public void bsp(
			BSPPeer<NullWritable, NullWritable, Text, DoubleWritable, DoubleWritable> peer)
			throws IOException, SyncException, InterruptedException {

		Stopwatch watch = new Stopwatch();
		watch.start();

		List<Kernel> kernels = new ArrayList<Kernel>();
		for (int i = 0; i < m_kernelCount; i++) {
			kernels.add(new PiEstimatorKernel(m_iterations));
		}
		Rootbeer rootbeer = new Rootbeer();
		// rootbeer.setThreadConfig(m_blockSize, m_gridSize);
		rootbeer.runAll(kernels);

		watch.stop();
		System.out.println("gpu time: " + watch.elapsedTimeMillis() + " ms");
		List<StatsRow> stats = rootbeer.getStats();
		for (StatsRow row : stats) {
			System.out.println("  StatsRow:");
			System.out.println("    init time: " + row.getInitTime());
			System.out
					.println("    serial time: " + row.getSerializationTime());
			System.out.println("    exec time: " + row.getExecutionTime());
			System.out.println("    deserial time: "
					+ row.getDeserializationTime());
			System.out.println("    num blocks: " + row.getNumBlocks());
			System.out.println("    num threads: " + row.getNumThreads());
		}

		// Send result to MasterTask
		for (int i = 0; i < m_kernelCount; i++) {
			peer.send(masterTask, new DoubleWritable(
					((PiEstimatorKernel) kernels.get(i)).getResult()));
		}
		peer.sync();
	}

	@Override
	public void setup(
			BSPPeer<NullWritable, NullWritable, Text, DoubleWritable, DoubleWritable> peer)
			throws IOException {

		// Choose one as a master
		this.masterTask = peer.getPeerName(peer.getNumPeers() / 2);
	}

	@Override
	public void cleanup(
			BSPPeer<NullWritable, NullWritable, Text, DoubleWritable, DoubleWritable> peer)
			throws IOException {

		if (peer.getPeerName().equals(masterTask)) {

			double pi = 0.0;

			int numPeers = peer.getNumCurrentMessages();

			DoubleWritable received;
			while ((received = peer.getCurrentMessage()) != null) {
				pi += received.get();
			}

			pi = pi / numPeers;
			peer.write(new Text("Estimated value of PI(3,14159265) is"),
					new DoubleWritable(pi));
		}
	}

	static void printOutput(BSPJob job) throws IOException {
		FileSystem fs = FileSystem.get(job.getConfiguration());
		FileStatus[] files = fs.listStatus(FileOutputFormat.getOutputPath(job));
		for (int i = 0; i < files.length; i++) {
			if (files[i].getLen() > 0) {
				FSDataInputStream in = fs.open(files[i].getPath());
				IOUtils.copyBytes(in, System.out, job.getConfiguration(), false);
				in.close();
				break;
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
		job.setJobName("Rootbeer GPU PiEstimatior");
		// set the BSP class which shall be executed
		job.setBspClass(PiEstimatorGpuBSP.class);
		// help Hama to locale the jar to be distributed
		job.setJarByClass(PiEstimatorGpuBSP.class);

		job.setInputFormat(NullInputFormat.class);
		job.setOutputKeyClass(Text.class);
		job.setOutputValueClass(DoubleWritable.class);
		job.setOutputFormat(TextOutputFormat.class);
		// FileOutputFormat.setOutputPath(job, TMP_OUTPUT);
		job.setOutputPath(new Path("output/hama/examples"));

		BSPJobClient jobClient = new BSPJobClient(conf);
		ClusterStatus cluster = jobClient.getClusterStatus(true);

		if (args.length > 0) {
			job.setNumBspTask(Integer.parseInt(args[0]));
		} else {
			// Set to maximum
			job.setNumBspTask(cluster.getMaxTasks());
		}
		LOG.info("DEBUG: NumBspTask: " + job.getNumBspTask());

		long startTime = System.currentTimeMillis();
		if (job.waitForCompletion(true)) {
			printOutput(job);
			System.out.println("Job Finished in "
					+ (System.currentTimeMillis() - startTime) / 1000.0
					+ " seconds");
		}
	}

}
