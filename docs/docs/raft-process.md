---
layout: docs
title: Raft Process
section: "usage"
---

### 6. Create `RaftProcess

Once we have the above dependencies, we can build a RaftProcess using methods on RaftProcess object, it requires all dependencies mentioned above, with an additional `ClusterConfig`, which is a case class containing names of each node 

WARNING: `ClusterConfig` will change once we support dynamic membership

```
import raft._

val clusterConfig = ClusterConfig("node0", Set("node1", "node2"))

val proc = RaftProcess.simple(
  stateMachine,
  clusterConfig,
  logIO,
  networkIO,
  eventLogger,
  persistentIO
)

```

