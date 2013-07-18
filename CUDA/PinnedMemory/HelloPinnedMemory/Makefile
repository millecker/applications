NVCC = nvcc

CUDA_INSTALL_PATH=/usr/local/cuda
NVCCFLAGS = -g -O2 -gencode arch=compute_12,code=sm_12 -gencode arch=compute_20,code=sm_20 \
 -I$(CUDA_INSTALL_PATH)/include

all: HelloPinnedMemory

HelloPinnedMemory: src/hello_pinned_memory.cu
		$(NVCC) $(NVCCFLAGS) $< -o bin/$@

run: HelloPinnedMemory
		./bin/HelloPinnedMemory
		
clean:
		rm -f bin/HelloPinnedMemory
		rm -rf bin/HelloPinnedMemory.dSYM
