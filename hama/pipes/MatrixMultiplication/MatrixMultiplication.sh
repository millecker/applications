#!/bin/bash

hadoop dfs -rmr output/matrixmult
hadoop dfs -rmr bin/cpu_MatrixMultiplication

cd cpu-MatrixMultiplication
make clean && make
cd ..

hadoop dfs -put cpu-MatrixMultiplication/MatrixMultiplication bin/cpu_MatrixMultiplication

hadoop dfs -rmr input/matrixmult
hadoop dfs -mkdir input/matrixmult
hadoop dfs -put input/* input/matrixmult

hama pipes -conf MatrixMultiplication_job.xml -output output/pipes/matrixmult

#hama seqdumper -seqFile input/matrixmult/matrixA.seq
