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
- [ ] file uploading, chunked request/response handling
- [ ] websockets
- [ ] HTTP/2
- [ ] HTTP/3
- [ ] API as compatible with Aleph as possible (so it can serve as a drop-in replacement)

### Requirements

* outperform or at least match Aleph speed
* be buildable as a GraalVM native-image
