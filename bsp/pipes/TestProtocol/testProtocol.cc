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

class TestProtocolBSP: public BSP {
public:
  TestProtocolBSP(BSPContext& context) {  }

  void bsp(BSPContext& context) {
      
     /*
      
      virtual void clear() = 0;
      virtual void reopenInput() = 0;

      virtual vector<string> getAllPeerNames() = 0;
      virtual int getPeerIndex() = 0;
      virtual long getSuperstepCount() = 0;
      
      virtual int getNumCurrentMessages() = 0;
      virtual const string& getCurrentMessage() = 0;
      virtual void sendMessage(const string& peerName, const string& msg) = 0;
      
      */
      
    /* Test virtual int getNumPeers() = 0; */
    cout << "TestProtocol: context.getNumPeers(): " << context.getNumPeers() << endl;
      
    /* TEST virtual const string& getPeerName() = 0;
            virtual const string& getPeerName(int index) = 0; */
    cout << "TestProtocol: context.getPeerName(): " << context.getPeerName() << endl;
    cout << "TestProtocol: context.getPeerName(-1): " << context.getPeerName(-1) << endl;
    cout << "TestProtocol: context.getPeerName(10): " << context.getPeerName(10) << endl;

    /* TEST virtual bool readNext(string& key, string& value) = 0; */
    string key;
    string value;
    cout << "TestProtocol: context.readNext(key,value): " << context.readNext(key,value) << endl;
    cout << "TestProtocol: context.readNext(key,value): key: '" << key << "' value: '" << value << "'" << endl;
    
    context.sendMessage(context.getPeerName(), "TestMessage");
    
    /* TEST virtual void sync() = 0; */
    cout << "TestProtocol: context.sync();"  << endl;
    context.sync();
      
    /* TEST virtual void write(const string& key, const string& value) = 0; */
    context.write("TestKey", "TestValue");
  }
    
  void setup(BSPContext& context) {
    
  }
    
  void cleanup(BSPContext& context) {
    
  }
};

int main(int argc, char *argv[]) {
  return HamaPipes::runTask(HamaPipes::TemplateFactory<TestProtocolBSP>());
}
