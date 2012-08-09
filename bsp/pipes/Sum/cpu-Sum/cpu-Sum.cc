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
using std::cerr;

using HamaPipes::BSP;
using HamaPipes::BSPContext;

class SumBSP: public BSP {
private:
  string masterTask;
public:
  SumBSP(BSPContext& context) {  }

  void bsp(BSPContext& context) {
    //std::vector<std::string> words =
    //  HadoopUtils::splitString(context.getInputValue(), " ");
    
    double intermediateSum = 0.0;
      
    string key; // = context.getInputKey();
    string value; // = context.getInputValue();
    
    while(context.readNext(key,value)) {
      cout << "SumBSP bsp: key: " << key << " value: "  << value  << "\n";
      intermediateSum += string2double(value);
      
    }
    cerr << "SendMessage to Master: " << masterTask << " value: "  << intermediateSum  << "\n";
    context.sendMessage(masterTask, double2string(intermediateSum));
    context.sync();
  }
    
  void setup(BSPContext& context) {
    // Choose one as a master
    masterTask = context.getPeerName(context.getNumPeers() / 2);
    cerr << "MasterTask: " << masterTask << "\n";
  }
    
  void cleanup(BSPContext& context) {
    
    if (context.getPeerName().compare(masterTask)==0) {
      double sum = 0.0;
      //std::int numPeers = context.getNumCurrentMessages();
      
      cerr << "I'm the MasterTask fetch results!\n";
      int msgCount = context.getNumCurrentMessages();
      cerr << "MasterTask fetches " << msgCount << " results!\n";
      for (int i=0; i<msgCount; i++) {
        string received = context.getCurrentMessage();
      
        sum += string2double(received);
      }
  
      context.write("Sum", double2string(sum));
    }
  }

    
    string double2string(double d)
    {
        std::stringstream ss;
        ss << std::setprecision(16) << d;
        return ss.str();
    }
    
    double string2double(const string& str) const {
        const char* begin = str.c_str();
        char* end;
        double val = strtod(begin, &end);
        size_t s = end - begin;
        if(s < str.size()) {
            cout << "SumBSP bsp: string2double: invalid double value: "  << str  << "\n";
        }
        return val;
    }
    
};

int main(int argc, char *argv[]) {
  return HamaPipes::runTask(HamaPipes::TemplateFactory<SumBSP>());
}
