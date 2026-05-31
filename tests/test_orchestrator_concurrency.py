"""Unit tests for orchestrator concurrency pool (SPEC §10)."""
import asyncio
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from agents.orchestrator.src.concurrency import (  # noqa: E402
    PortPool, PortExhausted, compose_project_name, run_pool,
    MAX_CONTAINERS_PER_BINDER,
)


def test_port_pool_allocates_unique_odd_ports():
    pool = PortPool()
    a, b, c = pool.acquire(), pool.acquire(), pool.acquire()
    assert (a, b, c) == (5555, 5557, 5559)
    assert len({a, b, c}) == 3
    assert pool.in_use == {5555, 5557, 5559}


def test_port_pool_release_recycles():
    pool = PortPool()
    p = pool.acquire()
    pool.release(p)
    assert pool.acquire() == p  # recycled deterministically
    assert pool.in_use == {p}


def test_port_pool_exhaustion():
    pool = PortPool(start=5555, end=5557, step=2)  # only 5555, 5557
    pool.acquire(); pool.acquire()
    with pytest.raises(PortExhausted):
        pool.acquire()


def test_compose_project_name():
    assert compose_project_name("L0a-L1", "ABCDEFGH1234") == "l0a-l1-abcdefgh"


def test_run_pool_caps_concurrency():
    """At most MAX_CONTAINERS_PER_BINDER workers run simultaneously."""
    live = 0
    peak = 0

    async def worker(cell, port):
        nonlocal live, peak
        live += 1
        peak = max(peak, live)
        await asyncio.sleep(0.01)
        live -= 1
        return {"cell": cell, "port": port}

    cells = list(range(12))
    results = asyncio.run(run_pool(cells, worker, max_concurrent=MAX_CONTAINERS_PER_BINDER))
    assert len(results) == 12
    assert peak <= MAX_CONTAINERS_PER_BINDER  # never more than 4 at once
    # every result got a valid pooled port, all distinct-at-a-time (released+reused ok)
    assert all(5555 <= r["port"] <= 5755 for r in results)
    # results preserved input order
    assert [r["cell"] for r in results] == cells


def test_run_pool_captures_worker_errors():
    async def boom(cell, port):
        raise ValueError("kaboom")

    results = asyncio.run(run_pool([1, 2], boom, max_concurrent=2))
    assert all("error" in r and "kaboom" in r["error"] for r in results)
