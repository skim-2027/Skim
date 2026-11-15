#!/usr/bin/env python3
"""Run Tai-e pointer-analysis experiments sequentially."""

import argparse
import csv
import json
from datetime import datetime
import os
from pathlib import Path
import re
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
BENCHMARK_INFO = ROOT / "java-benchmarks/benchmark-info.json"
OUTPUT = ROOT / "output/experiments"
SUMMARY = OUTPUT / "summary.csv"
COLUMNS = [
    "experiment_id", "pointer_analysis_seconds",
    "reachable_methods", "call_graph_edges", "poly_call_all",
    "may_fail_cast_all", "start_time", "end_time", "exit_code",
]
CONFIGS = {
    "ci": "cs:ci",
    **{f"{k}-obj": f"cs:{k}-obj" for k in (2, 3, 4)},
    **{f"{k}-moon": f"cs:{k}-obj;advanced:moon" for k in (2, 3, 4)},
    **{f"{k}-skim": f"cs:{k}-obj;advanced:skim" for k in (2, 3, 4)},
    "tunnel": "cs:1-obj-1h;advanced:tunneling",
}
UNSCALABLE = {
    "2-obj": {"fop", "jedit-3.0", "jme"},
    "3-obj": "all",
    "4-obj": "all",
    "3-moon": {"chart", "classyshark", "fop", "jedit-3.0", "jme", "JPC", "sunflow"},
    "4-moon": "all",
    "4-skim": {"classyshark", "fop", "jedit-3.0", "jme", "JPC", "lusearch", "sunflow"},
}


def is_unscalable(benchmark, config):
    parts = config.split("-")
    if len(parts) == 3 and parts[1] == "skim" and int(parts[2]) == 2:
        config = "-".join(parts[:2])
    benchmarks = UNSCALABLE.get(config, set())
    return benchmarks == "all" or benchmark in benchmarks


def config_options(config):
    if config in CONFIGS:
        return CONFIGS[config]
    parts = config.split("-")
    if len(parts) == 3:
        depth, analysis, rounds = parts
        if depth in ("2", "3", "4") and analysis == "skim" and rounds.isdecimal() and int(rounds) > 0:
            return CONFIGS[f"{depth}-skim"] + f";skim-max-rounds:{int(rounds)}"
    raise ValueError(f"unknown configuration: {config}")


def parse_arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("benchmarks", nargs="*", help="benchmark IDs; default: all")
    parser.add_argument("--config", nargs="+", default=list(CONFIGS),
                        help="configurations to run, e.g. ci, 2-skim, 2-skim-1; default: all")
    parser.add_argument("--memory", default="256g", help="maximum JVM heap")
    parser.add_argument("--timeout", type=int, default=5400, help="seconds per run")
    args = parser.parse_args()
    for config in args.config:
        try:
            config_options(config)
        except ValueError as error:
            parser.error(str(error))
    if args.timeout <= 0:
        parser.error("timeout must be positive")
    return args


def resolve_path(value):
    path = Path(value)
    base = ROOT if path.parts[0] == "java-benchmarks" else BENCHMARK_INFO.parent
    path = (base / path).resolve()
    if not path.exists():
        raise FileNotFoundError(path)
    return path


def resolve_benchmarks(args):
    benchmarks = {b["id"]: b for b in json.loads(BENCHMARK_INFO.read_text())}
    resolved = []
    for name in args.benchmarks or benchmarks:
        if name not in benchmarks:
            raise ValueError(f"unknown benchmark: {name}")
        info = dict(benchmarks[name])
        info["jdk"] = info.get("jdk")
        info["classpath"] = []
        for entry in info.get("apps", []) + info.get("libs", []):
            for part in entry.split(os.pathsep):
                path = resolve_path(part)
                info["classpath"].append(path)
                if path.is_dir():
                    info["classpath"].extend(path.glob("*.jar"))
        if info.get("refl-log"):
            info["refl-log"] = resolve_path(info["refl-log"])
        resolved.append(info)
    return resolved


def prepare_jar():
    jar = ROOT / "build/tai-e-all-0.5.4.jar"
    if not jar.is_file():
        subprocess.run(["./gradlew", "fatJar"], cwd=ROOT, check=True)
    return jar


def make_command(args, info, config, jar, output):
    options = config_options(config) + f";time-limit:{args.timeout}"
    if info.get("refl-log"):
        options += f";reflection-log:{info['refl-log']}"
    command = ["java", f"-Xmx{args.memory}", "-jar", str(jar), "-java", str(info["jdk"])]
    for path in info["classpath"]:
        command += ["-cp", str(path)]
    command += ["-m", info["main"], "--output-dir", str(output)]
    if info.get("allowPhantom", info.get("phantom", False)):
        command.append("--allow-phantom")
    return command + ["-a", "pta=" + options, "-a", "poly-call", "-a", "may-fail-cast"]


def read_metrics(log):
    patterns = {
        "pointer_analysis_seconds": r"^\[Pointer analysis\] elapsed time: ([\d.]+)s;",
        "reachable_methods": r"^#reachable methods:\s*([\d,]+) \(insens\)",
        "call_graph_edges": r"^#call graph edges:\s*([\d,]+) \(insens\)",
        "poly_call_all": r"^#poly-call: found ([\d,]+) in [\d,]+ reachable relevant Stmts$",
        "may_fail_cast_all": r"^#may-fail-cast: found ([\d,]+) in [\d,]+ reachable relevant Stmts$",
    }
    metrics = {}
    for column, pattern in patterns.items():
        matches = re.findall(pattern, log, re.MULTILINE)
        if matches:
            metrics[column] = matches[-1].replace(",", "")
    return metrics


def append_summary(info, config, log, status, start, end):
    options = dict(part.split(":", 1) for part in config_options(config).split(";"))
    advanced = options.get("advanced", "none")
    rounds = options.get("skim-max-rounds", "2")
    if advanced == "skim" and rounds != "2":
        advanced += f"-r{rounds}"
    row = {
        "experiment_id": f"{info['id']}__{options['cs']}__{advanced}__jdk{info['jdk']}",
        "start_time": start.strftime("%m-%d %H-%M-%S"),
        "end_time": end.strftime("%m-%d %H-%M-%S"),
        "exit_code": {"0": "normally exited", "timeout": "OOT"}.get(status, status),
    }
    if re.search(r"(?:^|\s)java\.lang\.OutOfMemoryError(?=[:\s]|$)", log):
        row["exit_code"] = "OOM"
    if "Pointer analysis stops early as it reaches time limit" in log:
        row["exit_code"] = "OOT"
    if row["exit_code"] == "normally exited":
        metrics = read_metrics(log)
        if len(metrics) == 5 and "[Tai-e] elapsed time:" in log:
            row.update(metrics)
        else:
            row["exit_code"] = "incomplete"
    new_file = not SUMMARY.exists() or SUMMARY.stat().st_size == 0
    with SUMMARY.open("a", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=COLUMNS)
        if new_file:
            writer.writeheader()
        writer.writerow(row)
    return row["exit_code"] == "normally exited"


def run_experiment(args, info, config, jar):
    output = OUTPUT / info["id"] / config
    print("running experiment:", info["id"], config, "->", output)
    if output.exists():
        print("skipping experiment: output directory already exists")
        return True
    output.mkdir(parents=True, exist_ok=False)
    command = make_command(args, info, config, jar, output)
    
    start = datetime.now()
    with (output / "run.log").open("w") as log:
        try:
            result = subprocess.run(command, cwd=ROOT, stdout=log,
                                    stderr=subprocess.STDOUT, timeout=args.timeout)
            status = str(result.returncode)
        except subprocess.TimeoutExpired:
            status = "timeout"
        except OSError as error:
            log.write(str(error) + "\n")
            status = "launch-error"
    end = datetime.now()
    (output / "status.txt").write_text(status + "\n")
    return append_summary(info, config, (output / "run.log").read_text(errors="replace"),
                          status, start, end)


def main():
    args = parse_arguments()
    benchmarks = resolve_benchmarks(args)
    jar = None
    failures = []
    for info in benchmarks:
        for config in args.config:
            if is_unscalable(info["id"], config):
                print(f"Skipping {info['id']}/{config}: presumed unscalable")
                continue
            if jar is None:
                jar = prepare_jar()
            if not run_experiment(args, info, config, jar):
                failures.append(f"{info['id']}/{config}")
    if failures:
        print("Failed runs: " + ", ".join(failures), file=sys.stderr)
    return bool(failures)


if __name__ == "__main__":
    sys.exit(main())
