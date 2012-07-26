#!/bin/bash

if [ $# -ne 1 ]; then
  echo "specify input file." 
  exit 1
fi

hadoop dfs -rmr output

hadoop pipes -conf wordcount_job.xml -input $1 -output output
