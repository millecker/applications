###############################################################################
##### Hama Examples README                                                #####
###############################################################################

# Hama Seqdumper which can operate with SequenceFiles
hama seqdumper -libjars HamaExamples.jar -seqFile input/examples/MatrixA.seq

# TestProtocol Example
#-> needs the bin/testProtocol binary in the HDFS! 
./HamaExamples.sh at.illecker.hama.examples.TestProtocol

# Sum Example
./HamaExamples.sh at.illecker.hama.examples.Sum

# Pi Estimator
./HamaExamples.sh at.illecker.hama.examples.MyEstimator {NumBspTasks}

# MatrixMultiplication
./HamaExamples.sh de.jungblut.bsp.MatrixMultiplicationBSP
./HamaExamples.sh at.illecker.hama.examples.matrixmultiplication.MatrixMultiplication {MATRIX_SIZE_N} {NumBspTasks}

###############################################################################
