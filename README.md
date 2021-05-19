# jepsen.couchbase

Jepsen testing for Couchbase.

## Usage

### Requirements

A JVM and the [Leiningen](https://leiningen.org/) build tool need to be
installed. Gnuplot is required for the plot performance graphs option. Basic
utilities like bc, curl, grep are expected to be present. Clojure and Java
dependencies will be auto-fetched on first run. You need some Couchbase Server
nodes to run Couchbase Server. We provide the provision.sh script that starts
suitable nodes using vagrant VMs or docker containers. We also provide the
ability to run Jepsen using cluster-run type nodes from a local build. Note that
some workloads are incompatible with docker or cluster-run nodes.

### Setup for vagrant / docker nodes

Jepsen requires a cluster of nodes to run Couchbase Server on. If you already
have nodes available you need to manually create a nodes file with the IP
addresses. Otherwise you can use the script provision.sh that automatically
start suitable vagrants (or docker containers) and create the corresponding
file.

#### Getting started / Running a simple test

The provision script can be used to automatically start suitable VMs.
```
./provision.sh --type=vagrant --vm-os=ubuntu1604 --action=create --nodes=3
```

A copy of Couchbase Server is required:
```
wget http://packages.couchbase.com/releases/6.0.0/couchbase-server-enterprise_6.0.0-ubuntu16.04_amd64.deb
```

You can then invoke leiningen to run a test:
```
lein run test --nodes-file=./nodes --package=./couchbase-server-enterprise_6.0.0-ubuntu16.04_amd64.deb --workload=register
```

After the test are completed the vagrants the VMs can be torn down with:
```
./provision.sh --type=vagrant --action=destroy-all
```

#### Using 'suite' files

The script run.sh can be used to automatically run multiple tests from a config
file:
```
./provision.sh --type=vagrant --vm-os=ubuntu1604 --action=create --nodes=3
./run.sh --provisioner=vagrant --package=./couchbase-server-enterprise_6.0.0-ubuntu16.04_amd64.deb --suite=./suites/example.conf
```

The run.sh script can also be used with cluster-run nodes:
```
./run.sh --provisioner=cluster-run --package=~/dev/source/install --suite=./suites/example.conf --global=node-count:4
```

#### Using other nodes

To use other nodes provide the file ./nodes with one node IP per line. Test can
then be run with the following command (replacing user/pass with suitable ssh
credentials for the node).
```
./run.sh --provisioner=vmpool --package=./some-package.deb --suite=./some-suite.conf --global=username:user,password:pass
```

#### Running against a build directory
If the host has a directory containing a build suitable for running the nodes,
you can supply the install directory to jepsen instead of a deb/rpm package.
```
./run.sh ... --package ~/dev/source/install
```

### Setup for cluster-run

If downloading and building from source, cluster-run is supported providing a
simple way to start running tests.

*Note* that Jepsen will automatically start the required cluster-run nodes. No
other cluster-run nodes should be running on the machine, and any leftover data
from previous cluster-run nodes will be deleted.

Currently, due to an issue (JDCP-81) with the Java DCP client used by Jepsen,
set workloads crash if the server reports version 0.0.0, as is the default for
custom builds. This can be worked around by setting a product version when
building, for example with:
`EXTRA_CMAKE_OPTIONS='-DPRODUCT_VERSION="9.9.9-9999"'`

```
lein run test --cluster-run --node-count 3 --workload register --package ~/dev/source/install
```

### Displaying results
Start a server on `http://localhost:8080` to show the results with a simple
interface.
```
lein trampoline run serve
```

## Development
### Checking your commit
Before push your code for review its important to check that the patch will pass
our commit validation. To do this use:
```bash
chmod +x cv-checks.sh
./cv-checks.sh
```
This will run the same checks that are used on our Jenkins commit validation
job. Its also important if adding or editing a workload/nemesis that they are
tested, as the commit validator is unable to do this.

## FAQ
### What does Jepsen testing involve?
Jepsen testing involves launching client operations to a database across a set
of logically single-threaded processes and recording the operation history while
introducing faults such as network partitions into the system via a special
nemesis process.

The history is then analysed through a checker for correctness against a
[consistency model](https://jepsen.io/consistency). A consistency model defines
the set of legal histories that can be observed when the system conforms to the
consistency model specification.

The checker detects consistency errors by identifying histories that do not
conform to the consistency model when faults are introduced into the system.

For more details see [Jepsen](https://jepsen.io/), [Jepsen project](
https://github.com/jepsen-io/jepsen) and [Jepsen testing at Couchbase](
https://blog.couchbase.com/introduction-to-jepsen-testing-at-couchbase/).

### What checkers are used in testing?
For the register style workloads we model Couchbase Server as independent
compare-and-swap registers using a combination of the following checkers given
key-value read and write operations:

* [Knossos](https://github.com/jepsen-io/knossos) a linearizability checker.
* [Seqchecker](sequential_checker.md) a per register sequential consistency
  checker.

The extended set workloads model Couchbase Server as a set in which a key is a
member of the set if it has a corresponding value. The extended set checker
checks the intended items are present in the set following add and delete
operations.

The counter workloads model Couchbase Server as a counter variable. The sanity
counter checker checks if the counter holds the correct summation following
increment and decrement operations.

### Have we passed Jepsen?
Jepsen testing is not a proof of correctness, but instead detects errors in a
subset of the possible histories produced by the implementation. Our project
requires independent verification and perhaps additional tests before we can
sufficiently make such a claim.