NVCC = nvcc

CUDA_INSTALL_PATH=/usr/local/cuda
NVCCFLAGS = -g -O2 -dc -gencode arch=compute_20,code=sm_20 \
 -I$(CUDA_INSTALL_PATH)/include

all: PassingObjectToKernel

PassingObjectToKernel: src/passing_object_to_kernel.cu
		$(NVCC) $(NVCCFLAGS) $< -o bin/$@

run: PassingObjectToKernel
		./bin/PassingObjectToKernel
		
clean:
		rm -f bin/PassingObjectToKernel
		rm -rf bin/PassingObjectToKernel.dSYM
