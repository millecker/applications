#!/bin/bash

hadoop fs -rmr output/PiCalculation
hadoop fs -rmr bin/cpu-PiCalculation

cd cpu-PiCalculation
make clean && make
cd ..

hadoop fs -put cpu-PiCalculation/cpu-PiCalculation bin/cpu-PiCalculation

hama pipes -conf piCalculation_job.xml -output output/PiCalculation

hama seqdumper -seqFile output/PiCalculation/part-00004
