## Some patterns used in this project

* Sharing mutable refs (Just use Ref)
* Gives control to high level code
    - Produce a `Stream[F, F[Unit]]` instead of `Stream[F, Unit]`, this gives high level code a chance to determine how to run it, for the same reason we sometimes also return more context like `Stream[F, Map[String, F[Unit]]` 
* Coordinate similar side-effect triggered independently and potentially concurrently (Use Queue, pipe task into the queue, eg. `Queue[F, F[Unit]]` and/or `Queue[F, Map[String, F[Unit]]]`, this allow us to not spam the same tasks all over, it also allow high level code to determine execution semantic with more information, ie. to run them in sequence or run concurrently and burn your CPU 
* To execute tasks concurrently with dependency
