
---
layout: home
title:  "Home"
section: "home"
technologies:
 - first: ["Scala", "Lolipop is completely written in Scala"]
 - second: ["cats-effect", "Lolipop is purely functional and leverage cats-effect to model effects"]
 - third: ["fs2", "Lolipop "]
---

## Lolipop (Purely functional Raft implementation)

**Lolipop** is a Raft algorithm library that gives user control on the execution of Raft process. It is flexible and most components can be customized.

It parameterized over the following concepts for flexibility
  
* Effect type
* Persistent Log Layer
* Network layer

Currently it only supports the bare minimum, which is the ability to reach consensus within the cluster. It does **NOT** supports `Dynamic membership` and `Log compaction` yet.

For more, check the [documentations](./docs)
