#!/bin/bash
yum update -y
amazon-linux-extras enable corretto17 && yum install -y java-17-amazon-corretto-headless awscli
aws s3 cp s3://wolfs-amaziah-bucket/manager.jar /home/ec2-user/app.jar
nohup java -cp /home/ec2-user/app.jar manager.ManagerApplication \
  "https://sqs.us-east-1.amazonaws.com/242240068339/Local_Manager_Queue" \
  "https://sqs.us-east-1.amazonaws.com/242240068339/Manager_Worker_Queue" \
  "https://sqs.us-east-1.amazonaws.com/242240068339/Worker_Manager_Queue" \
  "https://sqs.us-east-1.amazonaws.com/242240068339/Manager_App_Queue" \
  "wolfs-amaziah-bucket" "5" "false" \
  > /var/log/manager.log 2>&1 &
