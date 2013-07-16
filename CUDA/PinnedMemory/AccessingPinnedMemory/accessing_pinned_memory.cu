#include <stdio.h>
#include <assert.h>
#define N 4096

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

__global__ void increment_kernel(unsigned long long *cnt)
{
  atomicAdd(cnt, 1);
}

int main(int argc, char **argv)
{
  unsigned long long *cnt;

  checkCuda( cudaMallocHost((void**)&cnt, sizeof(unsigned long long)) );

  *cnt = 0;

  for (int i = 0; i < N; i++) {
    increment_kernel<<<4, 4>>>(cnt);
  }

  cudaThreadSynchronize();

  fprintf(stderr, "CNT %lu\n", *cnt);

  assert(*cnt == 16*N);
  fprintf(stderr, "CNT %lu\n", *cnt);

  return 0;
}
