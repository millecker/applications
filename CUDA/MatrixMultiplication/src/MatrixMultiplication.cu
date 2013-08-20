/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#include <stdio.h>      /* printf, scanf, puts, NULL */
#include <stdlib.h>     /* srand, rand */
#include <time.h>       /* time */
#include <assert.h>
#include <cuda_runtime.h>
#include "util/cuPrintf.cu"

// Convenience function for checking CUDA runtime API results
// can be wrapped around any runtime API call. No-op in release builds.
inline cudaError_t checkCuda(cudaError_t result) {
	if (result != cudaSuccess) {
		fprintf(stderr, "CUDA Runtime Error: %s\n", cudaGetErrorString(result));
		assert(result == cudaSuccess);
	}
	return result;
}

/**
 * Device functions
 */

__global__ void device_method(int *d_matrixA, int *d_matrixB, int *d_matrixC,
		int n, int m, int threadSliceSize, int blockSliceSize) {

	extern __shared__ int intermediateSums[];

	unsigned block_idxx = blockIdx.x;
	int thread_idxx = threadIdx.x;

	int matrixARowSize = n;
	int matrixAColSize = m;
	int matrixBRowSize = m;
	int matrixBColSize = n;

	// Check for wrong matrix sizes
	if (matrixAColSize != matrixBRowSize) {
		return;
	}

	// intermediateSums[thread_idxx] = 0;
	// syncthreads();

	// Check if thread and block is in matrix range
	if ((block_idxx < matrixBColSize * blockSliceSize)
			&& (thread_idxx < matrixBRowSize * threadSliceSize)) {

		cuPrintf("[%d,%d]device_method started. thread_id: %d, block_id: %d\n",
				thread_idxx, block_idxx, thread_idxx, block_idxx);

		// Setup multipliers of matrix B (slized Matrix)
		int multipliers[100][100]; //[blockSliceSize][threadSliceSize];
		for (int k = 0; k < blockSliceSize; k++) {
			for (int j = 0; j < threadSliceSize; j++) {

				if ((k + (blockSliceSize * block_idxx)) < matrixBColSize) {
					multipliers[k][j] = d_matrixB[((thread_idxx
							* threadSliceSize + j) * matrixAColSize)
							+ (block_idxx * blockSliceSize) + k];
				} else {
					multipliers[k][j] = 0;
				}

				cuPrintf("[%d,%d]multipliers[%d][%d]: %d\n", thread_idxx,
						block_idxx, k, j, multipliers[k][j]);
			}
		}

		// Setup columns of matrix A
		int matrixAColumns[10][1024]; //[threadSliceSize][matrixARowSize]
		for (int k = 0; k < threadSliceSize; k++) {
			for (int i = 0; i < matrixARowSize; i++) {
				matrixAColumns[k][i] = d_matrixA[(i * matrixAColSize)
						+ (thread_idxx * threadSliceSize) + k];

				cuPrintf("[%d,%d]matrixAColumns setup[%d][%d]: %d\n",
						thread_idxx, block_idxx, k, i, matrixAColumns[k][i]);
			}
		}

		// Calculate scalar multiplication
		for (int k = 0; k < blockSliceSize; k++) {
			for (int i = 0; i < matrixARowSize; i++) {

				int sum = 0;
				for (int j = 0; j < threadSliceSize; j++) {

					cuPrintf("[%d,%d]matrixAColumns read[%d][%d]: %d\n",
							thread_idxx, block_idxx, j, i,
							matrixAColumns[j][i]);
					cuPrintf("[%d,%d]multipliers[%d][%d]: %d\n", thread_idxx,
							block_idxx, k, j, multipliers[k][j]);

					sum += matrixAColumns[j][i] * multipliers[k][j];
				}

				cuPrintf("[%d,%d]sum: %d ,matrixARow: %d\n", thread_idxx,
						block_idxx, sum, i);

				intermediateSums[thread_idxx] = sum;

				syncthreads();

				// do reduction in shared memory
				// 1-bit right shift = divide by two to the power 1
				/*
				 for (int s = matrixARowSize / 2; s > 0; s >>= 1) {

				 if (thread_idxx < s) {
				 intermediateSums[thread_idxx] +=
				 intermediateSums[thread_idxx + s];
				 }
				 syncthreads();
				 }*/

				if (thread_idxx == 0) {

					for (int t = 1; t < matrixARowSize; t++) {
						sum += intermediateSums[t];
					}

					cuPrintf(
							"[%d,%d]final sum: %d (i:%d,k:%d,blockSliceSize:%d,threadSliceSize:%d)\n",
							thread_idxx, block_idxx, sum, i, k, blockSliceSize,
							threadSliceSize);

					if (sum != 0) {
						d_matrixC[(i * matrixARowSize)
								+ (blockSliceSize * block_idxx) + k] = sum;

					}
				}
			}
		}
	}
}

int divup(int x, int y) {
	if (x % y != 0) {
		// aufrunden
		return ((x + y - 1) / y);
	} else {
		return x / y;
	}
}

void constantInit(int *data, int n, int m, int value) {
	for (int j = 0; j < n; ++j) {
		for (int i = 0; i < m; ++i) {
			data[(j * m) + i] = value;
		}
	}
}

void randomInit(int *data, int n, int m, unsigned int seed) {
	/* initialize random seed: */
	srand(seed);

	for (int j = 0; j < n; ++j) {
		for (int i = 0; i < m; ++i) {
			data[(j * m) + i] = rand() % 10 + 1; // between 1 and 10
		}
	}
}

void printArr(int *data, int n, int m) {
	for (int j = 0; j < n; ++j) {
		for (int i = 0; i < m; ++i) {
			if (i == m - 1) {
				printf("%d]\n", data[(j * m) + i]);
			} else if (i == 0) {
				printf("[%d,", data[(j * m) + i]);
			} else {
				printf("%d,", data[(j * m) + i]);
			}
		}
	}
}

void multiply(int *matrixA, int *matrixB, int *matrixC, int a_rows, int a_cols,
		int b_cols) {

	for (int k = 0; k < a_cols; k++) {
		for (int i = 0; i < a_rows; i++) {
			for (int j = 0; j < b_cols; j++) {
				matrixC[i * b_cols + j] += matrixA[i * b_cols + k]
						* matrixB[k * a_rows + j];
			}
		}
	}
}

bool verify(int *matrixA, int *matrixB, int n, int m) {
	for (int j = 0; j < n; ++j) {
		for (int i = 0; i < m; ++i) {
			if (matrixA[j * m + i] != matrixB[j * m + i]) {
				printf("Verify ERROR at [%d,%d]\n", j, i);
				return false;
			}
		}
	}
	return true;
}

/**
 * Host function
 */
int main(int argc, char* argv[]) {

	bool DEBUG = false;

	// Fetch available GPU memory size to fix the samples count.
	cudaDeviceProp devProp;
	checkCuda(cudaGetDeviceProperties(&devProp, 0));

	//unsigned maxbytes = devProp.totalGlobalMem;
	//max samples depending on hints and curandStates array
	//unsigned n = maxbytes / (3*sizeof(double));

	int n = 250;
	if (argc > 1) {
		printf("Argument: %s\n", argv[1]);
		n = atoi(argv[1]);
		if (n < 1) {
			return 1;
		}
	}
	//if (n > max_samples) {
	//	n = max_samples;
	//}

	// set to pinned memory
	checkCuda (cudaSetDeviceFlags(cudaDeviceMapHost));

	// Allocate matrixA
	//int matrixARowSize = n;
	//int matrixAColSize = n;
	int	*h_matrixA = NULL;
	int *d_matrixA = NULL;
	checkCuda(
			cudaHostAlloc(&h_matrixA, sizeof(int) * n * n,
					cudaHostAllocWriteCombined | cudaHostAllocMapped));

	// Allocate matrixB
	int matrixBRowSize = n;
	int matrixBColSize = n;
	int *h_matrixB = NULL;
	int *d_matrixB = NULL;
	checkCuda(
			cudaHostAlloc(&h_matrixB, sizeof(int) * n * n,
					cudaHostAllocWriteCombined | cudaHostAllocMapped));

	// Allocate matrixC
	int *h_matrixC = NULL;
	int *d_matrixC = NULL;
	checkCuda(
			cudaHostAlloc(&h_matrixC, sizeof(int) * n * n,
					cudaHostAllocWriteCombined | cudaHostAllocMapped));

	// {{7,1,2,2},{3,9,2,1},{6,4,5,4},{8,5,7,3}}
	randomInit(h_matrixA, n, n, 42L); // time(NULL)
	// {{2,3,8,8},{5,3,2,3},{9,6,8,6},{5,2,1,4}}
	randomInit(h_matrixB, n, n, 1337L); // time(NULL)
	// http://www.wolframalpha.com/input/?i=matrix+multiplication+calculator&f1={{7%2C1%2C2%2C2}%2C{3%2C9%2C2%2C1}%2C{6%2C4%2C5%2C4}%2C{8%2C5%2C7%2C3}}&f=MatricesOperations.theMatrix1_{{7%2C1%2C2%2C2}%2C{3%2C9%2C2%2C1}%2C{6%2C4%2C5%2C4}%2C{8%2C5%2C7%2C3}}&f2={{2%2C3%2C8%2C8}%2C{5%2C3%2C2%2C3}%2C{9%2C6%2C8%2C6}%2C{5%2C2%2C1%2C4}}&f=MatricesOperations.theMatrix2_{{2%2C3%2C8%2C8}%2C{5%2C3%2C2%2C3}%2C{9%2C6%2C8%2C6}%2C{5%2C2%2C1%2C4}}&a=*FVarOpt.1-_**-.***MatricesOperations.theMatrix3---.*--
	constantInit(h_matrixC, n, n, 0); // time(NULL)

	if (DEBUG) {
		printf("matrixA:\n");
		printArr(h_matrixA, n, n);
		printf("matrixB:\n");
		printArr(h_matrixB, n, n);
		printf("matrixC:\n");
		printArr(h_matrixC, n, n);
	}

	// setup GPU parameters
	dim3 threads(1024); // 1024
	dim3 blocks(14); // 14
	printf("Threads.x: %d, Blocks.x: %d\n", threads.x, blocks.x);

	// threadSliceSize defines how much multipliers
	// of column B has to be multiplied with column A
	int threadSliceSize = divup(matrixBRowSize, threads.x);

	// blockSliceSize defines the column slice amount
	// columns of B per blockIters
	int blockSliceSize = divup(matrixBColSize, blocks.x);
	printf("threadSliceSize: %d, blockSliceSize: %d\n", threadSliceSize,
			blockSliceSize);

	checkCuda(cudaHostGetDevicePointer(&d_matrixA, h_matrixA, 0));
	checkCuda(cudaHostGetDevicePointer(&d_matrixB, h_matrixB, 0));
	checkCuda(cudaHostGetDevicePointer(&d_matrixC, h_matrixC, 0));

	// create cuda event handles
	cudaEvent_t gpu_start, gpu_stop;
	float gpu_time = 0.0f;

	checkCuda(cudaEventCreate(&gpu_start));
	checkCuda(cudaEventCreate(&gpu_stop));
	cudaPrintfInit();

	printf("Run computation on GPU\n");
	cudaEventRecord(gpu_start, 0);
	device_method<<<blocks, threads, threads.x>>>(d_matrixA, d_matrixB,
			d_matrixC, n, n, threadSliceSize, blockSliceSize);
	cudaEventRecord(gpu_stop, 0);

	checkCuda(cudaDeviceSynchronize());
	checkCuda(cudaGetLastError());

	if (DEBUG) {
		cudaPrintfDisplay();
	}
	cudaPrintfEnd();

	checkCuda(cudaEventElapsedTime(&gpu_time, gpu_start, gpu_stop));
	printf("gpu computation time: %.2f ms\n", gpu_time);

	//verify
	int *matrixD = (int*) malloc(sizeof(int) * n * n);
	constantInit(matrixD, n, n, 0);
	multiply(h_matrixA, h_matrixB, matrixD, n, n, n);
	bool verifyResult = verify(h_matrixC, matrixD, n, n);
	if (verifyResult) {
		printf("Verify PASSED!\n");
	} else {
		printf("Verify FAILED!\n");
	}

	if (DEBUG) {
		printf("matrixC:\n");
		printArr(h_matrixC, n, n);
		printf("matrixD:\n");
		printArr(matrixD, n, n);
	}
	checkCuda(cudaFreeHost(d_matrixA));
	checkCuda(cudaFreeHost(d_matrixB));
	checkCuda(cudaFreeHost(d_matrixC));

	return 0;
}
