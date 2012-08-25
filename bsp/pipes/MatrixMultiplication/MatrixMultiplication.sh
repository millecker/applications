#!/bin/bash

hadoop dfs -rmr output/PiCalculation

hadoop dfs -rmr bin/cpu-PiCalculation
hadoop dfs -put cpu-PiCalculation/cpu-PiCalculation bin/cpu-PiCalculation

hama pipes -conf piCalculation_job.xml -output output/PiCalculation
