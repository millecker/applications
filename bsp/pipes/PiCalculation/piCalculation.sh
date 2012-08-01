#!/bin/bash
#
#if [ $# -ne 1 ]; then
#  echo "specify input file." 
#  exit 1
#fi

#$1

hadoop dfs -rmr output/PiCalculation

hadoop dfs -rmr bin/cpu-PiCalculation
hadoop dfs -put cpu-PiCalculation/cpu-PiCalculation bin/cpu-PiCalculation

hadoop dfs -rmr input/PiCalculation
hadoop dfs -mkdir input/PiCalculation
hadoop dfs -put input/* input/PiCalculation

hadoop pipes -conf piCalculation_job.xml -input input/PiCalculation -output output/PiCalculation
