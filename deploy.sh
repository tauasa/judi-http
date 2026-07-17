#!/usr/bin/env bash

cp -f www/* /etc/judi-http/www
cp -f config/server.yml /etc/judi-http/config/server.yml
cp -f run.sh /etc/judi-http/run.sh
cp -f target/judi-http.jar /etc/judi-http/judi-http.jar