#!/bin/bash
#
#if [ $# -ne 1 ]; then
#  echo "specify input file." 
#  exit 1
#fi

#$1

clear

# Delete hdfs directories
hadoop fs -rmr /examples/input/testProtocol
hadoop fs -rmr /examples/output/testProtocol
hadoop fs -rmr /examples/bin/testProtocol

# Compile and upload binary
make clean && make
hadoop dfs -put testProtocol /examples/bin/testProtocol

# Run Summation example
hama pipes -conf TestProtocol_job.xml -output /examples/output/testProtocol

# Print output
TASKLOGDIR=$(ls -t $HAMA_HOME/logs/tasklogs/ | head -1)
GROOMSERVER_TASKLOGDIR=$(ls -t /tmp/hadoop-$USER/bsp/local/groomServer/ | head -1)

echo -e "\n"
echo "/tmp/hadoop-$USER/bsp/local/groomServer/$GROOMSERVER_TASKLOGDIR/work/tasklogs/$TASKLOGDIR/*.err"
cat /tmp/hadoop-$USER/bsp/local/groomServer/$GROOMSERVER_TASKLOGDIR/work/tasklogs/$TASKLOGDIR/*.err

echo -e "\n"
echo "/tmp/hadoop-$USER/bsp/local/groomServer/$GROOMSERVER_TASKLOGDIR/work/tasklogs/$TASKLOGDIR/*.log"
cat /tmp/hadoop-$USER/bsp/local/groomServer/$GROOMSERVER_TASKLOGDIR/work/tasklogs/$TASKLOGDIR/*.log

echo -e "\nhadoop fs -cat /examples/output/testProtocol/part-00000"
hadoop fs -cat /examples/output/testProtocol/part-00000

