# vector-backed-sorted-map
[![Clojars Project](https://img.shields.io/clojars/v/com.rpl/vector-backed-sorted-map.svg)](https://clojars.org/com.rpl/vector-backed-sorted-map)

A sorted map implementation designed for ultra-fast initialization and merge operations.

Maps returned by this library behave like those returned by Clojure `sorted-map` (PersistentTreeMap), but they have O(1) instantiation time instead of O(N log N). Instantiating a vector-backed sorted map (VBSM) will be dramatically faster than `sorted-map` for more than a small number of elements. The only catch is that your k/v pairs must already be sorted. It's not hard to structure your application such that the data will always be sorted in a previous step or read from disk in sorted order.

## Usage

Add a dependency via the mechanism of your choice.

In your code:

`(require 'com.rpl.vector-backed-sorted-map :as vbsm)`

To use, provide a vector of k/v tuples, and optionally a key comparator:

`(let [m (vbsm/vector-backed-sorted-map [[0 0] [1 1] [2 2]])])`

The vector tuples will never be mutated or modified in any way and can safely be reused elsewhere.

The tuples *must* be in already-sorted order! VBSM makes no effort to sort your input, but if assertions are enabled, it will verify that they're in sorted order. This verification behavior is really handy when you're just setting up a system that uses VBSMs, but it is also very, very expensive, so it's a good idea to disable assertions if you're aiming for high performance.

## Backing vector access

If you are using VBSMs explicitly in performance-critical code, it's often handy to be able to set aside the map wrapper all together and just access the backing vector. `vec` and similar core library fns would end up making a copy of the backing vector, and that's no good; so we provide a special helper fn called `vectorize`. `vectorize` exposes the direct backing vector in O(1) time, unless you call it on a VBSM that has overlaid modifications, in which case it will be O(N). (See read-only discussion below for more information).

## Fast merge support

Because of their flat structure, VBSMs are very amenable to high-performance merge operations. The library provides a handy `merge-sorted-optimal` function that implements a size-aware merging strategy, swapping between "one by one" and "bulk" modes based on relative sizes of the VBSMs being merged. Using this function tends to beat Clojure `merge` very handily.

## Read-only vs "updatable" VBSMs

When you instantiate a VBSM via `vector-backed-sorted-map`, you get back an "updatable" VBSM, that is, one that can be modified normally by `assoc`, `dissoc`, etc. This updatability is provided via an internal hidden overlay map, which is just a regular Clojure sorted-map. This approach avoids uncesssary vector surgery and makes random updates fairly cheap, particularly when the total number of updates is relatively small. However, there is an added cost at iteration or `vectorize` time, as the overlay has to be flattened into the backing vector to produce a new backing vector, making the operation take O(N).

It can be difficult to reason about whether a given map ever gets updated, so we provide the option to get a "read-only" VBSM by calling `read-only-vector-backed-sorted-map`. `assoc`, `dissoc`, etc will throw exceptions when invoked on a read-only VBSM. Using a read-only VBSM allows you to ensure that merge and `vectorize` operations down the road will always be consistently O(1), which can be important for performance-critical code. 

## License

Copyright © 2019 - 2020 Red Planet Labs Inc.

Distributed under the Apache Software License version 2.0
