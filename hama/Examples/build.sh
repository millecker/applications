#!/bin/bash

cd bin
jar cfm ../Examples_tmp.jar ../Manifest.txt `find . -not -path "*/.svn/*" -not -type d`
cd ..

# Include jars into Example.jar
# jar uf Examples.jar `find lib -not -path "*/.svn/*" -not -type d`

# Copy all jars for rootbeer
cp ../../../hama-trunk-gpu/hama-trunk-gpu/dist/target/hama-0.7.0-SNAPSHOT/hama-0.7.0-SNAPSHOT/lib/commons-configuration-1.7.jar lib/
cp ../../../hama-trunk-gpu/hama-trunk-gpu/dist/target/hama-0.7.0-SNAPSHOT/hama-0.7.0-SNAPSHOT/lib/commons-lang-2.6.jar lib/
cp ../../../hama-trunk-gpu/hama-trunk-gpu/dist/target/hama-0.7.0-SNAPSHOT/hama-0.7.0-SNAPSHOT/lib/commons-logging-1.1.1.jar lib/
cp ../../../hama-trunk-gpu/hama-trunk-gpu/dist/target/hama-0.7.0-SNAPSHOT/hama-0.7.0-SNAPSHOT/lib/hadoop-core-1.0.4.jar lib/
cp ../../../hama-trunk-gpu/hama-trunk-gpu/dist/target/hama-0.7.0-SNAPSHOT/hama-0.7.0-SNAPSHOT/lib/zookeeper-3.4.5.jar lib/
cp ../../../hama-trunk-gpu/hama-trunk-gpu/dist/target/hama-0.7.0-SNAPSHOT/hama-0.7.0-SNAPSHOT/lib/rootbeer-1.x-SNAPSHOT.jar lib/

# Pack jar including all lib jars
java -jar pack.jar -mainjar Examples_tmp.jar -directory lib -destjar Examples.jar

rm Examples_tmp.jar

if [ "$1" == "gpu" ]; then
  java -Xmx1g -jar ../../../rootbeer/rootbeer1/Rootbeer.jar Examples.jar Examples-GPU.jar
fi
