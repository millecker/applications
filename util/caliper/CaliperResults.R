#!/usr/bin/Rscript
library(rjson)
library(reshape2)
library(sqldf)
library(ggplot2)

# Functions
# List append
lappend <- function (lst, ...){
  lst <- c(lst, list(...))
  return(lst)
}

args <- commandArgs(trailingOnly = TRUE)

# Load result file
caliperJsonFile <- args[1]
#caliperJsonFile <- "results/at.illecker.hadoop.rootbeer.examples.matrixmultiplication.MatrixMultiplicationBenchmark.2013-06-16T08:18:14Z.json"

caliperResult <- NULL
caliperResult <- fromJSON(file=caliperJsonFile)
if (is.null(caliperResult)) {
  message <- paste("Json File was not found!",caliperJsonFile)
  stop(message)
}

scenarioTable <- NULL
measurementResults <-list()

# Loop over all scenarios and build measurements
for (scenarioIndex in 1:length(caliperResult)) {
  
  scenarioTableRow <- data.frame(scenario=scenarioIndex)
  scenario <- caliperResult[[scenarioIndex]]
  #length(scenario)
  
  # Scenario ID
  scenarioId <- scenario[1]
  # Add id
  scenarioTableRow <- data.frame(scenarioTableRow,data.frame(id=scenarioId))

  # Scenario Run informations
  scenarioRun <- scenario[2]
  # Add startTime
  scenarioTableRow <- data.frame(scenarioTableRow,data.frame(StartTime=scenarioRun$run$startTime))

  # Scenario Instrument specification
  scenarioInstrumentSpec <- scenario[3]
  # Add Instrument className
  scenarioTableRow <- data.frame(scenarioTableRow,data.frame(Instrument=scenarioInstrumentSpec$instrumentSpec$className))
  # Add measurements
  scenarioTableRow <- data.frame(scenarioTableRow,data.frame(Measurements=scenarioInstrumentSpec$instrumentSpec$options$measurements))
  # Add warmup
  scenarioTableRow <- data.frame(scenarioTableRow,data.frame(Warmup=scenarioInstrumentSpec$instrumentSpec$options$warmup))

  # Scenario properties
  scenarioProperties <- scenario[4]$scenario
  
  # Scenario Host informations
  scenarioHost <- scenarioProperties[1]
  # Add host.availableProcessors
  scenarioTableRow <- data.frame(scenarioTableRow,data.frame(AvailableProcessors=scenarioHost$host$properties$host.availableProcessors))
  # Add os.name
  scenarioTableRow <- data.frame(scenarioTableRow,data.frame(OS=scenarioHost$host$properties$os.name))
  # Add os.arch
  scenarioTableRow <- data.frame(scenarioTableRow,data.frame(OS_Arch=scenarioHost$host$properties$os.arch))

  # Scenario VM specifications
  #scenarioVmSpec <- scenarioProperties[2]
  #javaName <- scenarioVmSpec$vmSpec$properties$java.runtime.name
  #javaVersion <- scenarioVmSpec$vmSpec$properties$java.runtime.version
  #scenarioVmSpec$vmSpec$options
  
  # Scenario Benchmark specifications
  scenarioBenchmarkSpec <- scenarioProperties[3]$benchmarkSpec
  # Add className
  scenarioTableRow <- data.frame(scenarioTableRow,data.frame(ClassName=scenarioBenchmarkSpec$className))
  # Add methodName
  scenarioTableRow <- data.frame(scenarioTableRow,data.frame(MethodName=scenarioBenchmarkSpec$methodName))
  
  parameters <- scenarioBenchmarkSpec$parameters
  allParameters <- NULL
  # Get Parameters of scenarioBenchmarkSpec
  for (parameterIndex in 1:length(parameters)) {
    
    parameters.T <- t(melt(parameters[parameterIndex]))
    colnames(parameters.T) <- parameters.T[2,] 
    parameterName <- parameters.T[2,]
    parameterValue <- parameters.T[1,]
    
    parameter <- data.frame(parameterValue)
    colnames(parameter) <- parameterName
    
    if (is.null(allParameters)) {
      allParameters <- paste(parameterName,"=",parameterValue, sep="")
    } else {
      allParameters <- paste(allParameters,"\n",parameterName,"=",parameterValue, sep="")
    }
    
    scenarioTableRow <- data.frame(scenarioTableRow,parameter)
  }
  scenarioTableRow <- data.frame(scenarioTableRow,data.frame(AllParameters=allParameters))
  
  # Add scenarioTableRow to  scenarioTable
  if (is.null(scenarioTable)) {
    scenarioTable <- scenarioTableRow
  } else {
    scenarioTable <- rbind(scenarioTable,scenarioTableRow)  
  }


  # Get Measurements of scenario
  measurements <- scenario[5]$measurements
  for (measurementIndex in 1:length(measurements)) {
  
    measurements[[measurementIndex]]
    measurement <- measurements[[1]]
    
    value <- measurement$value
    magnitude <- value$magnitude
    unit <- value$unit
  
    newMeasurementRow <- c(scenario=scenarioIndex,measurement=measurementIndex,magnitude=magnitude,unit=unit)
    str(newMeasurementRow)
    measurementResults <- lappend(measurementResults, newMeasurementRow)
  }
}


## Measurement Table
# Create table from vectors
measurementTable <- data.frame(measurementResults)
# Transpose table
measurementTable.T <- t(measurementTable)
# Set row names to index
row.names(measurementTable.T) <- 1:nrow(measurementTable.T)
measurementTable <- measurementTable.T
measurementTable <- data.frame(measurementTable)

# Convert Strings to Numeric
measurementTable <- transform(measurementTable,scenario = as.numeric(as.character(measurementTable$scenario)))
measurementTable <- transform(measurementTable,measurement = as.numeric(as.character(measurementTable$measurement)))
measurementTable <- transform(measurementTable,magnitude = as.numeric(as.character(measurementTable$magnitude)))

scenarioTable <- transform(scenarioTable,Measurements = as.numeric(as.character(scenarioTable$Measurements)))
scenarioTable <- transform(scenarioTable,AvailableProcessors = as.numeric(as.character(scenarioTable$AvailableProcessors)))


## Cleanup
rm(scenario)
rm(scenarioIndex)
rm(measurementIndex)
rm(measurementResults)
rm(newMeasurementRow)
rm(measurement)
rm(measurements)
rm(measurementTable.T)
rm(value)
rm(magnitude)
rm(unit)
rm(parameterIndex)
rm(parameterName)
rm(parameterValue)
rm(allParameters)
rm(parameter)
rm(parameters)
rm(parameters.T)
rm(scenarioTableRow)
rm(scenarioBenchmarkSpec)
rm(scenarioHost)
rm(scenarioId)
rm(scenarioInstrumentSpec)
rm(scenarioProperties)
rm(scenarioRun)


benchmarkTable <- merge(x = measurementTable, y = scenarioTable, by = "scenario", all.x=TRUE)
#benchmarkTable <- sqldf('SELECT * FROM measurementTable JOIN scenarioTable USING(scenario)')

benchmarkTableAvg <- sqldf('SELECT scenario,avg(magnitude) as magnitude,unit,AllParameters FROM benchmarkTable GROUP BY scenario')
benchmarkTableAvg
#str(benchmarkTableAvg)

#summary(benchmarkTable)

#qplot(data=benchmarkTable,x=scenario,y=magnitude,main="Benchmark Results",geom="histogram")
ggplot(benchmarkTableAvg,aes(x=AllParameters,y=magnitude,fill=scenario)) + geom_bar(stat="identity",color="black")
