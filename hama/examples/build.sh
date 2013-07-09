#!/bin/bash

cd bin
jar cfm ../HamaExamples.jar ../Manifest.txt `find . -not -path "*/.svn/*" -not -type d`
cd ..

# Include jars into HamaExample.jar
jar uf HamaExamples.jar `find lib -not -path "*/.svn/*" -not -type d`
