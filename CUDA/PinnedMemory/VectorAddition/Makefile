NVCC = nvcc

CUDA_INSTALL_PATH=/usr/local/cuda
NVCCFLAGS = -g -O2 -gencode arch=compute_12,code=sm_12 -gencode arch=compute_20,code=sm_20 \
 -I$(CUDA_INSTALL_PATH)/include

all: VectorAddition

VectorAddition: src/VectorAddition.cu
		$(NVCC) $(NVCCFLAGS) $< -o bin/$@

run: VectorAddition
		./bin/VectorAddition
		
clean:
		rm -f bin/VectorAddition
		rm -rf bin/VectorAddition.dSYM
