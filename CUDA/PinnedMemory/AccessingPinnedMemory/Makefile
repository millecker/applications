CC = nvcc
#CPPFLAGS = -m64 -I$(HADOOP_HOME)/../../c++/Linux-amd64-64/include
#CPPFLAGS = -m64 -I$(HADOOP_HOME)/../../src/c++/install/include

accessing_pinned_memory: accessing_pinned_memory.cu
	$(CC) $< -g -O2 -o $@ -arch=sm_20

clean:
	rm -f accessing_pinned_memory
	rm -rf accessing_pinned_memory.dSYM

