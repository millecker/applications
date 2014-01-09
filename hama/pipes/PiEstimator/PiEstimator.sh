#!/bin/bash

clear

# Delete hdfs directories
hadoop fs -rmr /examples/input/piestimator
hadoop fs -rmr /examples/output/piestimator
hadoop fs -rmr /examples/bin/cpu_piestimator

# Compile and upload binary
cd cpu-PiEstimator
make clean && make
hadoop fs -put cpu-PiEstimator /examples/bin/cpu_piestimator
cd ..

# Run PiEstimator example
hama pipes -conf PiEstimator_job.xml \
 -output /examples/output/piestimator

# Print output
hama seqdumper -file /examples/output/piestimator/part-00001
