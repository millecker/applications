NVCC = nvcc
NVCCFLAGS = -g -O2 -m64 \
 -gencode arch=compute_20,code=sm_20 \
 -gencode arch=compute_35,code=sm_35 
#-gencode arch=compute_12,code=sm_12 
 
CXX=g++
CUDA_INSTALL_PATH=/usr/local/cuda
CFLAGS= -I$(CUDA_INSTALL_PATH)/include
LDFLAGS= -L$(CUDA_INSTALL_PATH)/lib -lcuda -lcudart 

all: PiEstimator PiEstimatorOptimized

PiEstimator: src/piestimator.cu
#		$(CXX) $(CFLAGS) $^ $(LDFLAGS) -o bin/$@
#		$(NVCC) $(NVCCFLAGS) -link $^ -o bin/$@
		$(NVCC) $(NVCCFLAGS) $^ -o bin/$@

PiEstimatorOptimized: src/piestimator_optimized.cu
		$(NVCC) $(NVCCFLAGS) $^ -o bin/$@


run: PiEstimator
		./bin/PiEstimator

clean:
		rm -f bin/PiEstimator
		rm -rf bin/PiEstimator.dSYM
		rm -f bin/PiEstimatorOptimized
		rm -rf bin/PiEstimatorOptimized.dSYM
