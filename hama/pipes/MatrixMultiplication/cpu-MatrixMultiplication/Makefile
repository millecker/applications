CC = g++
CPPFLAGS = -m64 -I$(HAMA_HOME)/../../../../c++/src/main/native/utils/api -I$(HAMA_HOME)/../../../../c++/src/main/native/pipes/api
LDFLAGS = -L$(HAMA_HOME)/../../../../c++/target/native -lhadooputils -lcrypto -lpthread -g -Wall -O2

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
