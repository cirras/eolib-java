# EOLib
[![quality gate](https://sonarcloud.io/api/project_badges/measure?project=Cirras_eolib-java&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=Cirras_eolib-java)
[![format](https://github.com/Cirras/eolib-java/actions/workflows/format.yml/badge.svg?event=push)](https://github.com/Cirras/eolib-java/actions/workflows/format.yml)
[![build](https://github.com/Cirras/eolib-java/actions/workflows/build.yml/badge.svg?event=push)](https://github.com/Cirras/eolib-java/actions/workflows/build.yml)
[![javadoc](https://javadoc.io/badge2/dev.cirras/eolib/javadoc.svg)](https://javadoc.io/doc/dev.cirras/eolib)

A core Java library for writing applications related to Endless Online.

## Features
Read and write the following EO data structures:
- Client packets
- Server packets
- Endless Map Files (EMF)
- Endless Item Files (EIF)
- Endless NPC Files (ENF)
- Endless Spell Files (ESF)
- Endless Class Files (ECF)

Utilities:
- Data reader
- Data writer
- Number encoding
- String encoding
- Data encryption
- Packet sequencer

## Requirements
- JDK 17+
- [Maven](https://maven.apache.org/)

## Build & Test
Execute the following command from the project's root directory:
```
mvn clean install
```
This command will:
- build `protocol-code-generator`
- build `protocol-code-generator-maven-plugin`
- generate code based on the [eo-protocol](https://github.com/Cirras/eo-protocol) XML specification
- build `eolib`
- run unit tests