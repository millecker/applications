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
		return "Multiplier [" + index + "," + value + "]";
	}

}
