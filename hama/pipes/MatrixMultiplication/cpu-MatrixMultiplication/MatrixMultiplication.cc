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
using HamaPipes::BSPJob;
using HamaPipes::Partitioner;
using HamaPipes::BSPContext;
using namespace HadoopUtils;

class MatrixMultiplicationBSP: public BSP {
private:
    string masterTask;
    int seqFileID;
    string HAMA_MAT_MULT_B_PATH;
public:
  MatrixMultiplicationBSP(BSPContext& context) { 
    HAMA_MAT_MULT_B_PATH = "hama.mat.mult.B.path";
  }

  void bsp(BSPContext& context) {
      
    string rowKey;
    string value;
    // while for each row of matrixA
    while(context.readNext(rowKey,value)) {
      // System.out.println(peer.getPeerName() + " " + rowKey.get() + "|"
      // + value.toString());
        
      string bMatrixKey;
      string bColumnVector;
      //VectorWritable colValues = null;
      string colValues;
        
      // while for each col of matrixB
      while (context.sequenceFileReadNext(seqFileID,bMatrixKey,bColumnVector)) {
        
          cout << "bMatrixKey: " << bMatrixKey << "bColumnVector: " << bColumnVector << "\n";
          //if (colValues == null)
          //   colValues = new VectorWritable(new DenseDoubleVector(
          //            columnVector.getVector().getDimension()));
          
          //double dot = value.getVector().dot(columnVector.getVector());
          
          //colValues.getVector().set(bMatrixKey.get(), dot);
      }
        
      context.sendMessage(masterTask, rowKey); //rowKey << ":" << colValues
        
      reopenMatrixB(context);
    }
    context.sequenceFileClose(seqFileID);
      
    context.sync();
  }
    
  void setup(BSPContext& context) {
      // Choose one as a master
      masterTask = context.getPeerName(context.getNumPeers() / 2);
      
      reopenMatrixB(context);
  }
    
  void cleanup(BSPContext& context) {
      if (context.getPeerName().compare(masterTask)==0) {
          cout << "I'm the MasterTask fetch results!\n";
          
          int msgCount = context.getNumCurrentMessages();
          cout << "MasterTask fetches " << msgCount << " results!\n";
          
          for (int i=0; i<msgCount; i++) {
              
              string received = context.getCurrentMessage();
              
              cout << "RECEIVED MSG: " << received << "\n";
              
              //peer.write(
              //           new IntWritable(currentMatrixRowMessage.getRowIndex()),
              //           currentMatrixRowMessage.getColValues());
              
              //context.write("Sum", toString(sum));
          }
      }
  }
    
  void reopenMatrixB(BSPContext& context) {
    context.sequenceFileClose(seqFileID);
    const BSPJob* job = context.getBSPJob();
    string path = job->get(HAMA_MAT_MULT_B_PATH);
    cout << "sequenceFileOpen path: " << path << "\n";
    seqFileID = context.sequenceFileOpen(path,"r");
  } 
    
};



class MatrixRowPartitioner: public Partitioner {
public:
    MatrixRowPartitioner(BSPContext& context) { }
        
    int partition(const string& key,const string& value, int32_t numTasks) {
      cout << "partition key: " << key << " value: " << value << " numTasks: "<< numTasks <<"\n";
      return toInt(key) % numTasks;
    }
};

int main(int argc, char *argv[]) {
  return HamaPipes::runTask(HamaPipes::TemplateFactory<MatrixMultiplicationBSP,MatrixRowPartitioner>());
}
