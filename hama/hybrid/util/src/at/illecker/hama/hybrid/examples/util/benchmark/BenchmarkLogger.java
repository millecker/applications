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
package at.illecker.hama.hybrid.examples.util.benchmark;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class BenchmarkLogger {
  private List<Scenario> scenarios;

  public BenchmarkLogger() {
    this.scenarios = new ArrayList<Scenario>();
  }

  public boolean addScenario(Scenario s) {
    s.setId(this.scenarios.size() + 1);
    return this.scenarios.add(s);
  }

  public String getJson() {
    String jsonStr = "";
    Gson gson = new GsonBuilder().create();
    for (Scenario s : scenarios) {
      jsonStr += gson.toJson(s);
    }
    return jsonStr;
  }

  public static void main(String[] args) throws IOException {
    BenchmarkLogger bench = new BenchmarkLogger();

    Scenario s = new Scenario();
    s.addParameter(new Parameter<String, String>("type", "GPU"));
    s.addParameter(new Parameter<String, Long>("n", 10000L));
    s.addMeasurement(new Measurement<Double>("total", 3.24, "ns"));
    s.addMeasurement(new Measurement<Double>("setup", 1.12, "ns"));
    s.addMeasurement(new Measurement<Double>("bsp", 2.12, "ns"));
    bench.addScenario(s);

    s = new Scenario();
    s.addParameter(new Parameter<String, String>("type", "CPU"));
    s.addParameter(new Parameter<String, Long>("n", 10000L));
    s.addMeasurement(new Measurement<Double>("total", 2.24, "ns"));
    s.addMeasurement(new Measurement<Double>("setup", 1.12, "ns"));
    s.addMeasurement(new Measurement<Double>("bsp", 1.12, "ns"));
    bench.addScenario(s);

    System.out.println(bench.getJson());
  }
}
