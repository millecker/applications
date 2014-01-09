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

class TestNullWritableBSP: public BSP<double,void,double,double,void> {
private:
  string master_task_;
  
public:
  TestNullWritableBSP(BSPContext<double,void,double,double,void>& context) {  }

  void bsp(BSPContext<double,void,double,double,void>& context) {
    int seq_file_id1 = context.sequenceFileOpen("/examples/output/testNullWritable1/part-00000","r",
                                            "org.apache.hadoop.io.NullWritable",
                                            "org.apache.hadoop.io.DoubleWritable");

    int seq_file_id2 = context.sequenceFileOpen("/examples/output/testNullWritable2/part-00000","r",
                                            "org.apache.hadoop.io.DoubleWritable",
                                            "org.apache.hadoop.io.NullWritable");

    int seq_file_id3 = context.sequenceFileOpen("/examples/output/testNullWritable3/output1.seq","w",
                                            "org.apache.hadoop.io.DoubleWritable",
                                            "org.apache.hadoop.io.DoubleWritable");

    int seq_file_id4 = context.sequenceFileOpen("/examples/output/testNullWritable3/output2.seq","w",
                                            "org.apache.hadoop.io.DoubleWritable",
                                            "org.apache.hadoop.io.NullWritable");

    int seq_file_id5 = context.sequenceFileOpen("/examples/output/testNullWritable3/output3.seq","w",
                                            "org.apache.hadoop.io.NullWritable",
                                            "org.apache.hadoop.io.DoubleWritable");
    double key = 0;
    while(context.readNext(key)) {
      printf("key: %f\n", key);

      double seq_file_value = 0;
      context.sequenceFileReadNext<double>(seq_file_id1, seq_file_value);

      double seq_file_key = 0;
      context.sequenceFileReadNext<double>(seq_file_id2, seq_file_key);

      double value = key + seq_file_value + seq_file_key;

      context.write(key, value);

      context.sequenceFileAppend<double,double>(seq_file_id3, key, value);
      context.sequenceFileAppend<double>(seq_file_id4, key);
      context.sequenceFileAppend<double>(seq_file_id5, value);
    }

    context.sequenceFileClose(seq_file_id1);
    context.sequenceFileClose(seq_file_id2);
    context.sequenceFileClose(seq_file_id3);
    context.sequenceFileClose(seq_file_id4);
    context.sequenceFileClose(seq_file_id5);
  }
};

int main(int argc, char *argv[]) {
  return HamaPipes::runTask<double,void,double,double,void>(HamaPipes::TemplateFactory<TestNullWritableBSP,double,void,double,double,void>());
}

