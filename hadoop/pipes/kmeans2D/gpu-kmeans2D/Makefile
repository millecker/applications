CC = nvcc
#CPPFLAGS = -m64 -I$(HADOOP_HOME)/../../c++/Linux-amd64-64/include
CPPFLAGS = -m64 -I$(HADOOP_HOME)/../../src/c++/install/include

gpu-kmeans2D: gpu-kmeans2D.cu
#	$(CC) $(CPPFLAGS) $< -L$(HADOOP_HOME)/../../c++/Linux-amd64-64/lib -lhadooppipes -lhadooputils -lcrypto -lpthread -g -O2 -o $@
	$(CC) $(CPPFLAGS) $< -L$(HADOOP_HOME)/../../src/c++/install/lib -lhadooppipes -lhadooputils -lcrypto -lpthread -g -O2 -o $@

clean:
	rm -f gpu-kmeans2D
