---
layout: docs
title: Usage
section: "usage"
---

# Lolipop - purely functional Raft implementation

**Lolipop** is a basic implementation of Raft algorithm using a purely functional approach. It is effect agnostic meaning user can choose your own effect type (eg. cats IO, Future, ZIO)

It does not support the following feature (todo)

* Dynamic membership
* Log compaction 


## Usage Guide

**Lolipop** is rather minimal, users need to implement several interfaces to be able to use it, below are interfaces that are required

1. [State Machine](./state-machine.html)
2. [Logs Api](./logs-api.html)
3. [Network IO](./network-io.html)
4. [Metadata Api](./metadata-api.html)
5. [Events Logger](./events-logger.html)
6. [Raft Process](./raft-process.html)
7. [Start the server](./start-raft.html)
