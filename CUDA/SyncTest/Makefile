NVCC = nvcc

CUDA_INSTALL_PATH=/usr/local/cuda
NVCCFLAGS = -g -O2 -m64 -gencode arch=compute_35,code=\"sm_35,compute_35\" \
 -I$(CUDA_INSTALL_PATH)/include

all: SyncTest1 SyncTest2

SyncTest1: src/sync_test1.cu
		$(NVCC) $(NVCCFLAGS) $< -o bin/$@

SyncTest2: src/sync_test2.cu
		$(NVCC) $(NVCCFLAGS) $< -o bin/$@

run: SyncTest1 SyncTest2
		./bin/SyncTest1
		./bin/SyncTest2

clean:
		rm -f bin/SyncTest1
		rm -rf bin/SyncTest1.dSYM
		rm -f bin/SyncTest2
		rm -rf bin/SyncTest2.dSYM
