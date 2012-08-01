CC = g++
CPPFLAGS = -m64 -I$(HAMA_HOME)/../../src/c++/install/include

cpu-wordcount: cpu-PiCalculation.cc
	$(CC) $(CPPFLAGS) $< -L$(HAMA_HOME)/../../src/c++/install/lib -lhadooppipes -lhadooputils -lcrypto -lpthread -g -O2 -o $@

clean:
	rm -f cpu-PiCalculation
