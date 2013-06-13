package at.illecker.hama.rootbeer.examples.hellorootbeer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
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

public class HelloRootbeerGpuBSP extends
		BSP<NullWritable, NullWritable, Text, DoubleWritable, DoubleWritable> {
	public static final Log LOG = LogFactory.getLog(HelloRootbeerGpuBSP.class);
	private static long kernelCount = 100;
	private static long iterations = 1000;
	private String m_masterTask;
	private int m_kernelCount;
	private long m_iterations;
	private List<Kernel> kernels = new ArrayList<Kernel>();

	@Override
	public void bsp(
			BSPPeer<NullWritable, NullWritable, Text, DoubleWritable, DoubleWritable> peer)
			throws IOException, SyncException, InterruptedException {

		Stopwatch watch = new Stopwatch();
		watch.start();
		Rootbeer rootbeer = new Rootbeer();
		// rootbeer.setThreadConfig(m_blockSize, m_gridSize);
		rootbeer.runAll(kernels);
		watch.stop();

		// Write log to dfs
		BSPJob job = new BSPJob((HamaConfiguration) peer.getConfiguration());
		FileSystem fs = FileSystem.get(peer.getConfiguration());
		FSDataOutputStream outStream = fs.create(new Path(FileOutputFormat
				.getOutputPath(job), peer.getTaskId() + ".log"));

		outStream.writeUTF("KernelCount: " + m_kernelCount + "\n");
		outStream.writeUTF("Iterations: " + m_iterations + "\n");
		outStream.writeUTF("gpu time: " + watch.elapsedTimeMillis() + " ms\n");
		List<StatsRow> stats = rootbeer.getStats();
		for (StatsRow row : stats) {
			outStream.writeUTF("  StatsRow:\n");
			outStream.writeUTF("    init time: " + row.getInitTime() + "\n");
			outStream.writeUTF("    serial time: " + row.getSerializationTime()
					+ "\n");
			outStream.writeUTF("    exec time: " + row.getExecutionTime()
					+ "\n");
			outStream.writeUTF("    deserial time: "
					+ row.getDeserializationTime() + "\n");
			outStream.writeUTF("    num blocks: " + row.getNumBlocks() + "\n");
			outStream
					.writeUTF("    num threads: " + row.getNumThreads() + "\n");
		}
		outStream.close();

		// Send result to MasterTask
		for (int i = 0; i < m_kernelCount; i++) {
			peer.send(m_masterTask, new DoubleWritable(
					((HelloRootbeerKernel) kernels.get(i)).result));
		}
		peer.sync();
	}

	@Override
	public void setup(
			BSPPeer<NullWritable, NullWritable, Text, DoubleWritable, DoubleWritable> peer)
			throws IOException {

		this.m_kernelCount = Integer.parseInt(peer.getConfiguration().get(
				"hellorootbeer.kernelCount"));
		this.m_iterations = Long.parseLong(peer.getConfiguration().get(
				"hellorootbeer.iterations"));
		// Choose one as a master
		this.m_masterTask = peer.getPeerName(peer.getNumPeers() / 2);

		for (int i = 0; i < m_kernelCount; i++) {
			kernels.add(new HelloRootbeerKernel(m_iterations));
		}
	}

	@Override
	public void cleanup(
			BSPPeer<NullWritable, NullWritable, Text, DoubleWritable, DoubleWritable> peer)
			throws IOException {

		if (peer.getPeerName().equals(m_masterTask)) {

			double sum = 0.0;

			DoubleWritable received;
			while ((received = peer.getCurrentMessage()) != null) {
				sum += received.get();
			}

			peer.write(new Text("Result"), new DoubleWritable(sum));
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

	public static void main(String[] args) throws InterruptedException,
			IOException, ClassNotFoundException {
		// BSP job configuration
		HamaConfiguration conf = new HamaConfiguration();

		BSPJob job = new BSPJob(conf);
		// Set the job name
		job.setJobName("HelloRootbeer GPU Example");
		// set the BSP class which shall be executed
		job.setBspClass(HelloRootbeerGpuBSP.class);
		// help Hama to locale the jar to be distributed
		job.setJarByClass(HelloRootbeerGpuBSP.class);

		job.setInputFormat(NullInputFormat.class);
		job.setOutputKeyClass(Text.class);
		job.setOutputValueClass(DoubleWritable.class);
		job.setOutputFormat(TextOutputFormat.class);
		// FileOutputFormat.setOutputPath(job, TMP_OUTPUT);
		job.setOutputPath(new Path("output/hama/rootbeer/examples/hellorootbeer"));

		job.set("bsp.child.java.opts", "-Xmx4G");

		BSPJobClient jobClient = new BSPJobClient(conf);
		ClusterStatus cluster = jobClient.getClusterStatus(true);

		if (args.length > 0) {
			if (args.length == 3) {
				job.setNumBspTask(Integer.parseInt(args[0]));
				job.set("hellorootbeer.kernelCount", args[1]);
				job.set("hellorootbeer.iterations", args[2]);
			} else {
				System.out.println("Wrong argument size!");
				System.out.println("    Argument1=NumBspTask");
				System.out.println("    Argument2=hellorootbeer.kernelCount");
				System.out.println("    Argument2=hellorootbeer.iterations");
				return;
			}
		} else {
			// Set to maximum
			job.setNumBspTask(cluster.getMaxTasks());
			job.set("hellorootbeer.kernelCount", ""
					+ HelloRootbeerGpuBSP.kernelCount);
			job.set("hellorootbeer.iterations", ""
					+ HelloRootbeerGpuBSP.iterations);
		}
		LOG.info("NumBspTask: " + job.getNumBspTask());
		LOG.info("KernelCount: " + job.get("hellorootbeer.kernelCount"));
		LOG.info("Iterations: " + job.get("hellorootbeer.iterations"));

		long startTime = System.currentTimeMillis();
		if (job.waitForCompletion(true)) {
			printOutput(job);
			System.out.println("Job Finished in "
					+ (System.currentTimeMillis() - startTime) / 1000.0
					+ " seconds");
		}
	}
}
