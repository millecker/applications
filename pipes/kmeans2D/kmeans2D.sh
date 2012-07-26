#!/bin/bash

if [ $# -ne 1 ]; then
  echo "specify input file." 
  exit 1
fi

hadoop dfs -rmr output

hadoop pipes -D hadoop.pipes.java.recordreader=true\
 -D hadoop.pipes.java.recordwriter=true\
 -output output\
 -cpubin bin/cpu-kmeans2D\
 -gpubin bin/cpu-kmeans2D\
 -input $1
