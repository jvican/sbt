#!/usr/bin/env bash

sbt -Dsbtio.path=../sbt-io -Dsbtutil.path=../sbt-util -Dsbtlm.path=../librarymanagement -Dsbtzinc.path=../zinc "$@"
