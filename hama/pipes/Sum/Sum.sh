#!/bin/bash
#
#if [ $# -ne 1 ]; then
#  echo "specify input file." 
#  exit 1
#fi

#$1

hadoop dfs -rmr output/Sum
hadoop dfs -rmr bin/cpu-Sum

cd cpu-Sum
make clean && make
cd ..

hadoop dfs -put cpu-Sum/cpu-Sum bin/cpu-Sum

hadoop dfs -rmr input/Sum
hadoop dfs -mkdir input/Sum
hadoop dfs -put input/* input/Sum

hama pipes -conf Sum_job.xml -input input/Sum -output output/Sum
