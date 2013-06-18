package at.illecker.hadoop.rootbeer.examples.matrixmultiplication.gpu;

public class Result {

	public int row;
	public double values[];

	public Result(int row, double[] values) {
		this.row = row;
		this.values = values;
	}

	@Override
	public String toString() {
		StringBuilder ret = new StringBuilder();

		ret.append("  Row: ");
		ret.append(row);
		ret.append("\n");

		ret.append("  Values: ");
		for (int i = 0; i < values.length; i++) {
			ret.append(values[i] + " ");
		}
		ret.append("\n");

		return ret.toString();
	}

	public static void main(String[] args) {
		// Dummy constructor invocation
		// to keep constructor in
		// rootbeer transformation
		new Result(0, null);
	}
}
