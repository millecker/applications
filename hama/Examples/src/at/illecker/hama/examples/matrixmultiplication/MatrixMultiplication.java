package at.illecker.hama.examples.matrixmultiplication;

import java.io.IOException;
import java.util.Random;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hama.HamaConfiguration;
import org.apache.hama.bsp.BSP;
import org.apache.hama.bsp.BSPJob;
import org.apache.hama.bsp.BSPPeer;
import org.apache.hama.bsp.Partitioner;
import org.apache.hama.bsp.SequenceFileInputFormat;
import org.apache.hama.bsp.SequenceFileOutputFormat;
import org.apache.hama.bsp.message.MessageManager;
import org.apache.hama.bsp.sync.SyncException;

import de.jungblut.bsp.MatrixMultiplicationBSP;
import de.jungblut.math.dense.DenseDoubleMatrix;
import de.jungblut.math.dense.DenseDoubleVector;
import de.jungblut.writable.VectorWritable;

public class MatrixMultiplication
		extends
		BSP<IntWritable, VectorWritable, IntWritable, VectorWritable, MatrixRowMessage> {

	protected static final Log LOG = LogFactory
			.getLog(MatrixMultiplication.class);
	private static final String HAMA_MAT_MULT_B_PATH = "hama.mat.mult.B.path";

	private SequenceFile.Reader reader;
	private String masterTask;

	@Override
	public void setup(
			BSPPeer<IntWritable, VectorWritable, IntWritable, VectorWritable, MatrixRowMessage> peer)
			throws IOException, SyncException, InterruptedException {

		// Choose one as a master, who sorts the matrix rows at the end
		this.masterTask = peer.getPeerName(peer.getNumPeers() / 2);

		reopenMatrixB(peer.getConfiguration());
	}

	@Override
	public void bsp(
			BSPPeer<IntWritable, VectorWritable, IntWritable, VectorWritable, MatrixRowMessage> peer)
			throws IOException, SyncException, InterruptedException {

		IntWritable rowKey = new IntWritable();
		VectorWritable value = new VectorWritable();
		// while for each row of matrixA
		while (peer.readNext(rowKey, value)) {
			// System.out.println(peer.getPeerName() + " " + rowKey.get() + "|"
			// + value.toString());

			IntWritable bMatrixKey = new IntWritable();
			VectorWritable columnVector = new VectorWritable();
			VectorWritable colValues = null;

			// while for each col of matrixB
			while (reader.next(bMatrixKey, columnVector)) {

				if (colValues == null)
					colValues = new VectorWritable(new DenseDoubleVector(
							columnVector.getVector().getDimension()));

				double dot = value.getVector().dot(columnVector.getVector());

				colValues.getVector().set(bMatrixKey.get(), dot);

			}
			// 1) send calculated row to corresponding task
			// peer.send(peer.getPeerName(rowKey.get() % peer.getNumPeers()),
			// new MatrixRowMessage(rowKey.get(), colValues));

			// 2) write out row directly
			// peer.write(new IntWritable(rowKey.get()), colValues);

			// 3) send to master who sorts rows
			peer.send(masterTask, new MatrixRowMessage(rowKey.get(), colValues));

			reopenMatrixB(peer.getConfiguration());
		}
		reader.close();
		
		peer.sync();

	}

	@Override
	public void cleanup(
			BSPPeer<IntWritable, VectorWritable, IntWritable, VectorWritable, MatrixRowMessage> peer)
			throws IOException {

		if (peer.getPeerName().equals(masterTask)) {
			MatrixRowMessage currentMatrixRowMessage = null;
			while ((currentMatrixRowMessage = peer.getCurrentMessage()) != null) { //
				// System.out.println("WRITE OUT ROW: "
				// + currentMatrixRowMessage.getRowIndex());

				peer.write(
						new IntWritable(currentMatrixRowMessage.getRowIndex()),
						currentMatrixRowMessage.getColValues());
			}
		}
	}

	public void reopenMatrixB(Configuration conf) throws IOException {
		if (reader != null) {
			reader.close();
		}
		reader = new SequenceFile.Reader(FileSystem.get(conf), new Path(
				conf.get(HAMA_MAT_MULT_B_PATH)), conf);
	}

	public static void main(String[] args) throws IOException,
			InterruptedException, ClassNotFoundException {

		int n = 200;
		int m = 200;
		if (args.length > 0) {
			n = Integer.parseInt(args[0]);
			m = n;
		}
		
		System.out.println("DenseDoubleMatrix Size: " + n + "x" + m);

		// only needed for writing input matrix to hdfs
		HamaConfiguration conf = new HamaConfiguration();

		// use constant seeds to get reproducable results
		DenseDoubleMatrix a = new DenseDoubleMatrix(n, m, new Random(42L));
		DenseDoubleMatrix b = new DenseDoubleMatrix(n, m, new Random(1337L));

		Path aPath = new Path("input/hama/examples/MatrixA.seq");
		MatrixMultiplicationBSP.writeSequenceFileMatrix(conf, a, aPath, false);

		Path bPath = new Path("input/hama/examples/MatrixB.seq");
		// store this in column major format
		MatrixMultiplicationBSP.writeSequenceFileMatrix(conf, b, bPath, true);

		conf.set(HAMA_MAT_MULT_B_PATH, bPath.toString());

		Path outPath = new Path("output/hama/examples/matrixmult");

		conf.set(MessageManager.QUEUE_TYPE_CLASS,
				"org.apache.hama.bsp.message.SortedMessageQueue");
		// conf.set("bsp.local.tasks.maximum", "10");

		// set Job Config
		BSPJob job = new BSPJob(conf);

		// job.setNumBspTask(4);
		// LOG.info("DEBUG: NumBspTask: " + job.getNumBspTask());
		LOG.info("DEBUG: bsp.job.split.file: "
				+ job.get("bsp.job.split.file"));
		LOG.info("DEBUG: bsp.peers.num: " + job.get("bsp.peers.num"));
		LOG.info("DEBUG: bsp.tasks.maximum: "
				+ job.get("bsp.tasks.maximum"));

		job.setInputFormat(SequenceFileInputFormat.class);
		job.setInputPath(aPath);
		LOG.info("DEBUG: bsp.input.dir: "
				+ job.get("bsp.input.dir"));

		job.setOutputKeyClass(IntWritable.class);
		job.setOutputValueClass(VectorWritable.class);
		job.setOutputFormat(SequenceFileOutputFormat.class);
		job.setOutputPath(outPath);
		LOG.info("DEBUG: bsp.output.dir: "
				+ job.get("bsp.output.dir"));
		
		job.setBspClass(MatrixMultiplication.class);
		job.setJarByClass(MatrixMultiplication.class);
		job.setPartitioner(MatrixRowPartitioner.class);

		long startTime = System.currentTimeMillis();
		job.waitForCompletion(true);
		System.out.println("Job Finished in "
				+ (System.currentTimeMillis() - startTime) / 1000.0
				+ " seconds");

		// Get output Matrix and calculate Error
		DenseDoubleMatrix outputMatrix = new DenseDoubleMatrix(a.getRowCount(),
				b.getColumnCount());

		FileSystem fs = FileSystem.get(conf);
		FileStatus[] stati = fs.listStatus(outPath);

		for (FileStatus status : stati) {
			if (!status.isDir() && !status.getPath().getName().endsWith(".crc")) {
				Path path = status.getPath();

				// READ Output file
				SequenceFile.Reader reader = null;
				try {

					reader = new SequenceFile.Reader(fs, path, conf);
					IntWritable key = new IntWritable();
					VectorWritable value = new VectorWritable();

					// read row by row
					while (reader.next(key, value)) {
						// System.out.println(key.get() + "|"
						// + value.toString());
						//System.out.println("ReadFile with RowIndex:"
						//		+ key.get());

						outputMatrix.setRowVector(key.get(), value.getVector());
					}

				} catch (IOException e) {
					e.printStackTrace();
				} finally {
					if (reader != null) {
						try {
							reader.close();
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
				}
			}
		}

		double error = DenseDoubleMatrix.error(outputMatrix,
				(DenseDoubleMatrix) a.multiply(b));
		System.out.println(n + "x" + m + " Matrix absolute error is " + error);
	}

	private static final class MatrixRowPartitioner implements
			Partitioner<IntWritable, VectorWritable> {

		@Override
		public final int getPartition(IntWritable key, VectorWritable value,
				int numTasks) {
			return key.get() % numTasks;
		}
	}
}