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

import edu.syr.pcpratts.rootbeer.runtime.HamaPeer;
import edu.syr.pcpratts.rootbeer.runtime.Kernel;

public class HelloHybridKernel implements Kernel {

  public int numPeers = 0;
  public String peerName;

  public String splitString;
  public String delimiter;
  public String[] splits1;
  public String[] splits2;
  public String[] splits3;
  public String[] splits4;
  public String[] splits5;

  public HelloHybridKernel(String splitString, String delimiter) {
    this.splitString = splitString;
    this.delimiter = delimiter;
  }

  public void gpuMethod() {
    peerName = HamaPeer.getPeerName();
    numPeers = HamaPeer.getNumPeers();

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
    new HelloHybridKernel(new String(""), new String(""));
  }
}
