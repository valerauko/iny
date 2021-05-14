# Iny

>Iny  magához  rántotta  a  libát,  és  elharapta  nyakát  tőből,  mintha  késsel  vágták  volna  el.  Aztán csak a csontok roppantak néha.
>
>— Fekete István: Vuk

Experiments with Netty from Clojure.

## Usage

Everything before version 1.0.0 is to be considered experimental, buggy and unstable.

## Goals

A HTTP server library based on Netty to replace [Aleph](https://github.com/ztellman/aleph/).

- [x] very basic HTTP server functionality
- [x] file uploading, chunked request/response handling
- [ ] websockets
- [x] HTTP/2
- [x] HTTP/3

### Goals

* outperform or at least match Aleph speed
* be buildable as a GraalVM native-image
