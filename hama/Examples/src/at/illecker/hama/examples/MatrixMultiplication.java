package at.illecker.hama.examples;

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
import org.apache.hama.bsp.Partitioner;
import org.apache.hama.pipes.Submitter;

import de.jungblut.math.dense.DenseDoubleMatrix;
import de.jungblut.writable.VectorWritable;

public class MatrixMultiplication {

	protected static final Log LOG = LogFactory
			.getLog(MatrixMultiplication.class);
	private static final String HAMA_MAT_MULT_B_PATH = "hama.mat.mult.B.path";

	private SequenceFile.Reader reader;

	public static void main(String[] args) throws IOException,
			InterruptedException, ClassNotFoundException {

		// only needed for writing input matrix to hdfs
		HamaConfiguration conf = new HamaConfiguration();

		for (int n = 200; n < 300; n++) {
			System.out.println(n + "x" + n);
			// use constant seeds to get reproducable results
			DenseDoubleMatrix a = new DenseDoubleMatrix(n, n, new Random(42L));
			DenseDoubleMatrix b = new DenseDoubleMatrix(n, n, new Random(1337L));

			Path inPath = new Path("files/matrixmult/in/A.seq");
			writeSequenceFileMatrix(conf, a, inPath, false);
			Path bPath = new Path("files/matrixmult/in/B.seq");
			// store this in column major format
			writeSequenceFileMatrix(conf, b, bPath, true);

			conf.set(HAMA_MAT_MULT_B_PATH, bPath.toString());
			Path outPath = new Path("files/matrixmult/out/");

			/* TODO */
			// job.setPartitioner(MatrixRowPartitioner.class);

			String[] arr = {
					"-conf",
					"/home/bafu/workspace/applications/bsp/pipes/MatrixMultiplication/MatrixMultiplication_job.xml",
					"-input", inPath.toString(), "-output", outPath.toString() };

			try {
				Submitter.main(arr);

			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

			DenseDoubleMatrix outputMatrix = new DenseDoubleMatrix(
					a.getRowCount(), b.getColumnCount());

			FileSystem fs = FileSystem.get(conf);
			FileStatus[] stati = fs.listStatus(outPath);
			for (FileStatus status : stati) {
				if (!status.isDir()
						&& !status.getPath().getName().endsWith(".crc")) {
					Path path = status.getPath();
					// Java 6 modifications begin
					SequenceFile.Reader reader = null;
					try {
						reader = new SequenceFile.Reader(fs, path, conf);
						IntWritable key = new IntWritable();
						VectorWritable value = new VectorWritable();
						while (reader.next(key, value)) {
							outputMatrix.setRowVector(key.get(),
									value.getVector());
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
					// Java 6 modifications end
				}
			}

			double error = DenseDoubleMatrix.error(outputMatrix,
					(DenseDoubleMatrix) a.multiply(b));
			System.out.println(n + "x" + n + " Matrix absolute error is "
					+ error);
		}
	}

	private static void writeSequenceFileMatrix(Configuration conf,
			DenseDoubleMatrix denseDoubleMatrix, Path p, boolean columnMajor) {
		SequenceFile.Writer writer = null;
		try {
			FileSystem fs = FileSystem.get(conf);
			writer = new SequenceFile.Writer(fs, conf, p, IntWritable.class,
					VectorWritable.class);
			if (!columnMajor) {
				for (int i = 0; i < denseDoubleMatrix.getRowCount(); i++) {
					VectorWritable vectorWritable = new VectorWritable(
							denseDoubleMatrix.getRowVector(i));
					writer.append(new IntWritable(i), vectorWritable);
				}
			} else {
				for (int i = 0; i < denseDoubleMatrix.getColumnCount(); i++) {
					VectorWritable vectorWritable = new VectorWritable(
							denseDoubleMatrix.getColumnVector(i));
					writer.append(new IntWritable(i), vectorWritable);
				}
			}

		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			if (writer != null) {
				try {
					writer.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}
	

	/* TODO */
	private static final class MatrixRowPartitioner implements
			Partitioner<IntWritable, VectorWritable> {

		@Override
		public final int getPartition(IntWritable key, VectorWritable value,
				int numTasks) {
			return key.get() % (numTasks - 1);
		}
	}

	public void reopenOtherMatrix(Configuration conf) throws IOException {
		if (reader != null) {
			reader.close();
		}
		reader = new SequenceFile.Reader(FileSystem.get(conf), new Path(
				conf.get(HAMA_MAT_MULT_B_PATH)), conf);
	}

}