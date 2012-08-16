#!/bin/bash
#
#if [ $# -ne 1 ]; then
#  echo "specify input file." 
#  exit 1
#fi

#$1

hadoop dfs -rmr output/testProtocol

hadoop dfs -rmr bin/testProtocol
hadoop dfs -put testProtocol bin/testProtocol

#hadoop dfs -rmr input/Sum
#hadoop dfs -mkdir input/Sum
#hadoop dfs -put input/* input/Sum

hama pipes -conf TestProtocol_job.xml -output output/testProtocol
#hama pipes -conf TestProtocol_job.xml
