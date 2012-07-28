#!/bin/bash
#
#if [ $# -ne 1 ]; then
#  echo "specify input file." 
#  exit 1
#fi

#$1

hadoop dfs -rmr output/cpu-wordcount

hadoop dfs -rmr bin/cpu-wordcount
hadoop dfs -put cpu-wordcount/cpu-wordcount bin/cpu-wordcount

hadoop dfs -rmr input/cpu-wordcount
hadoop dfs -mkdir input/cpu-wordcount
hadoop dfs -put input/* input/cpu-wordcount

hadoop pipes -conf wordcount_job.xml -input input/cpu-wordcount -output output/cpu-wordcount
