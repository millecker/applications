package at.illecker.hadoop.rootbeer.examples.matrixmultiplication;

import java.util.Random;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.util.ToolRunner;
import org.apache.mahout.common.AbstractJob;

import com.google.caliper.Param;
import com.google.caliper.Runner;
import com.google.caliper.SimpleBenchmark;

public class MatrixMultiplicationBenchmark extends SimpleBenchmark {

	private static final Path OUTPUT_DIR = new Path(
			"output/hadoop/rootbeer/examples/matrixmultiplication/bench");
	private static final Path MATRIX_A_PATH = new Path(OUTPUT_DIR
			+ "/MatrixA.seq");
	private static final Path MATRIX_B_PATH = new Path(OUTPUT_DIR
			+ "/MatrixB.seq");
	private static final Path MATRIX_C_PATH = new Path(OUTPUT_DIR
			+ "/MatrixC.seq");

	@Param({ "2", "4",})
		// "5", "10", "20", "40", "60", "80", "100", "500", "1000", "2000" })
	private int n;

	@Param
	CalcType type;

	public enum CalcType {
		JAVA, HADOOP_CPU_TRANSPOSE_JAVA, HADOOP_CPU_TRANSPOSE_MAP_REDUCE // ,
																			// HADOOP_GPU_TRANSPOSE_MAP_REDUCE
	};

	private Configuration conf;
	private DistributedRowMatrix matrixA;
	private DistributedRowMatrix matrixB;

	@Override
	protected void setUp() throws Exception {
		conf = new Configuration();
		// conf.set("tmpjars", "../../../lib/hadoop-core-1.3.0-SNAPSHOT.jar");

		// Create random DistributedRowMatrix
		// use constant seeds to get reproducable results
		DistributedRowMatrix.createRandomDistributedRowMatrix(conf, n, n,
				new Random(42L), MATRIX_A_PATH);
		DistributedRowMatrix.createRandomDistributedRowMatrix(conf, n, n,
				new Random(1337L), MATRIX_B_PATH);

		// Load DistributedRowMatrix a and b
		matrixA = new DistributedRowMatrix(MATRIX_A_PATH, OUTPUT_DIR, n, n);
		matrixB = new DistributedRowMatrix(MATRIX_B_PATH, OUTPUT_DIR, n, n);

		matrixA.setConf(conf);
		matrixB.setConf(conf);
	}

	public void timeCalculate(int reps) {
		for (int rep = 0; rep < reps; rep++) {
			int sum = 0;
			switch (type) {
			case JAVA:
				sum = matrixMultiplyJava(sum);
				break;
			case HADOOP_CPU_TRANSPOSE_JAVA:
				sum = matrixMultiplyHadoopCPUTransposeJava(sum);
				break;
			case HADOOP_CPU_TRANSPOSE_MAP_REDUCE:
				sum = matrixMultiplyHadoopCPUTransposeMR(sum);
				break;
			/*
			 * case HADOOP_GPU_TRANSPOSE_MAP_REDUCE: sum =
			 * matrixMultiplyHadopGPUTransposeMR(sum); break;
			 */
			default:
				break;
			}
			System.out.println(sum);
		}

	}

	private class MatrixMultiplicationCPU extends AbstractJob {
		private boolean submitMatrixMultiplyJob;
		private boolean submitTransposeJob;

		public MatrixMultiplicationCPU(boolean submitMatrixMultiplyJob,
				boolean submitTransposeJob) {
			this.submitMatrixMultiplyJob = submitMatrixMultiplyJob;
			this.submitTransposeJob = submitTransposeJob;
		}

		@Override
		public int run(String[] arg0) throws Exception {
			DistributedRowMatrix resultMatrixHadoopCPU = matrixA.times(matrixB,
					new Path(MATRIX_C_PATH
							+ ((submitMatrixMultiplyJob) ? "_matrixMultiplyMR"
									: "_transposeJava")
							+ ((submitTransposeJob) ? "_transposeMR"
									: "_transposeJava") + ".seq"),
					submitMatrixMultiplyJob, submitTransposeJob);

			return resultMatrixHadoopCPU.numRows();
		}
	}

	private int matrixMultiplyJava(int sum) {
		try {
			sum += ToolRunner.run(new MatrixMultiplicationCPU(false, false),
					null);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return sum;
	}

	private int matrixMultiplyHadoopCPUTransposeJava(int sum) {
		try {
			sum += ToolRunner.run(new MatrixMultiplicationCPU(true, false),
					null);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return sum;
	}

	private int matrixMultiplyHadoopCPUTransposeMR(int sum) {
		try {
			sum += ToolRunner
					.run(new MatrixMultiplicationCPU(true, true), null);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return sum;
	}

	/*
	 * private int matrixMultiplyHadopGPUTransposeMR(int sum) {
	 * 
	 * return 0; }
	 */

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		Runner.main(MatrixMultiplicationBenchmark.class, args);
	}

}