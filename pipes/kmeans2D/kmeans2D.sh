#!/bin/bash
#
#if [ $# -ne 1 ]; then
#  echo "specify input file." 
#  exit 1
#fi

#$1

hadoop dfs -rmr output/kmeans2D

hadoop dfs -rmr bin/kmeans2D
hadoop dfs -put cpu-kmeans2D/cpu-kmeans2D bin/kmeans2D/cpu-kmeans2D
hadoop dfs -put gpu-kmeans2D/gpu-kmeans2D bin/kmeans2D/gpu-kmeans2D

hadoop dfs -rmr input/kmeans2D
hadoop dfs -mkdir input/kmeans2D
hadoop dfs -put kmeans-input2D/ik2_128_512_10 input/kmeans2D

hadoop pipes -conf kmeans2D_job.xml -D mapred.child.env="LD_LIBRARY_PATH=$LD_LIBRARY_PATH" -input input/kmeans2D -output output/kmeans2D

