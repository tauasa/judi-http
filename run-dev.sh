#!/usr/bin/env bash

# launch from the project's target directory in development mode

# if the JUDI_PATH environment variable is set, use it to launch the jar file
if [ -n "$JUDI_PATH" ]; then
    echo "Launching judi-http from $JUDI_PATH"
    java -jar "$JUDI_PATH/judi-http.jar"
else
# otherwise, launch from the target directory
    echo "Launching judi-http from target directory"
    java -jar target/judi-http.jar
fi