package at.illecker.hama.examples.rootbeer;

import java.io.IOException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hama.HamaConfiguration;
import org.apache.hama.bsp.BSPJob;
import org.apache.hama.bsp.BSPJobClient;
import org.apache.hama.bsp.BSPPeer;
import org.apache.hama.bsp.ClusterStatus;
import org.apache.hama.bsp.FileOutputFormat;
import org.apache.hama.bsp.NullInputFormat;
import org.apache.hama.bsp.TextOutputFormat;
import org.apache.hama.bsp.gpu.GpuBSP;
import org.apache.hama.bsp.message.type.IntegerMessage;
import org.apache.hama.bsp.sync.SyncException;

public class HelloRootbeer extends
		GpuBSP<NullWritable, NullWritable, IntWritable, Text, IntegerMessage> {

	public static final Log LOG = LogFactory.getLog(HelloRootbeer.class);
	public static final int NUM_SUPERSTEPS = 15;
	private static Path TMP_OUTPUT = new Path(
			"output/hama/examples/HelloRootbeer-" + System.currentTimeMillis());

	@Override
	public void bspGPU(
			BSPPeer<NullWritable, NullWritable, IntWritable, Text, IntegerMessage> peer) {
		try {

			for (int i = 0; i < NUM_SUPERSTEPS; i++) {
				for (String otherPeer : peer.getAllPeerNames()) {
					peer.send(otherPeer, new IntegerMessage(peer.getPeerName(),
							i));
				}

				peer.sync();

				IntegerMessage msg = null;
				while ((msg = (IntegerMessage) peer.getCurrentMessage()) != null) {
					peer.write(new IntWritable(msg.getData()),
							new Text(msg.getTag()));
				}
			}

		} catch (IOException e) {
			e.printStackTrace();
		} catch (InterruptedException e) {
			e.printStackTrace();
		} catch (SyncException e) {
			e.printStackTrace();
		}
	}

	public static void main(String[] args) throws InterruptedException,
			IOException, ClassNotFoundException {

		// BSP job configuration
		HamaConfiguration conf = new HamaConfiguration();

		BSPJob job = new BSPJob(conf);
		// Set the job name
		job.setJobName("HelloRootbeer Example");
		// set the BSP class which shall be executed
		job.setBspClass(HelloRootbeer.class);
		// help Hama to locale the jar to be distributed
		job.setJarByClass(HelloRootbeer.class);

		job.setInputFormat(NullInputFormat.class);

		job.setOutputKeyClass(IntWritable.class);
		job.setOutputValueClass(Text.class);
		job.setOutputFormat(TextOutputFormat.class);
		FileOutputFormat.setOutputPath(job, TMP_OUTPUT);
		// job.setOutputPath(new Path("output/hama/examples"));

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

}
