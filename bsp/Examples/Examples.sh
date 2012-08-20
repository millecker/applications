#!/bin/bash

#if [ $# -ne 1 ]; then
#  echo "Please specify main Class." 
#  exit 1
#fi


hadoop dfs -rmr output/examples

hadoop dfs -rmr input/examples
hadoop dfs -mkdir input/examples
hadoop dfs -put input/* input/examples

cd bin
jar cf ../Examples.jar `find . -not -path "*/.svn/*" -not -type d` `find ../lib -not -path "*/.svn/*" -not -type d`
cd ..

hama jar Examples.jar $1

