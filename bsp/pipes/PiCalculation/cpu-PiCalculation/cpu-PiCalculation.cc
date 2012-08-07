#include "hama/Pipes.hh"
#include "hama/TemplateFactory.hh"
#include "hadoop/StringUtils.hh"

#include <time.h>
#include <math.h>
#include <string>
#include <sstream>
#include <iostream>
#include <iomanip>

using std::string;
using std::cout;

class PiCalculationBSP: public HamaPipes::BSP {
private:
    string masterTask;
    int iterations;
public:
  PiCalculationBSP(HamaPipes::BSPContext& context) {
      iterations = 10000;
  }

  void bsp(HamaPipes::BSPContext& context) {
    //std::vector<std::string> words =
    //  HadoopUtils::splitString(context.getInputValue(), " ");
      
    /* initialize random seed: */
    srand ( time(NULL) );
      
    int in = 0;
    for (int i = 0; i < iterations; i++) {
      double x = 2.0 * rand() - 1.0, y = 2.0 * rand() - 1.0;
      if ((sqrt(x * x + y * y) < 1.0)) {
        in++;
      }
    }      
      
    double data = 4.0 * in / iterations;
      
    context.send(masterTask, double2string(data));
    context.sync();
  }
    
  void setup(HamaPipes::BSPContext& context) {
    // Choose one as a master
    masterTask = context.getPeerName(context.getNumPeers() / 2);
  }
    
  void cleanup(HamaPipes::BSPContext& context) {
    if (context.getPeerName().compare(masterTask)) {
      double pi = 0.0;
      int numPeers = context.getNumCurrentMessages();
      string received;
      while (!(received = context.getCurrentMessage()).empty()) {
        pi += string2double(received);
      }
          
      pi = pi / numPeers;
      context.write("Estimated value of PI is", double2string(pi));
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
            cout << "PiCalculation bsp: string2double: invalid double value: "  << str  << "\n";
        }
        return val;
    }
    

};


int main(int argc, char *argv[]) {
  return HamaPipes::runTask(HamaPipes::TemplateFactory<PiCalculationBSP>());
}
