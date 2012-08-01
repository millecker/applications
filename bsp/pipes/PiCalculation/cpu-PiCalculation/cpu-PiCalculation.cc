#include "hama/Pipes.hh"
#include "hama/TemplateFactory.hh"
#include "hadoop/StringUtils.hh"

class PiCalculationBSP: public HamaPipes::BSP {
private:
    std::string masterTask;
    std::int iterations = 10000;
public:
  PiCalculationBSP(HamaPipes::BSPContext& context){}

  void bsp(HamaPipes::BSPContext& context) {
    //std::vector<std::string> words =
    //  HadoopUtils::splitString(context.getInputValue(), " ");
      
    int in = 0;
    for (int i = 0; i < iterations; i++) {
      double x = 2.0 * Math.random() - 1.0, y = 2.0 * Math.random() - 1.0;
      if ((Math.sqrt(x * x + y * y) < 1.0)) {
        in++;
      }
    }      
      
    double data = 4.0 * in / iterations;
      
    context.send(masterTask, data);
    context.sync();
  }
    
  void setup(HamaPipes::BSPContext& context) {
    // Choose one as a master
    masterTask = context.getPeerName(context.getNumPeers() / 2);
  }
    
  void cleanup(HamaPipes::BSPContext& context) {
    if (context.getPeerName().equals(masterTask)) {
      std::double pi = 0.0;
      std::int numPeers = context.getNumCurrentMessages();
      std::string received;
      while ((received = context.getCurrentMessage()) != null) {
        pi += received();
      }
          
      pi = pi / numPeers;
      context.write("Estimated value of PI is", pi);
    }
  }
};


int main(int argc, char *argv[]) {
  return HadoopPipes::runTask(HamaPipes::TemplateFactory<PiCalculationBSP>());
}
