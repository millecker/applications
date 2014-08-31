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

import org.trifort.rootbeer.runtime.HamaPeer;
import org.trifort.rootbeer.runtime.Kernel;
import org.trifort.rootbeer.runtime.KeyValuePair;

public class MatrixMultiplicationHybridKernelOld implements Kernel {

  public int m_seqFileId = 0;
  public String m_matrixB_path;
  public String m_peerName;
  public String m_masterTask;

  public MatrixMultiplicationHybridKernelOld(String matrixB_path) {
    this.m_matrixB_path = matrixB_path;
  }

  public void gpuMethod() {

    m_masterTask = HamaPeer.getPeerName(0);
    m_peerName = HamaPeer.getPeerName();

    reopenMatrixB();

    int aRowKey = 1; // [-128, 0] java_lang_Integer_valueOf11_5_ will fail
    String aRowVectorStr = "";
    KeyValuePair aKeyValuePair = new KeyValuePair(aRowKey, aRowVectorStr);

    // while for each row of matrix A
    while (HamaPeer.readNext(aKeyValuePair)) {
      aRowKey = (Integer) aKeyValuePair.getKey();
      aRowVectorStr = (String) aKeyValuePair.getValue();
      // System.out.print("got aRowKey: ");
      // System.out.println(aRowKey);
      // System.out.print("got aRowVectorStr: ");
      // System.out.println(aRowVectorStr);

      DenseDoubleVector aRowVector = new DenseDoubleVector(aRowVectorStr);

      int bColKey = 1; // [-128, 0] java_lang_Integer_valueOf11_5_ will fail
      String bColVectorStr = "";
      KeyValuePair bKeyValuePair = new KeyValuePair(bColKey, bColVectorStr);

      // dynamic column values, depend on matrix B cols
      DenseDoubleVector colValues = new DenseDoubleVector();

      // while for each col of matrix B
      while (HamaPeer.sequenceFileReadNext(m_seqFileId, bKeyValuePair)) {
        bColKey = (Integer) bKeyValuePair.getKey();
        bColVectorStr = (String) bKeyValuePair.getValue();
        // System.out.print("got bColKey: ");
        // System.out.println(bColKey);
        // System.out.print("got bColVectorStr: ");
        // System.out.println(bColVectorStr);

        DenseDoubleVector bColVector = new DenseDoubleVector(bColVectorStr);
        double dot = aRowVector.dot(bColVector);
        // System.out.print("calculated dot: ");
        // System.out.println(dot);

        colValues.set(bColKey, dot);
      }
      // Submit one calculated row
      String message = ":" + aRowKey + ":" + colValues.toString();
      System.out.print("send message: ");
      System.out.println(message);

      HamaPeer.send(m_masterTask, message);

      reopenMatrixB();
    }

    HamaPeer.sequenceFileClose(m_seqFileId);
    HamaPeer.sync();

    if (m_peerName.equals(m_masterTask)) {

      int msgCount = HamaPeer.getNumCurrentMessages();
      for (int i = 0; i < msgCount; i++) {

        // :key:value1,value2,value3
        String message = HamaPeer.getCurrentStringMessage();
        System.out.print("got message: ");
        System.out.println(message);

        // split delimiter and value
        String delimiter = message.substring(0, 1);
        String keyValueStr = message.substring(1);
        // System.out.print("delimiter: ");
        // System.out.println(delimiter);
        // System.out.print("keyValueStr: ");
        // System.out.println(keyValueStr);

        // find position of delimiter
        int pos = keyValueStr.indexOf(delimiter);
        // System.out.print("pos: ");
        // System.out.println(pos);

        int key = Integer.parseInt(keyValueStr.substring(0, pos));
        System.out.print("write key: ");
        System.out.println(key);

        String values = keyValueStr.substring(pos + 1);
        System.out.print("write value: ");
        System.out.println(values);

        // Attention valueOf(0) will fail
        HamaPeer.write(new Integer(key), values);
      }
    }

  }

  void reopenMatrixB() {
    if (m_seqFileId != 0) {
      HamaPeer.sequenceFileClose(m_seqFileId);
    }

    m_seqFileId = HamaPeer.sequenceFileOpen(m_matrixB_path, 'r',
        "org.apache.hadoop.io.IntWritable",
        "org.apache.hama.commons.io.PipesVectorWritable");
  }

  public static void main(String[] args) {
    // Dummy constructor invocation
    // to keep kernel constructor in
    // rootbeer transformation
    new MatrixMultiplicationHybridKernelOld(new String());
  }
}
