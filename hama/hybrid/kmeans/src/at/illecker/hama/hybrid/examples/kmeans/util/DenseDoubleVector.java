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
package at.illecker.hama.hybrid.examples.kmeans.util;

public class DenseDoubleVector {
  private double m_vector[];
  private int m_index;

  public DenseDoubleVector() {
    m_vector = new double[128];
    m_index = 0;
  }

  public DenseDoubleVector(int length) {
    m_vector = new double[length];
    m_index = 0;
  }

  public DenseDoubleVector(int length, double val) {
    this(length);
    for (int i = 0; i < length; i++) {
      m_vector[i] = val;
    }
    m_index = length;
  }

  public DenseDoubleVector(String values) {
    // System.out.print("DenseDoubleVector started: ");
    // System.out.println(values);
    String[] vals = values.split(",");
    if (vals != null) {
      // System.out.print("DenseDoubleVector length: ");
      // System.out.println(vals.length);
      m_vector = new double[vals.length];
      for (int i = 0; i < vals.length; i++) {
        m_vector[i] = Double.parseDouble(vals[i]);
        // System.out.print("DenseDoubleVector add: ");
        // System.out.println(m_vector[i]);
      }
      m_index = vals.length;
    } else {
      System.out.println("DenseDoubleVector no values found!");
      m_vector = new double[128];
      m_index = 0;
    }
  }

  public int getDimension() {
    return m_vector.length;
  }

  public int getLength() {
    return m_index;
  }

  public void set(int index, double value) {
    if (index >= m_vector.length) {
      double[] new_data = new double[m_index * 2];
      for (int i = 0; i < m_index; i++) {
        new_data[i] = m_vector[i];
      }
      m_vector = new_data;
    }

    m_vector[index] = value;
    // System.out.print("DenseDoubleVector add: ");
    // System.out.println(m_vector[index]);
    if (index > m_index) {
      m_index = index + 1; // update end index
    }
  }

  public double get(int index) {
    return m_vector[index];
  }

  public double dot(DenseDoubleVector otherVector) {
    double dotProduct = 0.0;
    for (int i = 0; i < m_index; i++) {
      dotProduct += m_vector[i] * otherVector.get(i);
    }
    return dotProduct;
  }

  public double[] toArray() {
    double[] values = new double[m_index];
    for (int i = 0; i < m_index; i++) {
      values[i] = m_vector[i];
    }
    return values;
  }

  @Override
  public String toString() {
    String str = "";
    String delimiter = ",";
    for (int i = 0; i < m_index; i++) {
      String val = Double.toString(m_vector[i]);
      // System.out.println(val);
      if (i == 0) {
        str += "" + val;
      } else {
        str += delimiter + " " + val;
      }
    }
    return str;
  }
}
