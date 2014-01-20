#include <stdio.h>

__global__ void sync_test(void)
{
  printf("a\n");

  if(threadIdx.x == 0){
    __syncthreads();
    __syncthreads();
  } else {
    __syncthreads();
  }

  printf("b\n");

  if(threadIdx.x != 0){
    printf("c\n");
    __syncthreads();
  }
}

int main(void)
{
  sync_test<<<1, 4>>>();
  cudaDeviceSynchronize();
  return 0;
}

/* prints:
a
a
a
a
b
b
b
b
c
c
c
*/

/* desired: 
a
a
a
a
b
b
b
c
c
c
b
*/
