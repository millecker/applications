#!/bin/bash

clear

# Delete hdfs directories
hadoop fs -rmr /examples/input/matrixmultiplication
hadoop fs -rmr /examples/output/matrixmultiplication
hadoop fs -rmr /examples/bin/cpu_matrixmultiplication

# Generate input data
hama jar $HAMA_HOME/hama-examples-*.jar \
  gen vectorwritablematrix 4 4 \
  /examples/input/matrixmultiplication/MatrixA.seq \
  false true 0 10 0

hama jar $HAMA_HOME/hama-examples-*.jar \
  gen vectorwritablematrix 4 4 \
  /examples/input/matrixmultiplication/MatrixB_transposed.seq \
  false true 0 10 0

# Compile and upload binary
cd cpu-MatrixMultiplication
make clean && make
hadoop fs -put MatrixMultiplication /examples/bin/cpu_matrixmultiplication
cd ..

# Run matrixmultiplication example
hama pipes -conf MatrixMultiplication_job.xml \
 -output /examples/output/matrixmultiplication

# Print output
hama seqdumper -file /examples/input/matrixmultiplication/MatrixA.seq
hama seqdumper -file /examples/input/matrixmultiplication/MatrixB_transposed.seq
hama seqdumper -file /examples/output/matrixmultiplication/part-00001
