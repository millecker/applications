CC = g++
CPPFLAGS = -m64 -I$(HAMA_HOME)/../../../../c++/install/include -g
#CPPFLAGS = -m64 -I/home/bafu/workspace/hama-0.5.0-gpu/c++/install/include
LDFLAGS = -L$(HAMA_HOME)/../../../../c++/install/lib -lhamapipes -lhadooputils -lcrypto -lpthread -O2
#LDFLAGS = -L/home/bafu/workspace/hama-0.5.0-gpu/c++/install/lib -lhamapipes -lhadooputils -lcrypto -lpthread -O2

all: MatrixMultiplication

DenseDoubleVector.o: DenseDoubleVector.cc
	$(CC) $(CPPFLAGS) -c $<

MatrixMultiplication.o: MatrixMultiplication.cc DenseDoubleVector.hh 
	$(CC) $(CPPFLAGS) -c $< 

OBJ = MatrixMultiplication.o DenseDoubleVector.o 

MatrixMultiplication: $(OBJ)
	$(CC) $(CPPFLAGS) -o $@ $(OBJ) $(LDFLAGS)

clean:
	rm -f MatrixMultiplication
	rm -f *.o
