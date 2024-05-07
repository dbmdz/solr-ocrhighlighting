#!/usr/bin/env python3
import argparse
import json
import os
import random
import statistics
import sys
import xml.etree.ElementTree as etree

from concurrent.futures import ProcessPoolExecutor, ThreadPoolExecutor, as_completed
from multiprocessing import cpu_count
from pathlib import Path
from typing import Iterable, Mapping, NamedTuple
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from collections import Counter

NSMAP = {"mets": "http://www.loc.gov/METS/", "mods": "http://www.loc.gov/mods/v3"}


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
        passage = tuple(w.text for w in words if w is not None)
        if len(passage) == 0:
            continue
        yield passage


def _queryset_worker(hocr_path: Path) -> Mapping[tuple[str, ...], int]:
    return analyze_phrases(parse_hocr(hocr_path))


def build_query_set(
    hocr_base_path: Path, min_count=8, max_count=256
) -> Iterable[tuple[str, int]]:
    # Counts in how many documents a phrase occurs
    phrase_counter = Counter()
    with ProcessPoolExecutor(max_workers=cpu_count()) as pool:
        futs = [
            pool.submit(_queryset_worker, hocr_path)
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


def run_query(query: str, solr_handler: str) -> tuple[float, float]:
    query_params = {
        "q": f"ocr_text:{query}",
        "hl": "on",
        "hl.ocr.fl": "ocr_text",
        "hl.snippets": 5,
        "rows": 50,
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
    warmup_iters=1,
    concurrency=1,
) -> BenchmarkResult:
    print(
        f"Running benchmark with {iterations} iterations and {concurrency} parallel requests",
        file=sys.stderr,
    )

    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        if warmup_iters > 0:
            print(f"Running {warmup_iters} warmup iterations", file=sys.stderr)
            for idx in range(warmup_iters):
                for idx, query in enumerate(queries):
                    print(
                        f"Warmup iteration {idx+1}: {idx+1:>4}/{len(queries)}",
                        file=sys.stderr,
                        end="\r",
                    )
                    run_query(query, solr_handler)

        print(f"Running {iterations} benchmark iterations", file=sys.stderr)
        all_query_times = []
        all_hl_times = []

        def _run_query(query):
            return query, run_query(query, solr_handler)

        for iteration_idx in range(iterations):
            iter_futs = [
                pool.submit(_run_query, query)
                for query in queries
            ]

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
                hl_factor = statistics.mean(hl_times.values()) / statistics.mean(query_times.values())
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
    parser = argparse.ArgumentParser()
    parser.add_argument("--iterations", type=int, default=3)
    parser.add_argument("--warmup-iterations", type=int, default=1)
    parser.add_argument("--concurrency", type=int, default=1)
    parser.add_argument("--queries-path", type=str, default="./benchqueries.txt")
    parser.add_argument("--save-results", type=str, default=None)
    parser.add_argument("--solr-handler", type=str, default="http://localhost:8983/solr/ocr/select")
    args = parser.parse_args()

    if os.path.exists(args.queries_path):
        with open(args.queries_path, "r") as f:
            queries = set(x for x in f.read().split("\n") if x.strip())
    else:
        hocr_base_path = Path("./data/google1000")
        queries = set(q for q, _ in build_query_set(hocr_base_path))
        with open(args.queries_path, "w") as f:
            f.write("\n".join(queries))

    results = run_benchmark(
        args.solr_handler,
        queries,
        iterations=args.iterations,
        warmup_iters=args.warmup_iterations,
        concurrency=args.concurrency,
    )

    print("\n\n=====================================")
    print(f"Mean query time: {results.mean_query_time():.2f}ms")
    print(f"Mean highlighting time: {results.mean_hl_time():.2f}ms")
    print(f"Percent of time spent on highlighting: {results.mean_hl_time() / results.mean_query_time() * 100:.2f}%")

    if args.save_results:
        with open(args.save_results, "w") as f:
            json.dump(
                {
                    "query_times": results.query_times_ms,
                    "hl_times": results.hl_times_ms,
                },
                f,
            )