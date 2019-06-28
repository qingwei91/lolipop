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


## Usage

**Lolipop** is rather minimal, users need to implement several interfaces to be able to use it, below are interfaces that are required


