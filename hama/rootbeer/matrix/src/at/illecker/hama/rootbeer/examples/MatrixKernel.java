package at.illecker.hama.rootbeer.examples;

import edu.syr.pcpratts.rootbeer.runtime.Kernel;
import edu.syr.pcpratts.rootbeer.runtime.RootbeerGpu;

public class MatrixKernel implements Kernel {

  private int[] m_a;
  private int[] m_b;
  private int[] m_c;
  private int m_blockSize;

  public MatrixKernel(int[] a, int[] b, int[] c, int block_size){
    m_a = a;
    m_b = b;
    m_c = c;
    m_blockSize = block_size;
  }

  public void gpuMethod(){
    int block_row = RootbeerGpu.getBlockIdxx();
    //int block_col = RootbeerGpu.getBlockIdxy();
    int row = RootbeerGpu.getThreadIdxx();
    //int col = RootbeerGpu.getThreadIdxy();
    int col = 1;

    int block_size = m_blockSize;
    
    //RootbeerGpu.setSharedFloat((row*block_size) + col, m_a[row*block_size]); 
  }
}
/*
__global__ void MatMulKernel(Matrix A, Matrix B, Matrix C) f
// Block row and column
int blockRow = blockIdx.y, blockCol = blockIdx.x;
// Each thread block computes one sub-matrix Csub of C
Matrix Csub = GetSubMatrix(C, blockRow, blockCol);
// Each thread computes 1 element of Csub accumulating results into Cvalue
float Cvalue = 0.0;
// Thread row and column within Csub
int row = threadIdx.y, col = threadIdx.x;
// Loop over all the sub-matrices of A and B required to compute Csub
for (int m = 0; m < (A.width / BLOCK_SIZE); ++m) f
// Get sub-matrices Asub of A and Bsub of B
Matrix Asub = GetSubMatrix(A, blockRow, m);
Matrix Bsub = GetSubMatrix(B, m, blockCol);
// Shared memory used to store Asub and Bsub respectively
__shared__ float As[BLOCK_SIZE][BLOCK_SIZE];
__shared__ float Bs[BLOCK_SIZE][BLOCK_SIZE];
// Load Asub and Bsub from device memory to shared memory
// Each thread loads one element of each sub-matrix
As[row][col] = GetElement(Asub, row, col);
Bs[row][col] = GetElement(Bsub, row, col);
__syncthreads();
// Multiply Asub and Bsub together
for (int e = 0; e < BLOCK_SIZE; ++e)
Cvalue += As[row][e] * Bs[e][col];
__syncthreads();
g
// Each thread writes one element of Csub to memory
SetElement(Csub, row, col, Cvalue);
g
*/
