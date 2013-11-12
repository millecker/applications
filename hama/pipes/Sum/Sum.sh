#!/bin/bash
#
#if [ $# -ne 1 ]; then
#  echo "specify input file." 
#  exit 1
#fi

#$1

hadoop fs -rmr output/Sum
hadoop fs -rmr bin/cpu-Sum

cd cpu-Sum
make clean && make
cd ..

hadoop fs -put cpu-Sum/cpu-Sum bin/cpu-Sum

hadoop fs -rmr input/Sum
hadoop fs -mkdir input/Sum
hadoop fs -put input/* input/Sum

hama pipes -conf Sum_job.xml -input input/Sum -output output/Sum

hadoop fs -cat input/Sum/test.txt

hama seqdumper -seqFile output/Sum/part-00000