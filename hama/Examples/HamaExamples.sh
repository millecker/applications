#!/bin/bash

if [ $# -lt 1 ]; then
  echo "Please specify main Class." 
  exit 1
fi


hadoop dfs -rmr output/hama/examples

hadoop dfs -rmr input/hama/examples
hadoop dfs -mkdir input/hama/examples
hadoop dfs -put input/* input/hama/examples

./build.sh # $1

# $1 set main-class in manifest
# Remove $1 from parameter list

# echo $1
# $1=''
# echo $@

hama jar HamaExamples.jar $@
