package at.illecker.hadoop.rootbeer.examples.matrixmultiplication.gpu;

public class Multiplier {

	public int index;
	public double value;

	public Multiplier(int index, double value) {
		this.index = index;
		this.value = value;
	}

	@Override
	public String toString() {
		StringBuilder ret = new StringBuilder();

		ret.append("  Index: ");
		ret.append(index);
		ret.append("\n");

		ret.append("  Value: ");
		ret.append(value);
		ret.append("\n");

		return ret.toString();

	}

	public static void main(String[] args) {
		// Dummy constructor invocation
		// to keep constructor in
		// rootbeer transformation
		new Multiplier(0, 0);
	}
}
