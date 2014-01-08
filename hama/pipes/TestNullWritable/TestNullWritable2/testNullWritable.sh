#!/bin/bash

clear

# Delete hdfs directories
hadoop dfs -rmr /examples/bin/testNullWritable2
hadoop dfs -rmr /examples/output/testNullWritable2

# Compile and upload binary
make clean && make
hadoop dfs -put testNullWritable /examples/bin/testNullWritable2

# Run job
hama pipes -conf testNullWritable.xml \
 -input /examples/output/testNullWritable1/part-00000 \
 -output /examples/output/testNullWritable2

# Print output
hama seqdumper -file /examples/output/testNullWritable2/part-00000
