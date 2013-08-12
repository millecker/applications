NVCC = nvcc

CUDA_INSTALL_PATH=/usr/local/cuda
NVCCFLAGS = -g -O2 \
 -gencode arch=compute_12,code=sm_12 -gencode arch=compute_20,code=sm_20 -gencode arch=compute_35,code=sm_35 \
 -I$(CUDA_INSTALL_PATH)/include

all: AccessingPinnedMemory

AccessingPinnedMemory: src/accessing_pinned_memory.cu
		$(NVCC) $(NVCCFLAGS) $< -o bin/$@

run: AccessingPinnedMemory
		./bin/AccessingPinnedMemory
		
clean:
		rm -f bin/AccessingPinnedMemory
		rm -rf bin/AccessingPinnedMemory.dSYM
