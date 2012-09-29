#include "hama/Pipes.hh"
#include "hama/TemplateFactory.hh"
#include "hadoop/StringUtils.hh"

#include <time.h>
#include <math.h>
#include <string>
#include <iostream>

using std::string;
using std::cout;

using HamaPipes::BSP;
using HamaPipes::BSPContext;
using namespace HadoopUtils;

class PiCalculationBSP: public BSP {
private:
    string masterTask;
    int iterations;
public:
  PiCalculationBSP(BSPContext& context) {
      iterations = 10000;
  }
    
  inline double closed_interval_rand(double x0, double x1)
  {
    return x0 + (x1 - x0) * rand() / ((double) RAND_MAX);
  }

  void bsp(BSPContext& context) {
      
    /* initialize random seed: */
    srand(time(NULL));
    
    int in = 0;
    for (int i = 0; i < iterations; i++) {
      //rand() -> greater than or equal to 0.0 and less than 1.0. 
      double x = 2.0 * closed_interval_rand(0, 1) - 1.0;
      double y = 2.0 * closed_interval_rand(0, 1) - 1.0;    
      if (sqrt(x * x + y * y) < 1.0) {
        in++;
      }
    }      
      
    double data = 4.0 * in / iterations;
      
    cout << "SendMessage to Master: " << masterTask << " value: "  << data  << "\n";
    context.sendMessage(masterTask, toString(data));
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
      double pi = 0.0;
      int msgCount = context.getNumCurrentMessages();
      cout << "MasterTask fetches " << msgCount << " results!\n";
      string received;
      for (int i=0; i<msgCount; i++) {
        string received = context.getCurrentMessage();
        pi += toDouble(received);
      }

      pi = pi / msgCount; //msgCount = numPeers
      cout << "Estimated value of PI is " << pi << " write results...\n";
      context.write("Estimated value of PI is", toString(pi));
    }
  }
};

int main(int argc, char *argv[]) {
  return HamaPipes::runTask(HamaPipes::TemplateFactory<PiCalculationBSP>());
}
