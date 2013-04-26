#!/bin/bash

cd bin
jar cfm ../Examples_tmp.jar ../Manifest.txt `find . -not -path "*/.svn/*" -not -type d`
cd ..

# Include jars into Example.jar
# jar uf Examples.jar `find lib -not -path "*/.svn/*" -not -type d`

HAMA_DIR=../../../hama-trunk-gpu/hama-trunk-gpu/dist/target/hama-0.7.0-SNAPSHOT/hama-0.7.0-SNAPSHOT
HAMA_LIB=$HAMA/lib

# Pack jar including all lib jars
#java -jar pack.jar -mainjar Examples_tmp.jar -directory lib -destjar Examples_tmp2.jar

java -jar pack.jar -mainjar Examples_tmp.jar -libjar $HAMA_DIR/hama-core-0.7.0-SNAPSHOT.jar -destjar Examples.jar

rm Examples_tmp.jar
#rm Examples_tmp2.jar

#mv Examples_tmp.jar Examples.jar

if [ "$1" == "gpu" ]; then
#  java -Xmx1g -jar ../../../rootbeer/rootbeer1/Rootbeer.jar Examples.jar Examples-GPU.jar
  java -Xmx2g -jar ../../../rootbeer/rootbeer1_rbclassload2/Rootbeer.jar Examples.jar Examples-GPU.jar
fi
