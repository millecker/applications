#include "hama/Pipes.hh"
#include "hama/TemplateFactory.hh"
#include "hadoop/StringUtils.hh"

#include<stdlib.h>
#include<string>
#include<iostream>

using std::string;
using std::cout;
using std::cerr;

using HamaPipes::BSP;
using HamaPipes::BSPContext;
using namespace HadoopUtils;

class SumBSP: public BSP {
private:
  string masterTask;
public:
  SumBSP(BSPContext& context) {  }

  void bsp(BSPContext& context) {
    
    double intermediateSum = 0.0;
    string key;
    string value;
    
    while(context.readNext(key,value)) {
      cout << "SumBSP bsp: key: " << key << " value: "  << value  << "\n";
      intermediateSum += toDouble(value);
      
    }
    cout << "SendMessage to Master: " << masterTask << " value: "  << intermediateSum  << "\n";
    context.sendMessage(masterTask, toString(intermediateSum));
    context.sync();
  }
    
  void setup(BSPContext& context) {
    // Choose one as a master
    masterTask = context.getPeerName(context.getNumPeers() / 2);
    cout << "MasterTask: " << masterTask << "\n";
  }
    
  void cleanup(BSPContext& context) {    
    if (context.getPeerName().compare(masterTask)==0) {
      cout << "I'm the MasterTask fetch results!\n";
      double sum = 0.0;
      int msgCount = context.getNumCurrentMessages();
      cout << "MasterTask fetches " << msgCount << " results!\n";
      for (int i=0; i<msgCount; i++) {
        string received = context.getCurrentMessage();
        sum += toDouble(received);
      }
      cout << "Sum " << sum << " write results...\n";
      context.write("Sum", toString(sum));
    }
  }    
};

int main(int argc, char *argv[]) {
  return HamaPipes::runTask(HamaPipes::TemplateFactory<SumBSP>());
}
