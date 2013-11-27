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
package at.illecker.hama.hybrid.examples.summation;

import edu.syr.pcpratts.rootbeer.runtime.HamaPeer;
import edu.syr.pcpratts.rootbeer.runtime.Kernel;

public class SummationKernel implements Kernel {

  private String masterTask;
  public int numPeers = 0;
  public String peerName;

  public SummationKernel(String masterTask) {
    this.masterTask = masterTask;
  }

  public void gpuMethod() {
    peerName = HamaPeer.getPeerName();
    numPeers = HamaPeer.getNumPeers();

    double intermediateSum = 0.0;
    String key = "";
    String value = "";

    while (HamaPeer.readNext(key, value)) {
      System.out.println("SummationBSP.bsp key: " + key + " value: " + value
          + "\n");
      // intermediateSum += Double.parseDouble(value);
    }

    System.out.println("SummationBSP.bsp send intermediateSum: "
        + intermediateSum + "\n");

    HamaPeer.sendDouble(masterTask, intermediateSum);
    HamaPeer.sync();
  }

  public static void main(String[] args) {
    // Dummy constructor invocation
    // to keep kernel constructor in
    // rootbeer transformation
    new SummationKernel(new String());
  }
}
