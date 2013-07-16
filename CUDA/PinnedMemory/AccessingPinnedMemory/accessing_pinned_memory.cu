#include <stdio.h>
#include <assert.h>
#define N 4096

__global__ void increment_kernel(unsigned long long *cnt)
{
  //atomicAdd(cnt, 1);
  *cnt++;
}

int main(int argc, char **argv)
{
  unsigned long long *cnt;

  cudaMallocHost(&cnt, sizeof(unsigned long long));

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
