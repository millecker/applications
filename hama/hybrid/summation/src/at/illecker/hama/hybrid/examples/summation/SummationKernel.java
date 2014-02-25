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

import org.trifort.rootbeer.runtime.HamaPeer;
import org.trifort.rootbeer.runtime.Kernel;
import org.trifort.rootbeer.runtime.KeyValuePair;

public class SummationKernel implements Kernel {

  private String m_masterTask;
  public int m_numPeers = 0;
  public String m_peerName;

  public SummationKernel(String masterTask) {
    this.m_masterTask = masterTask;
  }

  public void gpuMethod() {
    m_numPeers = HamaPeer.getNumPeers();

    double intermediateSum = 0.0;
    String key = "";
    String value = "";
    KeyValuePair keyValuePair = new KeyValuePair(key, value);

    while (HamaPeer.readNext(keyValuePair)) {
      key = (String) keyValuePair.getKey();
      value = (String) keyValuePair.getValue();

      System.out.print("SummationBSP.bsp key: '");
      System.out.print(key);
      System.out.println("'");
      System.out.print("SummationBSP.bsp value: '");
      System.out.print(value);
      System.out.println("'");

      double doubleVal = Double.parseDouble(value);
      System.out.println(doubleVal);
      System.out.println(Double.toString(doubleVal));
      intermediateSum += doubleVal;
    }

    System.out.print("SummationBSP.bsp send intermediateSum: ");
    System.out.println(intermediateSum);

    HamaPeer.send(m_masterTask, intermediateSum);
    HamaPeer.sync();

    // Fetch messages
    m_peerName = HamaPeer.getPeerName();
    if (m_peerName.equals(m_masterTask)) {

      double sum = 0.0;
      int msg_count = HamaPeer.getNumCurrentMessages();
      System.out.print("SummationBSP.bsp msg_count: ");
      System.out.println(msg_count);

      for (int i = 0; i < msg_count; i++) {
        double msg = HamaPeer.getCurrentDoubleMessage();
        System.out.print("SummationBSP.bsp message: ");
        System.out.println(msg);
        sum += msg;
      }

      System.out.print("SummationBSP.bsp write Sum: ");
      System.out.println(sum);
      HamaPeer.write("Sum", sum);
    }
  }

  public static void main(String[] args) {
    // Dummy constructor invocation
    // to keep kernel constructor in
    // rootbeer transformation
    new SummationKernel(new String());
  }
}
