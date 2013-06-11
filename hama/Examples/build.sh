#!/bin/bash

cd bin
jar cfm ../HamaExamples.jar ../Manifest.txt `find . -not -path "*/.svn/*" -not -type d`
cd ..

LIBS=../../lib

# Include jars into HamaExample.jar
jar uf HamaExamples.jar `find $LIBS -not -path "*/.svn/*" -not -type d`
