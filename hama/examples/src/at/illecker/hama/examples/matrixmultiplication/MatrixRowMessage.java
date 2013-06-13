package at.illecker.hama.examples.matrixmultiplication;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import org.apache.hadoop.io.WritableComparable;

import com.google.common.collect.ComparisonChain;

import de.jungblut.writable.VectorWritable;

public class MatrixRowMessage implements WritableComparable<MatrixRowMessage> {
	private int rowIndex;
	private VectorWritable colValues = null;

	public MatrixRowMessage() {
	}

	public MatrixRowMessage(int rowIndex, VectorWritable colValues) {
		super();
		this.rowIndex = rowIndex;
		this.colValues = colValues;
	}

	public int getRowIndex() {
		return rowIndex;
	}

	public VectorWritable getColValues() {
		return colValues;
	}

	@Override
	public void readFields(DataInput in) throws IOException {
		rowIndex = in.readInt();
		colValues = new VectorWritable(VectorWritable.readVector(in));
	}

	@Override
	public void write(DataOutput out) throws IOException {
		out.writeInt(rowIndex);
		VectorWritable.writeVector(colValues.getVector(), out);
	}

	@Override
	public int compareTo(MatrixRowMessage o) {
		return ComparisonChain.start().compare(rowIndex, o.rowIndex).result();
	}

}
