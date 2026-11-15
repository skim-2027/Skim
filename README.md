# Adaptive Object Sensitivity for Pointer Analysis (Artifact Document)

## 1. Introduction

This artifact supports reproducing **RQ1–RQ3 in Section 5** of our paper. We implemented Skim atop [Tai-e](https://github.com/pascal-lab/Tai-e) and evaluated against (1) conventional object-sensitive pointer analysis, (2) [Moon](https://dl.acm.org/doi/10.1145/3763111), and (3) [Context Tunneling](https://doi.org/10.1145/3276510).

It requires **JDK 17** and **Python 3.9+**, with **Gradle** for building Tai-e.
### 1.1 Benchmarks

Details of all 21 benchmarks can be found in `java-benchmarks/benchmark-info.json`, including their names, main classes, JAR paths, reflection logs.

To decompress the benchmarks, execute: 
```sh
tar -zxvf benchmarks.tar.gz
```

### 1.2 Smoke Test

Experiments use `--memory 256g` and `--timeout 5400` by default, for a small smoke test:

```sh
python3 scripts/run_experiments.py antlr --config 2-skim --memory 8g
```

## 2. Getting Started Guide

### 2.1 Directory Structure

| Path | Contents |
|---|---|
| `src/main/java/pascal/taie/analysis/pta/toolkit/skim/` | Skim implementation |
| `src/main/java/pascal/taie/analysis/pta/toolkit/moon/` | Moon implementation |
| `src/main/java/pascal/taie/analysis/pta/toolkit/tunneling/` | Context Tunneling model and features (by carefully following the original [artifact](https://dl.acm.org/do/10.1145/3276931/full) )|
| `java-benchmarks/` | Benchmark programs, reflection logs, and Java models |
| `scripts/run_experiments.py` | Experiment runner |
| `output/experiments/` | Experiment logs and summarys |


### 2.2 Basic Commands


#### 2.2.1 Running Benchmarks for RQ1 and RQ2

##### Context-Insensitive Analysis

```sh
python3 scripts/run_experiments.py {benchmark_name} --config ci
```

##### Conventional Object Sensitivity

```sh
python3 scripts/run_experiments.py {benchmark_name} --config 2-obj 3-obj 4-obj
```

##### Skim

```sh
python3 scripts/run_experiments.py {benchmark_name} --config 2-skim 3-skim 4-skim
```

These correspond to S-2o, S-3o, and S-4o. The maximum derivation-layer limit
is **2** by default.

##### Moon

```sh
python3 scripts/run_experiments.py {benchmark_name} --config 2-moon 3-moon 4-moon
```

These correspond to M-2o, M-3o, and M-4o.

##### Context Tunneling

```sh
python3 scripts/run_experiments.py {benchmark_name} --config tunnel
```

This correspond to Tun. Note that this uses the pretrained model from the original artifact, no training is required.


##### Running All Benchmarks for All Approaches

```sh
python3 scripts/run_experiments.py
```

This selects CI, conventional object sensitivity, Moon, Skim, and tunneling
across all 21 benchmarks.


#### 2.2.2 Derivation-Layer Experiments for RQ3

To reproduce **Section 5.3 and Table 2**, use **3-object-sensitivity** and specify different the maximum derivation-layer limit:

```sh
python3 scripts/run_experiments.py --config 3-skim-1 3-skim-2 3-skim-3 3-skim-4
```
where `3-skim-I` sets `skim-max-rounds:I`.

### 2.3 Results

Each run produces logs under `output/experiments/<benchmark>/<configuration>/`. A summary report is provided in `output/experiments/summary.csv`:

| Column | Meaning |
|---|---|
| `experiment_id` | Benchmark id|
| `pointer_analysis_seconds` | Pointer-analysis time |
| `may_fail_cast_all` | #FC: potentially failing casts |
| `poly_call_all` | #PC: polymorphic virtual call sites |
| `reachable_methods` | #RM: reachable methods |
| `call_graph_edges` | #CE: call-graph edges |
| `start_time`, `end_time`, `exit_code` | Elapsed Time and exit code |

