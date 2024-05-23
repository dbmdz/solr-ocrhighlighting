#!/usr/bin/env python3
"""Small benchmarking script for Solr highlighting performance.

Generates a set of common two-term phrase queries from the Google 1000 dataset
and runs them against the Dockerized example setup. The script measures the time
spent on query execution and highlighting and prints the results to stdout.

If you want to profile the plugin:
- Download async-profiler: https://github.com/async-profiler/async-profiler
- Mount the async-profiler directory to the same location in the container as
  on your system
- Add these lines to the `solr` service in `docker-compose.yml`:
  ```
  security_opt:
    - seccomp:unconfined
  cap_add:
    - SYS_ADMIN
  ```
- Launch the container
- Find the PID of the Solr process on the host machine (use `ps` or `htop`)
- Launch the profiler: `${ASYNC_PROFILER_DIR}/asprof -d 60 -f /tmp/flamegraph.svg ${SOLRPID}`
"""

import argparse
import json
import os
import random
import statistics
import string
import sys
import xml.etree.ElementTree as etree

from concurrent.futures import ProcessPoolExecutor, ThreadPoolExecutor, as_completed
from multiprocessing import cpu_count
from pathlib import Path
from typing import Iterable, Mapping, NamedTuple, TextIO, cast
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from collections import Counter

NSMAP = {"mets": "http://www.loc.gov/METS/", "mods": "http://www.loc.gov/mods/v3"}
STRIP_PUNCTUATION_TBL = str.maketrans("", "", string.punctuation)


class BenchmarkResult(NamedTuple):
    query_times_ms: list[Mapping[str, float]]
    hl_times_ms: list[Mapping[str, float]]

    def mean_query_time(self) -> float:
        return statistics.mean(
            statistics.mean(query_times.values()) for query_times in self.query_times_ms
        )

    def mean_query_times(self) -> tuple[float, ...]:
        return tuple(
            statistics.mean(query_times.values()) for query_times in self.query_times_ms
        )

    def mean_hl_time(self) -> float:
        return statistics.mean(
            statistics.mean(hl_times.values()) for hl_times in self.hl_times_ms
        )

    def mean_hl_times(self) -> tuple[float, ...]:
        return tuple(
            statistics.mean(hl_times.values()) for hl_times in self.hl_times_ms
        )


def analyze_phrases(
    sections: Iterable[tuple[str, ...]],
    min_len=2,
    max_len=8,
    min_word_len=4,
    sample_size: int = 512,
) -> Mapping[tuple[str, ...], int]:
    counter: Counter[tuple[str, ...]] = Counter()
    for words in sections:
        for length in range(min_len, max_len + 1):
            for i in range(len(words) - length + 1):
                phrase = tuple(words[i : i + length])
                if all(len(word) < min_word_len for word in phrase):
                    continue
                counter[phrase] += 1
    filtered = [(phrase, count) for phrase, count in counter.items() if count > 4]
    return dict(
        random.sample(
            filtered,
            min(sample_size, len(filtered)),
        )
    )


def parse_hocr(hocr_path: Path) -> Iterable[tuple[str, ...]]:
    tree = etree.parse(hocr_path)
    for block in tree.findall('.//div[@class="ocrx_block"]'):
        words = [w for w in block.findall('.//span[@class="ocrx_word"]')]
        if len(words) == 0:
            continue
        passage = tuple(
            filtered
            for filtered in (
                w.text.translate(STRIP_PUNCTUATION_TBL)
                for w in words
                if w is not None and w.text is not None
            )
            if filtered
        )
        if len(passage) == 0:
            continue
        yield passage


def build_query_set(
    hocr_base_path: Path, min_count=8, max_count=256
) -> Iterable[tuple[str, int]]:
    # Counts in how many documents a phrase occurs
    phrase_counter = Counter()
    with ProcessPoolExecutor(max_workers=cpu_count()) as pool:
        futs = [
            pool.submit(lambda p: analyze_phrases(parse_hocr(p)), hocr_path)
            for hocr_path in hocr_base_path.glob("**/*.hocr")
        ]
        num_completed = 0
        for fut in as_completed(futs):
            num_completed += 1
            print(
                f"Analyzed {num_completed:>5}/{len(futs)} documents",
                file=sys.stderr,
                end="\r",
            )
            for phrase, count in fut.result().items():
                if count < 4:
                    continue
                phrase_counter[phrase] += 1

    # Selects phrases that occur in at least min_count documents
    for phrase, count in phrase_counter.items():
        if count < min_count or count > max_count:
            continue
        if len(phrase) == 1:
            yield phrase[0]
        else:
            yield f'"{" ".join(phrase)}"', count


def run_query(
    query: str, solr_handler: str, num_rows: int, num_snippets: int
) -> tuple[float, float]:
    query_params = {
        "q": f"ocr_text:{query}",
        "hl": "on",
        "hl.ocr.fl": "ocr_text",
        "hl.snippets": num_snippets,
        "rows": num_rows,
        "debug": "timing",
        "hl.weightMatches": "true",
    }
    req = Request(f"{solr_handler}?{urlencode(query_params)}")
    with urlopen(req) as http_resp:
        solr_resp = json.load(http_resp)
    hl_duration = solr_resp["debug"]["timing"]["process"]["ocrHighlight"]["time"]
    query_duration = solr_resp["debug"]["timing"]["time"]
    return query_duration, hl_duration


def run_benchmark(
    solr_handler: str,
    queries: set[str],
    iterations=3,
    concurrency=1,
    num_rows=50,
    num_snippets=5,
) -> BenchmarkResult:
    print(
        f"Running benchmark for {num_rows} rows with {num_snippets} snippets across {iterations} iterations and {concurrency} parallel requests",
        file=sys.stderr,
    )

    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        print(f"Running {iterations} benchmark iterations", file=sys.stderr)
        all_query_times = []
        all_hl_times = []

        def _run_query(query):
            return query, run_query(query, solr_handler, num_rows, num_snippets)

        for iteration_idx in range(iterations):
            iter_futs = [pool.submit(_run_query, query) for query in queries]

            query_times = {}
            hl_times = {}
            for idx, fut in enumerate(as_completed(iter_futs)):
                try:
                    query, (query_time, hl_time) = fut.result()
                except Exception as e:
                    print(f"\nError: {e}", file=sys.stderr)
                    continue
                query_times[query] = query_time
                hl_times[query] = hl_time
                hl_factor = statistics.mean(hl_times.values()) / statistics.mean(
                    query_times.values()
                )
                print(
                    f"Iteration {iteration_idx+1}: {idx+1:>4}/{len(queries)}, "
                    f"øq={statistics.mean(query_times.values()):.2f}ms, "
                    f"øhl={statistics.mean(hl_times.values()):.2f}ms, "
                    f"hl_share={hl_factor:.2f}",
                    file=sys.stderr,
                    end="\r",
                )
            all_query_times.append(query_times)
            all_hl_times.append(hl_times)

        return BenchmarkResult(all_query_times, all_hl_times)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawTextHelpFormatter
    )
    parser.add_argument(
        "--iterations",
        type=int,
        default=3,
        metavar="N",
        help="Number of benchmark iterations",
    )
    parser.add_argument(
        "--concurrency",
        type=int,
        default=1,
        metavar="N",
        help="Number of concurrent requests",
    )
    parser.add_argument(
        "--queries-path",
        type=str,
        default="./benchqueries.txt.gz",
        metavar="PATH",
        help="Path to the file containing the queries",
    )
    parser.add_argument(
        "--save-results",
        type=str,
        default=None,
        metavar="PATH",
        help="Path to save the results to as a JSON file (optional)",
    )
    parser.add_argument(
        "--num-rows",
        type=int,
        default=50,
        metavar="N",
        help="Number of rows to request from Solr",
    )
    parser.add_argument(
        "--num-snippets",
        type=int,
        default=5,
        metavar="N",
        help="Number of snippets to request from Solr",
    )
    parser.add_argument(
        "--solr-handler",
        type=str,
        default="http://localhost:8983/solr/ocr/select",
        help="URL to the Solr handler",
    )
    args = parser.parse_args()

    if os.path.exists(args.queries_path):
        if args.queries_path.endswith(".gz"):
            import gzip

            with gzip.open(args.queries_path, "rt") as f:
                queries = set(
                    q for q in (line.strip() for line in cast(TextIO, f)) if q
                )
        else:
            with open(args.queries_path, "rt") as f:
                queries = set(q for q in (line.strip() for line in f) if q)
    else:
        hocr_base_path = Path("./data/google1000")
        queries = set(q for q, _ in build_query_set(hocr_base_path))
        if args.queries_path.endswith(".gz"):
            import gzip

            with cast(TextIO, gzip.open(args.queries_path, "wt", compresslevel=9)) as f:
                f.write("\n".join(queries))
        else:
            with open(args.queries_path, "w") as f:
                f.write("\n".join(queries))

    results = run_benchmark(
        args.solr_handler,
        queries,
        iterations=args.iterations,
        concurrency=args.concurrency,
        num_rows=args.num_rows,
        num_snippets=args.num_snippets,
    )

    print("\n\n=====================================")
    print(f"Mean query time: {results.mean_query_time():.2f}ms")
    print(f"Mean highlighting time: {results.mean_hl_time():.2f}ms")
    print(
        f"Percent of time spent on highlighting: {results.mean_hl_time() / results.mean_query_time() * 100:.2f}%"
    )
    print("=====================================\n\n")

    if args.save_results:
        with open(args.save_results, "w") as f:
            json.dump(
                {
                    "query_times": results.query_times_ms,
                    "hl_times": results.hl_times_ms,
                },
                f,
            )
