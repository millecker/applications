#!/bin/bash

hadoop fs -rmr output/matrixmult
hadoop fs -rmr bin/cpu_MatrixMultiplication

cd cpu-MatrixMultiplication
make clean && make
cd ..

hadoop fs -put cpu-MatrixMultiplication/MatrixMultiplication bin/cpu_MatrixMultiplication

hadoop fs -rmr input/matrixmult
hadoop fs -mkdir input/matrixmult
hadoop fs -put input/* input/matrixmult

hama pipes -conf MatrixMultiplication_job.xml -output output/pipes/matrixmult

hama seqdumper -seqFile input/matrixmult/matrixA_10x10.seq

hama seqdumper -seqFile input/matrixmult/transposedMatrixB_10x10.seq

hama seqdumper -seqFile output/pipes/matrixmult/part-00000