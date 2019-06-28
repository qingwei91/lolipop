# Lolipop - purely functional Raft implementation

**Lolipop** is a basic implementation of Raft algorithm using a purely functional approach. It is effect agnostic meaning user can choose your own effect type (eg. cats IO, Future, ZIO)


To learn more, check [Documentation](https://qingwei91.github.io/lolipop/docs/)

## TODO

### Replay log events on node restart

* add test for it, replay should work regardless of server type
* perform replay on start is probably easier

### Visualize state changes over time

* Input: (events log, time)
* Output: States of each node
* Allow controlling time back and forth
