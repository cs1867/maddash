#!/bin/bash

java -Done-jar.main.class=net.es.maddash.utils.DBClientUtil -Djava.net.preferIPv4Stack=true -jar /usr/lib/maddash/maddash-server/target/maddash-server.one-jar.jar $*
