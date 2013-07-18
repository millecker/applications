CXX=g++

CUDA_INSTALL_PATH=/usr/local/cuda
CFLAGS= -I. -I$(CUDA_INSTALL_PATH)/include -I../Nvidia_OpenCL_SDK_4_2_Linux/shared/inc
LDFLAGS= -L$(CUDA_INSTALL_PATH)/lib ../Nvidia_OpenCL_SDK_4_2_Linux/shared/lib/libshrutil_x86_64.a -lcuda -lcudart 
#CUDAFLAGS=  -lcuda

all: DeviceInfo

DeviceInfo: src/CUDADeviceInfo.cpp
		$(MAKE) -C ../Nvidia_OpenCL_SDK_4_2_Linux/shared
		$(CXX) $(CFLAGS) $< $(LDFLAGS) -g -O2 -o bin/$@
#		nvcc $(CUDAFLAGS) -c $@.o -o $@

run: DeviceInfo
		./bin/DeviceInfo

clean:
		rm -f bin/DeviceInfo
		rm -rf bin/DeviceInfo.dSYM
		rm -f deviceQuery.txt
		rm -f SdkMasterLog.csv
		$(MAKE) -C ../Nvidia_OpenCL_SDK_4_2_Linux/shared clean
