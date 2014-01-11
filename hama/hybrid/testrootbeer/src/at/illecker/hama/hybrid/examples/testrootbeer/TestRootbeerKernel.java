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
package at.illecker.hama.hybrid.examples.testrootbeer;

import edu.syr.pcpratts.rootbeer.runtime.HamaPeer;
import edu.syr.pcpratts.rootbeer.runtime.Kernel;

public class TestRootbeerKernel implements Kernel {

  public int numPeers = 0;
  public String peerName;
  public String examplePath;
  public int n;
  public int[] summation;
  public String[] allPeerNames;

  public TestRootbeerKernel(String examplePath, int n) {
    this.examplePath = examplePath;
    this.n = n;
    this.summation = new int[n];
  }

  public void gpuMethod() {
    // is required for
    // error: identifier "java_lang_String__array_new" is undefined
    allPeerNames = new String[] { "test" };
    
    peerName = HamaPeer.getPeerName();
    
    //numPeers = HamaPeer.getNumPeers();

    
/*
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
*/
    
/*    
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
*/
    
/*
    // test output
    j = 0;
    while (j < i) {
      System.out.print("output: key: '");
      System.out.print(summation[j]);
      System.out.println("'");
      HamaPeer.write(summation[j], null);
      j++;
    }
*/
    
    // test getAllPeerNames
    //allPeerNames = HamaPeer.getAllPeerNames();

  }

  public static void main(String[] args) {
    // Dummy constructor invocation
    // to keep kernel constructor in
    // rootbeer transformation
    new TestRootbeerKernel(new String(""), 0);
  }
}
