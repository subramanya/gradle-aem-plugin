#!/bin/sh

(cd {{ rootProject.projectDir }} && {{ service.opts['environmentCommand'] }} && {{ service.opts['statusCommand'] }} -Pinstance.name={{ instance.name }})