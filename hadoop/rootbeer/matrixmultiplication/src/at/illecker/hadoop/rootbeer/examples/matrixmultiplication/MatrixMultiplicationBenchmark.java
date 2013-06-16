package at.illecker.hadoop.rootbeer.examples.matrixmultiplication;

import java.util.Random;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.util.ToolRunner;
import org.apache.mahout.common.AbstractJob;

import com.google.caliper.Benchmark;
import com.google.caliper.Param;
import com.google.caliper.api.Macrobenchmark;
import com.google.caliper.runner.CaliperMain;

public class MatrixMultiplicationBenchmark extends Benchmark {

	@Param({ "5", "10" })
	// , "20", "40", "45", "50", "55", })
	// "80", "100", "500", "1000", "2000" })
	private int n;

	@Param
	CalcType type;

	public enum CalcType {
		JAVA, HADOOP_CPU_TRANSPOSE_JAVA, HADOOP_CPU_TRANSPOSE_MAP_REDUCE
		// , HADOOP_GPU_TRANSPOSE_MAP_REDUCE
	};

	// private static final Log LOG =
	// LogFactory.getLog(MatrixMultiplicationBenchmark.class);
	private static final String OUTPUT_DIR = "output/hadoop/rootbeer/examples/matrixmultiplication/bench";

	private Path OUTPUT_DIR_PATH;
	private Path MATRIX_A_PATH;
	private Path MATRIX_B_PATH;
	private Path MATRIX_C_PATH;

	private Configuration conf;

	private DistributedRowMatrix matrixA;
	private DistributedRowMatrix matrixB;
	private boolean runLocally = false;

	@Override
	protected void setUp() throws Exception {
		conf = new Configuration();
		String HADOOP_HOME = System.getenv("HADOOP_HOME");
		String HADOOP_INSTALL = System.getenv("HADOOP_INSTALL");

		if ((HADOOP_HOME != null) || (HADOOP_INSTALL != null) && (!runLocally)) {
			System.out.println("Load Hadoop default configuration...");
			String HADOOP = ((HADOOP_HOME != null) ? HADOOP_HOME
					: HADOOP_INSTALL);

			System.out.println("HADOOP_HOME: " + HADOOP);
			conf.addResource(new Path(HADOOP, "src/core/core-default.xml"));
			conf.addResource(new Path(HADOOP, "src/hdfs/hdfs-default.xml"));
			conf.addResource(new Path(HADOOP, "src/mapred/mapred-default.xml"));
			conf.addResource(new Path(HADOOP, "conf/core-site.xml"));
			conf.addResource(new Path(HADOOP, "conf/hdfs-site.xml"));
			conf.addResource(new Path(HADOOP, "conf/mapred-site.xml"));

			try {
				FileSystem.get(conf);
			} catch (Exception e) {
				// HDFS not reachable run Benchmark locally
				conf = new Configuration();
				runLocally = true;
			}
		}

		// Setup outputs
		OUTPUT_DIR_PATH = new Path(OUTPUT_DIR + "/bench_"
				+ System.currentTimeMillis());
		System.out.println("OUTPUT_DIR_PATH: " + OUTPUT_DIR_PATH);

		MATRIX_A_PATH = new Path(OUTPUT_DIR_PATH + "/MatrixA.seq");
		MATRIX_B_PATH = new Path(OUTPUT_DIR_PATH + "/MatrixB.seq");
		MATRIX_C_PATH = new Path(OUTPUT_DIR_PATH + "/MatrixC.seq");

		// Create random DistributedRowMatrix
		// use constant seeds to get reproducable results
		DistributedRowMatrix.createRandomDistributedRowMatrix(conf, n, n,
				new Random(42L), MATRIX_A_PATH);
		DistributedRowMatrix.createRandomDistributedRowMatrix(conf, n, n,
				new Random(1337L), MATRIX_B_PATH);

		// Load DistributedRowMatrix a and b
		matrixA = new DistributedRowMatrix(MATRIX_A_PATH, OUTPUT_DIR_PATH, n, n);
		matrixB = new DistributedRowMatrix(MATRIX_B_PATH, OUTPUT_DIR_PATH, n, n);

		matrixA.setConf(conf);
		matrixB.setConf(conf);
	}

	@Override
	protected void tearDown() throws Exception {
		// System.out.println("Hey, I'm tearing up the joint.");
	}

	// Microbenchmark
	// Uncomment Macro to use Micro
	public void timeCalculate(int reps) {
		int sum = 0;
		for (int rep = 0; rep < reps; rep++) {
			sum = doBenchmark(sum);
		}
		System.out.println(sum);
	}

	@Macrobenchmark
	public void timeCalculate() {
		doBenchmark(0);
	}

	public int doBenchmark(int sum) {
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
		return sum;
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
									: "_transposeJava")),
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

	public static void main(String[] args) {
		CaliperMain.main(MatrixMultiplicationBenchmark.class, args);
	}

}