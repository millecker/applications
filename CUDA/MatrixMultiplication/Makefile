NVCC = nvcc
NVCCFLAGS = -g -O2 -m64 \
 -gencode arch=compute_20,code=sm_20 \
 -gencode arch=compute_35,code=sm_35 
#-gencode arch=compute_12,code=sm_12 
 
CXX=g++
CUDA_INSTALL_PATH=/usr/local/cuda
CFLAGS= -I$(CUDA_INSTALL_PATH)/include
LDFLAGS= -L$(CUDA_INSTALL_PATH)/lib -lcuda -lcudart 

all: MatrixMultiplication

MatrixMultiplication: src/MatrixMultiplication.cu
#		$(CXX) $(CFLAGS) $^ $(LDFLAGS) -o bin/$@
#		$(NVCC) $(NVCCFLAGS) -link $^ -o bin/$@
		$(NVCC) $(NVCCFLAGS) $^ -o bin/$@

run: MatrixMultiplication
		./bin/MatrixMultiplication

clean:
		rm -f bin/MatrixMultiplication
		rm -rf bin/MatrixMultiplication.dSYM
