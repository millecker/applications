#!/bin/bash

hadoop dfs -rmr output/pipes/matrixmult

hadoop dfs -rmr bin/cpu-MatrixMultiplication
hadoop dfs -put cpu-MatrixMultiplication/cpu-MatrixMultiplication bin/cpu-MatrixMultiplication

hama pipes -conf MatrixMultiplication_job.xml -output output/pipes/matrixmult
