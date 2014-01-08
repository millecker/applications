/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "hama/Pipes.hh"
#include "hama/TemplateFactory.hh"
#include "hadoop/StringUtils.hh"

#include <stdlib.h>
#include <string>
#include <iostream>

using std::string;

using HamaPipes::BSP;
using HamaPipes::BSPContext;

class TestNullWritableBSP: public BSP<void,double,double,void,double> {
private:
  string master_task_;

public:
  TestNullWritableBSP(BSPContext<void,double,double,void,double>& context) {  }

  void setup(BSPContext<void,double,double,void,double>& context) {
    master_task_ = context.getPeerName(context.getNumPeers() / 2);
  }

  void bsp(BSPContext<void,double,double,void,double>& context) {
    double value = 0;
    while(context.readNext(value)) {
      printf("value: %f\n", value);
      context.sendMessage(master_task_, value);
    }

    context.sync();
  }

  void cleanup(BSPContext<void,double,double,void,double>& context) {
    if (context.getPeerName().compare(master_task_)==0) {
      
      int msg_count = context.getNumCurrentMessages();
      for (int i=0; i < msg_count; i++) {
        double val = context.getCurrentMessage();
        printf("message: %f\n", val);
        context.write(val);
      }
    }
  }
};

int main(int argc, char *argv[]) {
  return HamaPipes::runTask<void,double,double,void,double>(HamaPipes::TemplateFactory<TestNullWritableBSP,void,double,double,void,double>());
}

