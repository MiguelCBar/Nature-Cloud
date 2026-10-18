#!/bin/bash

source "$(dirname "$0")/config.sh"

# Install Java 11 on the fresh Amazon Linux instance
cmd="sudo yum update -y; sudo yum install java-11-amazon-corretto-devel.x86_64 -y;"
ssh -o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAR_PATH ec2-user@$(cat instance.dns) $cmd

# Copy the compiled WebServer JAR to the EC2 instance
scp -o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAR_PATH $DIR/../../webserver/target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar ec2-user@$(cat instance.dns):/home/ec2-user/

# Copy the compiled Instrumentation/Javassist JAR to the EC2 instance
scp -o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAR_PATH $DIR/../../instrumentation/target/JavassistWrapper-1.0-jar-with-dependencies.jar ec2-user@$(cat instance.dns):/home/ec2-user/

# Setup the system to automatically start the instrumented WebServer on boot
# The Javassist agent is injected into the JVM initialization string
cmd="echo -e \"#!/bin/bash\\njava -Xms256m -Xmx500m -cp /home/ec2-user/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar -Xbootclasspath/a:/home/ec2-user/JavassistWrapper-1.0-jar-with-dependencies.jar -javaagent:/home/ec2-user/JavassistWrapper-1.0-jar-with-dependencies.jar=RequestMetricsTool:pt.ulisboa.tecnico.cnv.fractals,pt.ulisboa.tecnico.cnv.grayscott,pt.ulisboa.tecnico.cnv.dna:output pt.ulisboa.tecnico.cnv.webserver.WebServer \" | sudo tee /etc/rc.d/rc.local; sudo chmod +x /etc/rc.d/rc.local"
#cmd="echo -e \"#!/bin/bash\\njava -Xms256m -Xmx500m -cp /home/ec2-user/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar -Xbootclasspath/a:/home/ec2-user/JavassistWrapper-1.0-jar-with-dependencies.jar -javaagent:/home/ec2-user/JavassistWrapper-1.0-jar-with-dependencies.jar=RequestMetricsTool:pt.ulisboa.tecnico.cnv.fractals,pt.ulisboa.tecnico.cnv.grayscott,pt.ulisboa.tecnico.cnv.dna:output pt.ulisboa.tecnico.cnv.webserver.WebServer >> /var/log/nature-cloud.log 2>&1\" | sudo tee /etc/rc.d/rc.local; sudo chmod +x /etc/rc.d/rc.local"

ssh -o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAR_PATH ec2-user@$(cat instance.dns) $cmd