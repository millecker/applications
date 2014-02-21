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
package at.illecker.hama.hybrid.examples.hellohybrid;

import org.trifort.rootbeer.runtime.HamaPeer;
import org.trifort.rootbeer.runtime.Kernel;
import org.trifort.rootbeer.runtime.KeyValuePair;

public class HelloHybridKernel implements Kernel {

  public int numPeers = 0;
  public String peerName;
  public String examplePath;
  public int n;
  public int[] summation;
  public String[] allPeerNames;

  public String splitString;
  public String delimiter;
  public String[] splits1;
  public String[] splits2;
  public String[] splits3;
  public String[] splits4;
  public String[] splits5;

  public HelloHybridKernel(String examplePath, int n, String splitString,
      String delimiter) {
    this.examplePath = examplePath;
    this.n = n;
    this.summation = new int[n];
    this.splitString = splitString;
    this.delimiter = delimiter;
  }

  public void gpuMethod() {
    peerName = HamaPeer.getPeerName();
    numPeers = HamaPeer.getNumPeers();

    // test input
    int key = 1;
    int i = 0;
    KeyValuePair keyValuePair = new KeyValuePair(key, null);
    while (HamaPeer.readNext(keyValuePair)) {
      key = (Integer) keyValuePair.getKey();
      summation[i] = key;
      System.out.print("input: key: '");
      System.out.print(key);
      System.out.println("'");
      i++;
    }

    // test sequenceFileReader
    int seqFileId = HamaPeer
        .sequenceFileOpen(examplePath, 'r', "org.apache.hadoop.io.IntWritable",
            "org.apache.hadoop.io.NullWritable");

    int j = 0;
    while (HamaPeer.sequenceFileReadNext(seqFileId, keyValuePair)) {
      key = (Integer) keyValuePair.getKey();
      if (j < i) {
        summation[j] += key;
      }
      System.out.print("sequenceFileReader: key: '");
      System.out.print(key);
      System.out.println("'");
      j++;
    }
    HamaPeer.sequenceFileClose(seqFileId);

    // test output
    j = 0;
    while (j < i) {
      System.out.print("output: key: '");
      System.out.print(summation[j]);
      System.out.println("'");
      HamaPeer.write(summation[j], null);
      j++;
    }

    // test getAllPeerNames
    allPeerNames = HamaPeer.getAllPeerNames();

    // test String.split
    splits1 = splitString.split(delimiter);
    splits2 = splitString.split(delimiter, 2);
    splits3 = splitString.split(delimiter, 5);
    splits4 = splitString.split(delimiter, -2);
    splits5 = splitString.split(";");
  }

  public static void main(String[] args) {
    // Dummy constructor invocation
    // to keep kernel constructor in
    // rootbeer transformation
    new HelloHybridKernel(new String(""), 0, new String(""), new String(""));
  }
}
