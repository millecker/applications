/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package at.illecker.hama.hybrid.examples.matrixmultiplication2;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import org.apache.hadoop.io.WritableComparable;
import org.apache.hama.commons.io.VectorWritable;
import org.apache.hama.commons.math.DoubleVector;

public class MatrixRowMessage implements WritableComparable<MatrixRowMessage> {
  private int rowIndex;
  private DoubleVector rowValues = null;

  public MatrixRowMessage() {
    super();
  }

  public MatrixRowMessage(int rowIndex, DoubleVector rowValues) {
    this.rowIndex = rowIndex;
    this.rowValues = rowValues;
  }

  public int getRowIndex() {
    return rowIndex;
  }

  public DoubleVector getRowValues() {
    return rowValues;
  }

  @Override
  public void readFields(DataInput in) throws IOException {
    rowIndex = in.readInt();
    rowValues = VectorWritable.readVector(in);
  }

  @Override
  public void write(DataOutput out) throws IOException {
    out.writeInt(rowIndex);
    VectorWritable.writeVector(rowValues, out);
  }

  @Override
  public int compareTo(MatrixRowMessage o) {
    return ((rowIndex < o.rowIndex) ? -1 : (rowIndex == o.rowIndex ? 0 : 1));
  }
}
