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

class MatrixMultiplicationBSP: public BSP {
private:
    string HAMA_MAT_MULT_B_PATH = "hama.mat.mult.B.path";
public:
  MatrixMultiplicationBSP(BSPContext& context) {
      iterations = 10000;
  }

  void bsp(BSPContext& context) {
    // properties for our output matrix C
    int otherMatrixColumnDimension = -1;
    bool otherVectorSparse = false;
    string rowKey;
    string value;
      
    while(context.readNext(rowKey,value)) {
      // System.out.println(peer.getPeerName() + " " + rowKey.get() + "|"
      // + value.toString());
      string bMatrixKey;
      string columnVector = new VectorWritable();
      while (context.sequenceFileNext(bMatrixKey,columnVector)) {
        // detect the properties of our columnvector
        if (otherMatrixColumnDimension == -1) {
          otherMatrixColumnDimension = columnVector.getVector().getDimension();
          otherVectorSparse = columnVector.getVector().isSparse();  
        }
          
        double dot = value.getVector().dot(columnVector.getVector());
        
        // we use row based partitioning once again to distribute the
        // outcome
        context.sendMessage(context.getPeerName(
                    rowKey.get() % (context.getNumPeers() - 1)),
                    new ResultMessage(rowKey.get(), bMatrixKey.get(), dot));
      }
      reopenOtherMatrix(peer.getConfiguration());
    }
      
      
    peer.sync();
      
    // TODO we have a sorted message queue now (in hama 0.5), we can recode
    // this better.
    // a peer gets all column entries for multiple rows based on row number
      
      
      
      
  }
    
  void setup(BSPContext& context) {
    
      
  }
    
  void cleanup(BSPContext& context) {
   
  }
};

int main(int argc, char *argv[]) {
  return HamaPipes::runTask(HamaPipes::TemplateFactory<MatrixMultiplicationBSP>());
}
