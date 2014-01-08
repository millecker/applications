#!/bin/bash

clear

# Delete hdfs directories
hadoop dfs -rmr /examples/bin/testNullWritable3
hadoop dfs -rmr /examples/output/testNullWritable3

# Compile and upload binary
make clean && make
hadoop dfs -put testNullWritable /examples/bin/testNullWritable3

# Run job
hama pipes -conf testNullWritable.xml \
 -input /examples/output/testNullWritable2/part-00000 \
 -output /examples/output/testNullWritable3

# Print output
hama seqdumper -file /examples/output/testNullWritable3/part-00000
hama seqdumper -file /examples/output/testNullWritable3/output1.seq
hama seqdumper -file /examples/output/testNullWritable3/output2.seq
hama seqdumper -file /examples/output/testNullWritable3/output3.seq
