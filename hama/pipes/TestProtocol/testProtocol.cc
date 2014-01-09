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

#include<stdlib.h>
#include<string>
#include<sstream>
#include<iostream>
#include<iomanip>

using std::string;
using std::cout;
using std::endl;

using HamaPipes::BSP;
using HamaPipes::BSPContext;

class TestProtocolBSP: public BSP<string,string,string,string,string> {
private:
    string my_name_;

public:
  TestProtocolBSP(BSPContext<string,string,string,string,string>& context) {  }

  
  void setup(BSPContext<string,string,string,string,string>& context) {
    my_name_ = context.getPeerName();
  }
  
  void bsp(BSPContext<string,string,string,string,string>& context) {
      
    /*
    virtual void clear() = 0;
    virtual void reopenInput() = 0;
    */
      
    /* virtual int getPeerIndex() = 0; */
    cout << "TestProtocol: context.getPeerIndex(): " << context.getPeerIndex() << endl;
    
    /* Test virtual int getNumPeers() = 0; */
    cout << "TestProtocol: context.getNumPeers(): " << context.getNumPeers() << endl;
      
    /* Test virtual long getSuperstepCount() = 0; */
    cout << "TestProtocol: context.getSuperstepCount(): " << context.getSuperstepCount() << endl;

      
    /* TEST virtual const string& getPeerName() = 0;
            virtual const string& getPeerName(int index) = 0; */
    cout << "TestProtocol: context.getPeerName(): " << my_name_ << endl;
    cout << "TestProtocol: context.getPeerName(-1): " << context.getPeerName(-1) << endl;
    cout << "TestProtocol: context.getPeerName(10): " << context.getPeerName(10) << endl;

    /* TEST virtual vector<string> getAllPeerNames() = 0; */
    vector<string> peerNames = context.getAllPeerNames(); 
    for (int i=0; i<peerNames.size(); i++)
      cout << "TestProtocol: context.getAllPeerNames("<< i <<"): " << peerNames[i] << endl;
      
    /* TEST virtual bool readNext(string& key, string& value) = 0; */
    string key;
    string value;
    cout << "TestProtocol: context.readNext Result: " << ((context.readNext(key,value))?"true":"false") << endl;
    cout << "TestProtocol: context.readNext(key,value): key: '" << key << "' value: '" << value << "'" << endl;
    
    
    /* TEST virtual void sendMessage(const string& peerName, const string& msg) = 0; */
    for (int i=0; i<5; i++) {
        std::ostringstream oss;
        oss << "TestMessage " << i;
        context.sendMessage(my_name_, oss.str());
    } 
    /* TEST virtual void sync() = 0; */
    cout << "TestProtocol: context.sync();"  << endl;
    context.sync();
  }
    
  void cleanup(BSPContext<string,string,string,string,string>& context) {
    
      /* TEST virtual int getNumCurrentMessages() = 0; */
      int messageCount = context.getNumCurrentMessages(); 
      
      /* TEST virtual void write(const string& key, const string& value) = 0; */
      for (int i=0; i<messageCount; i++) {
          
          /* TEST virtual const string& getCurrentMessage() = 0; */
          string msg = context.getCurrentMessage();
          std::ostringstream oss;
          oss << "Output: " << msg;
          context.write(my_name_, oss.str());	
      }
  }
};

int main(int argc, char *argv[]) {
  return HamaPipes::runTask<string,string,string,string,string>(HamaPipes::TemplateFactory<TestProtocolBSP,string,string,string,string,string>());
}

