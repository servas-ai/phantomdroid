# agents/orchestrator/src/concurrency.py
#
# Binder-device-aware concurrency model (SPEC §10).
#
# - Hard cap: 4 simultaneous containers per binder device (layers.md L0).
# - asyncio event loop with a bounded semaphore.
# - Unique compose project name per cell: ${config_id}-${run_id_short}.
# - ADB ports from pool [5555..5755] step 2 (odd-only), allocate/release tracked.
# - One container = one ADB-server scope (each cell gets its own port → no cross-talk).

from __future__ import annotations

import asyncio
from typing import Awaitable, Callable

MAX_CONTAINERS_PER_BINDER = 4
ADB_PORT_START = 5555
ADB_PORT_END = 5755
ADB_PORT_STEP = 2


class PortExhausted(RuntimeError):
    """Raised when the ADB port pool has no free port (SPEC §10 pool bound)."""


class PortPool:
    """Allocates unique ADB ports from [5555..5755] step 2. Thread/await-safe via caller serialization."""

    def __init__(self, start: int = ADB_PORT_START, end: int = ADB_PORT_END, step: int = ADB_PORT_STEP):
        self._free = list(range(start, end + 1, step))
        self._in_use: set[int] = set()

    def acquire(self) -> int:
        if not self._free:
            raise PortExhausted(f"no free ADB port (in use: {sorted(self._in_use)})")
        port = self._free.pop(0)
        self._in_use.add(port)
        return port

    def release(self, port: int) -> None:
        if port in self._in_use:
            self._in_use.discard(port)
            # keep ordering stable so allocation is deterministic
            self._free.append(port)
            self._free.sort()

    @property
    def in_use(self) -> set[int]:
        return set(self._in_use)


def compose_project_name(config_id: str, run_id: str) -> str:
    """Unique compose project per cell (SPEC §10): config_id + short run_id."""
    return f"{config_id}-{run_id[:8]}".lower()


async def run_pool(
    cells: list,
    worker: Callable[[object, int], Awaitable[dict]],
    max_concurrent: int = MAX_CONTAINERS_PER_BINDER,
    port_pool: PortPool | None = None,
) -> list[dict]:
    """Run `worker(cell, port)` over cells with at most `max_concurrent` in flight.

    Each task acquires a unique ADB port for its lifetime and releases it after.
    Returns results in input order. A worker raising is captured as {'error': ...}.
    """
    if max_concurrent < 1:
        raise ValueError("max_concurrent must be >= 1")
    sem = asyncio.Semaphore(max_concurrent)
    pool = port_pool or PortPool()
    results: list[dict | None] = [None] * len(cells)

    async def _one(idx: int, cell: object) -> None:
        async with sem:
            port = pool.acquire()
            try:
                results[idx] = await worker(cell, port)
            except Exception as exc:  # capture, don't crash the whole pool
                results[idx] = {"error": f"{type(exc).__name__}: {exc}"}
            finally:
                pool.release(port)

    await asyncio.gather(*[_one(i, c) for i, c in enumerate(cells)])
    return [r if r is not None else {"error": "no result"} for r in results]
