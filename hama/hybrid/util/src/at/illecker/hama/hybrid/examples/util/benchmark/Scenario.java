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

import java.util.HashSet;
import java.util.Set;

public class Scenario {
  private int id;
  private Set<Parameter<?, ?>> parameters;
  private Set<Measurement<?>> measurements;

  public Scenario() {
    this.parameters = new HashSet<Parameter<?, ?>>();
    this.measurements = new HashSet<Measurement<?>>();
  }

  public int getId() {
    return id;
  }

  public void setId(int id) {
    this.id = id;
  }

  public Set<Parameter<?, ?>> getParameters() {
    return this.parameters;
  }

  public boolean addParameter(Parameter<?, ?> parameter) {
    return this.parameters.add(parameter);
  }

  public Set<Measurement<?>> getMeasurements() {
    return this.measurements;
  }

  public boolean addMeasurement(Measurement<?> measurement) {
    return this.measurements.add(measurement);
  }

}
