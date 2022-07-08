# ARCADIA

The ARCADIA framework is for developing integerated, intelligent systems. This distribution includes several models and the components, sensors, etc. required to run them.

## Installation and Setup

See either 
- [INSTALL_MAC.md](INSTALL_MAC.md)
- [INSTALL_LINUX.md](INSTALL_LINUX.md)

## Usage
```Bash
# enter the directory and start a REPL
cd arcadia
lein deps
lein repl
```

```Clojure
;; Test a basic model of multiple object tracking.
(refresh)
(arcadia.models.mot-simple/example-run)
```

## Documentation
API documentation can be generated using either [Marginalia](https://github.com/gdeer81/marginalia) or [Codox](https://github.com/weavejester/codox). See their project pages for available options.
```Bash
# Codox
lein codox

# Marginalia
lein marg
```

An [in-progress manual](manual/index.md) for working with ARCADIA is also available.

## License

See [LICENSE](LICENSE) file.

Distributed under a modified open source license
