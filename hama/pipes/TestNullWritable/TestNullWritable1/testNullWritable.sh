#!/bin/bash

clear

# Delete hdfs directories
hadoop dfs -rmr /examples/bin/testNullWritable1
hadoop dfs -rmr /examples/input/testNullWritable1
hadoop dfs -rmr /examples/output/testNullWritable1

# Upload input data
echo -e "key1\t1.0\nkey2\t2.0\nkey3\t3.0\nkey4\t4.0\nkey5\t5.0\nkey6\t6.0\nkey7\t7.0\nkey8\t8.0\nkey9\t9.0\nkey10\t10.0" > input.txt && hadoop fs -put input.txt \
 /examples/input/testNullWritable1/input.txt && rm input.txt

# Compile and upload binary
make clean && make
hadoop dfs -put testNullWritable /examples/bin/testNullWritable1

# Run job
hama pipes -conf testNullWritable.xml \
 -input /examples/input/testNullWritable1 \
 -output /examples/output/testNullWritable1

# Print output
hama seqdumper -file /examples/output/testNullWritable1/part-00000
