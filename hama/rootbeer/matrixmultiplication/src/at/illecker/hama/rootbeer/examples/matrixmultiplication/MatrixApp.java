package at.illecker.hama.rootbeer.examples.matrixmultiplication;

import edu.syr.pcpratts.rootbeer.runtime.util.Stopwatch;
import edu.syr.pcpratts.rootbeer.runtime.Rootbeer;
import edu.syr.pcpratts.rootbeer.runtime.Kernel;
import edu.syr.pcpratts.rootbeer.runtime.StatsRow;
import java.util.List;
import java.util.ArrayList;

import at.illecker.hama.rootbeer.examples.matrixmultiplication.MatrixCpuThread;
import at.illecker.hama.rootbeer.examples.matrixmultiplication.MatrixKernel;

//see: http://www.shodor.org/media/content//petascale/materials/UPModules/matrixMultiplication/moduleDocument.pdf
public class MatrixApp {

  private int m_a[];
  private int m_b[];
  private int m_cgpu[];
  private int m_ccpu[];
  private int m_blockSize;
  private int m_gridSize;

  public MatrixApp(){
    m_blockSize = 64;
    m_gridSize = 1024;
    m_a = new int[m_blockSize*m_blockSize];
    m_b = new int[m_blockSize*m_blockSize*m_gridSize];
    m_ccpu = new int[m_blockSize*m_blockSize*m_gridSize];
    m_cgpu = new int[m_blockSize*m_blockSize*m_gridSize];

    for(int i = 0; i < m_a.length; ++i){
      m_a[i] = i;
    }

    for(int i = 0; i < m_b.length; ++i){
      m_b[i] = i;
    }
  }

  public void cpuRun(){
    int num_cores = Runtime.getRuntime().availableProcessors();
    Stopwatch watch = new Stopwatch();
    watch.start();
    List<MatrixCpuThread> threads = new ArrayList<MatrixCpuThread>();
    for(int i = 0; i < num_cores; ++i){
      MatrixCpuThread thread = new MatrixCpuThread(m_a, m_b, m_ccpu, i,
        m_blockSize, m_gridSize, num_cores);
      threads.add(thread);
    }
    for(int i = 0; i < num_cores; ++i){
      MatrixCpuThread thread = threads.get(i);
      thread.join();
    }
    watch.stop();
    System.out.println("cpu time: "+watch.elapsedTimeMillis()+" ms");
  }

  public void gpuRun(){
    Stopwatch watch = new Stopwatch();
    watch.start();
    List<Kernel> kernels = new ArrayList<Kernel>();
    for(int i = 0; i < m_blockSize * m_gridSize; ++i){
      kernels.add(new MatrixKernel(null, null, null, m_blockSize));
    } 
    Rootbeer rootbeer = new Rootbeer();
    rootbeer.setThreadConfig(m_blockSize, m_gridSize);
    rootbeer.runAll(kernels);
    watch.stop();
    System.out.println("gpu time: "+watch.elapsedTimeMillis()+" ms");

    List<StatsRow> stats = rootbeer.getStats();
    for(StatsRow row : stats){
      System.out.println("  StatsRow:");
      System.out.println("    init time: "+row.getInitTime());
      System.out.println("    serial time: "+row.getSerializationTime());
      System.out.println("    exec time: "+row.getExecutionTime());
      System.out.println("    deserial time: "+row.getDeserializationTime());
      System.out.println("    num blocks: "+row.getNumBlocks());
      System.out.println("    num threads: "+row.getNumThreads());
    }
  }

  public void run(){
    cpuRun();
    gpuRun();
  }

  public static void main(String[] args){
    //initialize CudaRuntime2.v()
    Rootbeer rootbeer = new Rootbeer();

    MatrixApp app = new MatrixApp();
    app.run();
  }
}
