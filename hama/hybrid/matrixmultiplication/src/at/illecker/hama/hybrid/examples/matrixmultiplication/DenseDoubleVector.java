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
package at.illecker.hama.hybrid.examples.matrixmultiplication;


public class DenseDoubleVector {
  double m_vector[];

  public DenseDoubleVector(int length) {
    m_vector = new double[length];
  }

  public DenseDoubleVector(int length, double val) {
    this(length);
    for (int i = 0; i < length; i++) {
      m_vector[i] = val;
    }
  }

  public DenseDoubleVector(String values) {
    String[] vals = values.split(",");
    m_vector = new double[vals.length];
    for (int i = 0; i < vals.length; i++) {
      m_vector[i] = Double.parseDouble(vals[i]);
    }
  }

  public int getLength() {
    return m_vector.length;
  }

  public void set(int index, double value) {
    m_vector[index] = value;
  }

  public double get(int index) {
    return m_vector[index];
  }

  public double dot(DenseDoubleVector otherVector) {
    double dotProduct = 0.0;
    for (int i = 0; i < m_vector.length; i++) {
      dotProduct += m_vector[i] * otherVector.get(i);
    }
    return dotProduct;
  }

  @Override
  public String toString() {
    String str = "";
    String delimiter = ",";
    for (int i = 0; i < m_vector.length; i++) {
      if (i == 0) {
        str += "" + m_vector[i];
      } else {
        str += delimiter + " " + m_vector[i];
      }
    }
    return str;
  }
}
