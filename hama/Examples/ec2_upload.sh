#!/bin/bash

if [ $# -lt 1 ]; then
  echo "Please specify Public DNS of EC2 instance. e.g. ubuntu@ec2-54-228-105-64.eu-west-1.compute.amazonaws.com"
  exit 1
fi

scp -i ~/.ec2/millecker.pem Examples.jar $1:./Examples
