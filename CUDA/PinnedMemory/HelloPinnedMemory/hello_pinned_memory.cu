#include "util/cuPrintf.cu"
#include <stdio.h>

// Convenience function for checking CUDA runtime API results
// can be wrapped around any runtime API call. No-op in release builds.
inline
cudaError_t checkCuda(cudaError_t result)
{
#if defined(DEBUG) || defined(_DEBUG)
  if (result != cudaSuccess) {
    fprintf(stderr, "CUDA Runtime Error: %s\n", cudaGetErrorString(result));
    assert(result == cudaSuccess);
  }
#endif
  return result;
}

unsigned int nElements = 10;
const unsigned int bytes = nElements * sizeof(float);
  
// host arrays
float *host_array;

// device array
float *device_array;

__global__ void device_method(float *d_array, unsigned int n)
{
  
  cuPrintf("Device array:\n");
  for (int i = 0; i < n; ++i) {
    cuPrintf("%f ",d_array[i]);
  }
  
  modifyArray(d_array);
  
  cuPrintf("Device array after modification:\n");
  for (int i = 0; i < n; ++i) {
    cuPrintf("%f ",d_array[i]);
  }
}

__global__ void modifyArray(float *d_array)
{
  host_array[0] = -1;
    
  // Sync array
  checkCuda( cudaMemcpy(d_array, host_array, bytes, cudaMemcpyHostToDevice) );
}

int main(void)
{

  // allocate and initialize
  checkCuda( cudaMallocHost((void**)&host_array, bytes) ); // host pinned
  checkCuda( cudaMalloc((void**)&device_array, bytes) );           // device
  
  // init array
  printf("Host array:\n");
  for (int i = 0; i < nElements; ++i) {
    host_array[i] = i;
    printf("%f ",host_array[i]);
  }
  
  // Sync array
  checkCuda( cudaMemcpy(device_array, host_array, bytes, cudaMemcpyHostToDevice) );


  // initialize cuPrintf
  cudaPrintfInit();

  // launch a kernel with a single thread
  device_method<<<1,1>>>(device_array, nElements);

  // display the device's output
  cudaPrintfDisplay();
  
  // clean up after cuPrintf
  cudaPrintfEnd();

  return 0;
}
