NVCC = nvcc

CUDA_INSTALL_PATH=/usr/local/cuda
NVCCFLAGS = -g -O2 -gencode arch=compute_20,code=sm_20 -gencode arch=compute_20,code=sm_20 \
 -I$(CUDA_INSTALL_PATH)/include

all: SyncTest

SyncTest: src/sync_test.cu
		$(NVCC) $(NVCCFLAGS) $< -o bin/$@

run: SyncTest
		./bin/SyncTest

clean:
		rm -f bin/SyncTest
		rm -rf bin/SyncTest.dSYM
