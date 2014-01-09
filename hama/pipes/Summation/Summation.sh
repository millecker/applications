#!/bin/bash
#
#if [ $# -ne 1 ]; then
#  echo "specify input file." 
#  exit 1
#fi

#$1

clear

# Delete hdfs directories
hadoop fs -rmr /examples/input/summation
hadoop fs -rmr /examples/output/summation
hadoop fs -rmr /examples/bin/cpu_summation

# Generate input data
echo -e "key1\t1.0\nkey2\t2.0\nkey3\t3.0\nkey4\t4.0\nkey5\t5.0\nkey6\t6.0\nkey7\t7.0\nkey8\t8.0\nkey9\t9.0\nkey10\t10.0" > summation.txt \
 && hadoop fs -put summation.txt /examples/input/summation/input.txt && rm summation.txt

# Compile and upload binary
cd cpu-Summation
make clean && make
hadoop fs -put cpu-Summation /examples/bin/cpu_summation
cd ..

# Run Summation example
hama pipes -conf Summation_job.xml \
 -input /examples/input/summation \
 -output /examples/output/summation

# Print output
hadoop fs -cat /examples/input/summation/input.txt
hama seqdumper -file /examples/output/summation/part-00000
