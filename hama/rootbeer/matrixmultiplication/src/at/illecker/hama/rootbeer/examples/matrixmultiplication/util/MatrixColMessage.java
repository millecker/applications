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
package at.illecker.hama.rootbeer.examples.matrixmultiplication.util;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import org.apache.hadoop.io.WritableComparable;
import org.apache.mahout.math.VectorWritable;

import com.google.common.collect.ComparisonChain;

public class MatrixColMessage implements WritableComparable<MatrixColMessage> {
  private int colIndex;
  private VectorWritable colValues = null;

  public MatrixColMessage() {
  }

  public MatrixColMessage(int colIndex, VectorWritable colValues) {
    super();
    this.colIndex = colIndex;
    this.colValues = colValues;
  }

  public int getColIndex() {
    return colIndex;
  }

  public VectorWritable getColValues() {
    return colValues;
  }

  @Override
  public void readFields(DataInput in) throws IOException {
    colIndex = in.readInt();
    colValues = new VectorWritable(VectorWritable.readVector(in));
  }

  @Override
  public void write(DataOutput out) throws IOException {
    out.writeInt(colIndex);
    VectorWritable.writeVector(out, colValues.get());
  }

  @Override
  public int compareTo(MatrixColMessage o) {
    return ComparisonChain.start().compare(colIndex, o.colIndex).result();
  }

}
