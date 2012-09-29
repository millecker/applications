package at.illecker.hama.examples;

import java.io.IOException;
import java.util.Random;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hama.HamaConfiguration;
import org.apache.hama.bsp.BSPJob;
import org.apache.hama.bsp.SequenceFileInputFormat;
import org.apache.hama.bsp.SequenceFileOutputFormat;
import org.apache.hama.bsp.message.MessageManager;

import de.jungblut.bsp.MatrixMultiplicationBSP;
import de.jungblut.bsp.MatrixMultiplicationBSP.MatrixRowPartitioner;
import de.jungblut.math.dense.DenseDoubleMatrix;
import de.jungblut.writable.VectorWritable;

public class MatrixMultiplication {

	protected static final Log LOG = LogFactory
			.getLog(MatrixMultiplication.class);
	private static final String HAMA_MAT_MULT_B_PATH = "hama.mat.mult.B.path";

	public static void main(String[] args) throws IOException,
			InterruptedException, ClassNotFoundException {

		int n = 200;
		if (args.length > 0) {
			n = Integer.parseInt(args[0]);
		}
		System.out.println("DenseDoubleMatrix Size: " + n + "x" + n);

		// only needed for writing input matrix to hdfs
		HamaConfiguration conf = new HamaConfiguration();

		// use constant seeds to get reproducable results
		DenseDoubleMatrix a = new DenseDoubleMatrix(n, n, new Random(42L));
		DenseDoubleMatrix b = new DenseDoubleMatrix(n, n, new Random(1337L));

		Path aPath = new Path("input/MatrixA.seq");
		MatrixMultiplicationBSP.writeSequenceFileMatrix(conf, a, aPath, false);

		Path bPath = new Path("input/MatrixB.seq");
		// store this in column major format
		MatrixMultiplicationBSP.writeSequenceFileMatrix(conf, b, bPath, true);

		conf.set(HAMA_MAT_MULT_B_PATH, bPath.toString());
		Path outPath = new Path("output/matrixmult");

		conf.set(MessageManager.QUEUE_TYPE_CLASS,
				"org.apache.hama.bsp.message.SortedMessageQueue");
		conf.set("bsp.local.tasks.maximum", "2");
		
		// set Job Config
		BSPJob job = new BSPJob(conf);
		job.setInputFormat(SequenceFileInputFormat.class);
		job.setInputPath(aPath);

		job.setOutputKeyClass(IntWritable.class);
		job.setOutputValueClass(VectorWritable.class);

		job.setOutputFormat(SequenceFileOutputFormat.class);
		job.setOutputPath(outPath);

		job.setBspClass(MatrixMultiplicationBSP.class);
		job.setJarByClass(MatrixMultiplicationBSP.class);
		/* TODO */
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
		System.out.println(n + "x" + n + " Matrix absolute error is " + error);
	}

}