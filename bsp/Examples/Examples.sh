#!/bin/bash

if [ $# -lt 1 ]; then
  echo "Please specify main Class." 
  exit 1
fi


hadoop dfs -rmr output/examples

hadoop dfs -rmr input/examples
hadoop dfs -mkdir input/examples
hadoop dfs -put input/* input/examples

cd bin
jar cfm ../Examples.jar ../Manifest.txt `find . -not -path "*/.svn/*" -not -type d`
cd ..
jar uf Examples.jar `find lib -not -path "*/.svn/*" -not -type d`

hama jar Examples.jar $@

