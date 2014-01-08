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

class TestNullWritableBSP: public BSP<string,string,void,double,void> {  
public:
  TestNullWritableBSP(BSPContext<string,string,void,double,void>& context) {  }

  void bsp(BSPContext<string,string,void,double,void>& context) {
    
    string key;
    string value;
    
    while(context.readNext(key, value)) {
      double val = HadoopUtils::toDouble(value);
      printf("key: '%s' value: %f\n", key.c_str(), val);
      context.write(val);
    }
    
  }
};

int main(int argc, char *argv[]) {
  return HamaPipes::runTask<string,string,void,double,void>(HamaPipes::TemplateFactory<TestNullWritableBSP,string,string,void,double,void>());
}

