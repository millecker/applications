#!/bin/bash

hadoop dfs -rmr output/pipes/matrixmult

hadoop dfs -rmr bin/cpu-MatrixMultiplication
hadoop dfs -put cpu-MatrixMultiplication/MatrixMultiplication bin/cpu-MatrixMultiplication

# -libjars added for SequenceFile: java.io.IOException: WritableName can't load class: de.jungblut.writable.VectorWritable
hama pipes -libjars ../../Examples/Examples.jar -conf MatrixMultiplication_job.xml -output output/pipes/matrixmult
