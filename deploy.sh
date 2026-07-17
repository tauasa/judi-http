#!/usr/bin/env bash

cp -f www/* /etc/judi-http/www
echo "Copied www files to /etc/judi-http/www"

cp -f config/server.yml /etc/judi-http/config/server.yml
echo "Copied server.yml to /etc/judi-http/config/server.yml"

cp -f target/judi-http.jar /etc/judi-http/judi-http.jar
echo "Copied judi-http.jar to /etc/judi-http/judi-http.jar"

cp -f run.sh /etc/judi-http/run.sh
echo "Copied run.sh to /etc/judi-http/run.sh"