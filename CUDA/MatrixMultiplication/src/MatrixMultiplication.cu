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

__global__ void my_matrixmult(float *d_matrixA, float *d_matrixB,
		float *d_matrixC, int n, int m, int threadSliceSize,
		int blockSliceSize) {

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
		float multipliers[100][100]; //[blockSliceSize][threadSliceSize];
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
		float matrixAColumns[10][1024]; //[threadSliceSize][matrixARowSize]
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

				float sum = 0;
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

/**
 * Matrix multiplication (CUDA Kernel) on the device: C = A * B
 * wA is A's width and wB is B's width
 */
template<int BLOCK_SIZE> __global__ void matrixMulCUDA(float *C, float *A,
		float *B, int wA, int wB, int blocksXSize, int blocksYSize,
		int gridXSize, int gridYSize) {

	// Thread index
	int tx = threadIdx.x / blocksXSize;
	int ty = threadIdx.x % blocksYSize;

	// Block index
	int bx = blockIdx.x / gridXSize;
	int by = blockIdx.x % gridYSize;

	int thread_id = threadIdx.x + (blockIdx.x * blockDim.x);

	cuPrintf(
			"matrixMulCUDA started. tx: %d, ty: %d, bx: %d, by: %d thread_id: %d\n",
			tx, ty, bx, by, thread_id);

	// Index of the first sub-matrix of A processed by the block
	int aBegin = wA * BLOCK_SIZE * by;

	// Index of the last sub-matrix of A processed by the block
	int aEnd = aBegin + wA - 1;

	// Step size used to iterate through the sub-matrices of A
	int aStep = BLOCK_SIZE;

	// Index of the first sub-matrix of B processed by the block
	int bBegin = BLOCK_SIZE * bx;

	// Step size used to iterate through the sub-matrices of B
	int bStep = BLOCK_SIZE * wB;

	// Csub is used to store the element of the block sub-matrix
	// that is computed by the thread
	float Csub = 0;

	// Loop over all the sub-matrices of A and B
	// required to compute the block sub-matrix
	for (int a = aBegin, b = bBegin; a <= aEnd; a += aStep, b += bStep) {

		// Declaration of the shared memory array As used to
		// store the sub-matrix of A
		__shared__ float As[BLOCK_SIZE][BLOCK_SIZE];

		// Declaration of the shared memory array Bs used to
		// store the sub-matrix of B
		__shared__ float Bs[BLOCK_SIZE][BLOCK_SIZE];

		// Load the matrices from device memory
		// to shared memory; each thread loads
		// one element of each matrix
		As[ty][tx] = A[a + wA * ty + tx];
		Bs[ty][tx] = B[b + wB * ty + tx];

		// Synchronize to make sure the matrices are loaded
		__syncthreads();

		// Multiply the two matrices together;
		// each thread computes one element
		// of the block sub-matrix
#pragma unroll

		for (int k = 0; k < BLOCK_SIZE; ++k) {
			Csub += As[ty][k] * Bs[k][tx];
		}

		// Synchronize to make sure that the preceding
		// computation is done before loading two new
		// sub-matrices of A and B in the next iteration
		__syncthreads();
	}

	// Write the block sub-matrix to device memory;
	// each thread writes one element
	int c = wB * BLOCK_SIZE * by + BLOCK_SIZE * bx;
	C[c + wB * ty + tx] = Csub;
}

/**
 * Host functions
 */

int divup(int x, int y) {
	if (x % y != 0) {
		// aufrunden
		return ((x + y - 1) / y);
	} else {
		return x / y;
	}
}

void constantInit(float *data, int n, int m, float value) {
	for (int j = 0; j < n; ++j) {
		for (int i = 0; i < m; ++i) {
			data[(j * m) + i] = value;
		}
	}
}

void randomInit(float *data, int n, int m, unsigned int seed) {
	/* initialize random seed: */
	srand(seed);

	for (int j = 0; j < n; ++j) {
		for (int i = 0; i < m; ++i) {
			data[(j * m) + i] = rand() % 10 + 1; // between 1 and 10
		}
	}
}

void printArr(float *data, int n, int m) {
	for (int j = 0; j < n; ++j) {
		for (int i = 0; i < m; ++i) {
			if (i == m - 1) {
				printf("%4.1f]\n", data[(j * m) + i]);
			} else if (i == 0) {
				printf("[%4.1f,", data[(j * m) + i]);
			} else {
				printf("%4.1f,", data[(j * m) + i]);
			}
		}
	}
}

void multiply(float *matrixA, float *matrixB, float *matrixC, int a_rows,
		int a_cols, int b_cols) {

	for (int k = 0; k < a_cols; k++) {
		for (int i = 0; i < a_rows; i++) {
			for (int j = 0; j < b_cols; j++) {
				matrixC[i * b_cols + j] += matrixA[i * b_cols + k]
						* matrixB[k * a_rows + j];
			}
		}
	}
}

bool verify(float *matrixA, float *matrixB, int n, int m) {
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

	int matrix_block_size = 32;
	int n = 4 * 4 *  matrix_block_size;
	if (argc > 1) {
		printf("Argument: %s\n", argv[1]);
		n = atoi(argv[1]);
		if (n < 1) {
			return 1;
		}
	}

	// setup GPU parameters

	// dim3 blockSize(1024); // 1024
	// dim3 gridSize(14); // 14
	dim3 threads(matrix_block_size, matrix_block_size);
	dim3 grid(n / matrix_block_size, n / matrix_block_size);

	printf("Threads.x: %d, Threads.y: %d, Blocks.x: %d, Blocks.x: %d\n",
			threads.x, threads.y, grid.x, grid.y);

	// set to pinned memory
	checkCuda (cudaSetDeviceFlags(cudaDeviceMapHost));

	// Allocate matrixA
	//int matrixARowSize = n;
	//int matrixAColSize = n;
	float *h_matrixA = NULL;
	float *d_matrixA = NULL;
	checkCuda(
			cudaHostAlloc(&h_matrixA, sizeof(float) * n * n,
					cudaHostAllocWriteCombined | cudaHostAllocMapped));

	// Allocate matrixB
	int matrixBRowSize = n;
	int matrixBColSize = n;
	float *h_matrixB = NULL;
	float *d_matrixB = NULL;
	checkCuda(
			cudaHostAlloc(&h_matrixB, sizeof(float) * n * n,
					cudaHostAllocWriteCombined | cudaHostAllocMapped));

	// Allocate matrixC
	float *h_matrixC = NULL;
	float *d_matrixC = NULL;
	checkCuda(
			cudaHostAlloc(&h_matrixC, sizeof(float) * n * n,
					cudaHostAllocWriteCombined | cudaHostAllocMapped));

	// {{7,1,2,2},{3,9,2,1},{6,4,5,4},{8,5,7,3}}
	randomInit(h_matrixA, n, n, 42L); // time(NULL)
	// {{2,3,8,8},{5,3,2,3},{9,6,8,6},{5,2,1,4}}
	randomInit(h_matrixB, n, n, 1337L); // time(NULL)
	// http://www.wolframalpha.com/input/?i=matrix+multiplication+calculator&f1={{7%2C1%2C2%2C2}%2C{3%2C9%2C2%2C1}%2C{6%2C4%2C5%2C4}%2C{8%2C5%2C7%2C3}}&f=MatricesOperations.theMatrix1_{{7%2C1%2C2%2C2}%2C{3%2C9%2C2%2C1}%2C{6%2C4%2C5%2C4}%2C{8%2C5%2C7%2C3}}&f2={{2%2C3%2C8%2C8}%2C{5%2C3%2C2%2C3}%2C{9%2C6%2C8%2C6}%2C{5%2C2%2C1%2C4}}&f=MatricesOperations.theMatrix2_{{2%2C3%2C8%2C8}%2C{5%2C3%2C2%2C3}%2C{9%2C6%2C8%2C6}%2C{5%2C2%2C1%2C4}}&a=*FVarOpt.1-_**-.***MatricesOperations.theMatrix3---.*--
	constantInit(h_matrixC, n, n, 0); // time(NULL)

	printf("n: %d\n", n);

	if (DEBUG) {
		printf("matrixA:\n");
		printArr(h_matrixA, n, n);
		printf("matrixB:\n");
		printArr(h_matrixB, n, n);
		printf("matrixC:\n");
		printArr(h_matrixC, n, n);
	}

	// threadSliceSize defines how much multipliers
	// of column B has to be multiplied with column A
	int threadSliceSize = divup(matrixBRowSize, threads.x);

	// blockSliceSize defines the column slice amount
	// columns of B per blockIters
	int blockSliceSize = divup(matrixBColSize, grid.x);
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

	//my_matrixmult<<<14, 1024, 1024>>>(d_matrixA, d_matrixB,
	//		d_matrixC, n, n, threadSliceSize, blockSliceSize);

	int blockSize = threads.x * threads.y;
	int gridSize = grid.x * grid.y;

	printf("blockSize: %d, gridSize: %d\n", blockSize, gridSize);

	matrixMulCUDA<32> <<<gridSize, blockSize>>>(d_matrixC, d_matrixA, d_matrixB,
			n, n, threads.x, threads.y, grid.x, grid.y);

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
	float *matrixD = (float*) malloc(sizeof(float) * n * n);
	constantInit(matrixD, n, n, 0);

	multiply(h_matrixA, h_matrixB, matrixD, n, n, n);

	bool verifyResult = verify(h_matrixC, matrixD, n, n);
	if (verifyResult) {
		printf("Verify PASSED!\n");
	} else {
		printf("Verify FAILED!\n");
	}
	/*
	 printf("Checking computed result for correctness: ");
	 bool correct = true;

	 // test relative error by the formula
	 //     |<x, y>_cpu - <x,y>_gpu|/<|x|, |y|>  < eps
	 double eps = 1.e-6 ; // machine zero
	 for (int i = 0; i < (int)(dimsC.x * dimsC.y); i++)
	 {
	 double abs_err = fabs(h_C[i] - (dimsA.x * valB));
	 double dot_length = dimsA.x;
	 double abs_val = fabs(h_C[i]);
	 double rel_err = abs_err/abs_val/dot_length ;
	 if (rel_err > eps)
	 {
	 printf("Error! Matrix[%05d]=%.8f, ref=%.8f error term is > %E\n", i, h_C[i], dimsA.x*valB, eps);
	 correct = false;
	 }
	 }

	 printf("%s\n", correct ? "Result = PASS" : "Result = FAIL");
	 */

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
