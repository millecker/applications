#!/bin/bash

cd bin
jar cfm ../HamaExamples_tmp1.jar ../Manifest.txt `find . -not -path "*/.svn/*" -not -type d`
cd ..

LIBS=../../lib

# Include jars into HamaExample.jar
# jar uf HamaExamples.jar `find $LIBS -not -path "*/.svn/*" -not -type d`

# Pack jar including all lib jars
java -jar pack.jar -mainjar HamaExamples_tmp1.jar -libjar $LIBS/hama-core-0.7.0-SNAPSHOT.jar -destjar HamaExamples_tmp2.jar
java -jar pack.jar -mainjar HamaExamples_tmp2.jar -libjar $LIBS/hadoop-core-1.3.0-SNAPSHOT.jar -destjar HamaExamples_tmp3.jar
java -jar pack.jar -mainjar HamaExamples_tmp3.jar -libjar $LIBS/commons-logging-1.1.1.jar -destjar HamaExamples_tmp4.jar
java -jar pack.jar -mainjar HamaExamples_tmp4.jar -libjar $LIBS/tjungblut-math-1.0.jar -destjar HamaExamples_tmp5.jar

rm HamaExamples_tmp1.jar
rm HamaExamples_tmp2.jar
rm HamaExamples_tmp3.jar
rm HamaExamples_tmp4.jar

mv HamaExamples_tmp5.jar HamaExamples.jar

if [ "$1" == "gpu" ]; then
  java -Xmx2g -jar ../../../rootbeer1/Rootbeer.jar HamaExamples.jar HamaExamples-GPU.jar -nodoubles -norecursion -loadclass $2
fi
