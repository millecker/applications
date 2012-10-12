#!/bin/bash

hadoop dfs -rmr output/pipes/matrixmult
hadoop dfs -rmr bin/cpu_MatrixMultiplication

cd cpu-MatrixMultiplication
make clean && make
cd ..

hadoop dfs -put cpu-MatrixMultiplication/MatrixMultiplication bin/cpu_MatrixMultiplication

# create Example Jar
oldDir=`pwd`

cd ../../Examples/bin
jar cfm ../Examples.jar ../Manifest.txt `find . -not -path "*/.svn/*" -not -type d`
cd ..
jar uf Examples.jar `find lib -not -path "*/.svn/*" -not -type d`

cd $oldDir

# -libjars added for SequenceFile: java.io.IOException: WritableName can't load class: de.jungblut.writable.VectorWritable
hama pipes -libjars ../../Examples/Examples.jar,../../Examples/lib/tjungblut-math-1.0.jar -conf MatrixMultiplication_job.xml -output output/pipes/matrixmult
