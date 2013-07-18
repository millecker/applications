NVCC = nvcc

CUDA_INSTALL_PATH=/usr/local/cuda
NVCCFLAGS = -g -O2 -gencode arch=compute_12,code=sm_12 -gencode arch=compute_20,code=sm_20 \
 -I$(CUDA_INSTALL_PATH)/include -I$(CUDA_INSTALL_PATH)/samples/common/inc

all: AsyncAPI

AsyncAPI: src/asyncAPI.cu
		$(NVCC) $(NVCCFLAGS) $< -o bin/$@

run: AsyncAPI
		./bin/AsyncAPI
		
clean:
		rm -f bin/AsyncAPI
		rm -rf bin/AsyncAPI.dSYM
