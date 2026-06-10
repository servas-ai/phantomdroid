# experiments/akka_eval/mock_actors.py
#
# A lightweight Actor Model prototype built on asyncio to evaluate
# supervision and failure-isolation mechanics for the ReDroid orchestrator.

import asyncio
import random
import logging
from typing import Dict, Any, Optional

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] (%(name)s) %(message)s")
logger = logging.getLogger("Supervisor")


class Actor:
    """Base class for all lightweight asyncio actors."""
    def __init__(self, name: str):
        self.name = name
        self.queue: asyncio.Queue = asyncio.Queue()
        self._task: Optional[asyncio.Task] = None
        self.log = logging.getLogger(name)

    def start(self):
        self._task = asyncio.create_task(self._run_loop())
        self.log.debug("Actor started")

    async def send(self, msg: Any):
        await self.queue.put(msg)

    async def _run_loop(self):
        try:
            while True:
                msg = await self.queue.get()
                try:
                    await self.receive(msg)
                except Exception as e:
                    await self.handle_failure(msg, e)
                finally:
                    self.queue.task_done()
        except asyncio.CancelledError:
            self.log.debug("Actor stopped")

    async def receive(self, msg: Any):
        raise NotImplementedError

    async def handle_failure(self, msg: Any, exc: Exception):
        self.log.error(f"Failed processing message {msg}: {exc}")

    def stop(self):
        if self._task:
            self._task.cancel()


class PortManager(Actor):
    """Actor responsible for managing unique port allocations."""
    def __init__(self):
        super().__init__("PortManager")
        self.free_ports = [5555, 5557, 5559, 5561]
        self.allocated: Dict[int, Actor] = {}

    async def receive(self, msg: Any):
        action = msg.get("action")
        if action == "request":
            reply_to = msg["reply_to"]
            if self.free_ports:
                port = self.free_ports.pop(0)
                self.allocated[port] = reply_to
                self.log.info(f"Allocated port {port} to {reply_to.name}")
                await reply_to.send({"action": "port_allocated", "port": port})
            else:
                self.log.warning(f"No ports available for {reply_to.name}, placing back in queue")
                # Wait and retry request
                await asyncio.sleep(0.5)
                await self.send(msg)

        elif action == "release":
            port = msg["port"]
            if port in self.allocated:
                actor = self.allocated.pop(port)
                self.free_ports.append(port)
                self.free_ports.sort()
                self.log.info(f"Released port {port} from {actor.name}")


class CellActor(Actor):
    """Actor representing the lifecycle of a single ReDroid container run."""
    def __init__(self, name: str, config_id: str, run_id: int, supervisor: Actor, port_manager: Actor):
        super().__init__(name)
        self.config_id = config_id
        self.run_id = run_id
        self.supervisor = supervisor
        self.port_manager = port_manager
        self.port: Optional[int] = None
        self.state = "INIT"

    async def receive(self, msg: Any):
        action = msg.get("action")
        
        if action == "start":
            self.state = "REQUESTING_PORT"
            self.log.info("Starting lifecycle - requesting port")
            await self.port_manager.send({"action": "request", "reply_to": self})
            
        elif action == "port_allocated":
            self.port = msg["port"]
            self.state = "BOOTING"
            self.log.info(f"Port {self.port} allocated. Booting container compose...")
            
            # Simulate boot with potential random failures
            asyncio.create_task(self._simulate_boot())
            
        elif action == "boot_finished":
            success = msg["success"]
            if success:
                self.state = "RUNNING_PROBES"
                self.log.info("Container booted successfully. Running probes...")
                asyncio.create_task(self._simulate_probes())
            else:
                self.state = "FAILED"
                self.log.error("Container boot failed! Notifying supervisor.")
                await self.supervisor.send({"action": "cell_failed", "cell": self, "reason": "boot_timeout"})
                
        elif action == "probes_finished":
            self.state = "TEARDOWN"
            self.log.info("Probes completed successfully. Tearing down...")
            asyncio.create_task(self._simulate_teardown())
            
        elif action == "teardown_finished":
            self.state = "COMPLETED"
            self.log.info("Teardown completed. Releasing port and notifying supervisor.")
            if self.port:
                await self.port_manager.send({"action": "release", "port": self.port})
            await self.supervisor.send({"action": "cell_completed", "cell": self})

    async def _simulate_boot(self):
        # Simulate boot delay
        await asyncio.sleep(random.uniform(0.5, 1.5))
        # Boot fails 20% of the time to test supervisor strategy
        success = random.random() > 0.2
        await self.send({"action": "boot_finished", "success": success})

    async def _simulate_probes(self):
        await asyncio.sleep(random.uniform(0.5, 1.0))
        await self.send({"action": "probes_finished"})

    async def _simulate_teardown(self):
        await asyncio.sleep(0.5)
        await self.send({"action": "teardown_finished"})


class OrchestratorSupervisor(Actor):
    """Parent supervisor actor managing the testing run."""
    def __init__(self, port_manager: Actor):
        super().__init__("OrchestratorSupervisor")
        self.port_manager = port_manager
        self.active_cells: Dict[str, CellActor] = {}
        self.retry_counts: Dict[str, int] = {}
        self.max_retries = 2
        self.done_event = asyncio.Event()

    async def receive(self, msg: Any):
        action = msg.get("action")
        
        if action == "run_suite":
            configs = msg["configs"]
            self.log.info(f"Starting suite for configs: {configs}")
            for i, cfg in enumerate(configs):
                cell_name = f"CellActor-{cfg}-{i}"
                cell = CellActor(cell_name, cfg, i, self, self.port_manager)
                cell.start()
                self.active_cells[cell_name] = cell
                self.retry_counts[cell_name] = 0
                await cell.send({"action": "start"})
                
        elif action == "cell_completed":
            cell = msg["cell"]
            self.log.info(f"Cell {cell.name} completed successfully.")
            cell.stop()
            self.active_cells.pop(cell.name, None)
            if not self.active_cells:
                self.log.info("All cells finished suite run.")
                self.done_event.set()
                
        elif action == "cell_failed":
            cell = msg["cell"]
            reason = msg["reason"]
            retries = self.retry_counts.get(cell.name, 0)
            
            # Supervise: Apply restart policy
            if retries < self.max_retries:
                self.retry_counts[cell.name] = retries + 1
                self.log.warning(f"Supervision: Cell {cell.name} failed due to {reason}. Restarting cell (Attempt {retries + 1}/{self.max_retries}).")
                
                # Perform cleanup of old cell resources if needed
                if cell.port:
                    await self.port_manager.send({"action": "release", "port": cell.port})
                
                # Re-issue start to same cell actor to retry
                await cell.send({"action": "start"})
            else:
                self.log.error(f"Supervision: Cell {cell.name} failed and exceeded max retries. Escalating/stopping.")
                cell.stop()
                self.active_cells.pop(cell.name, None)
                if not self.active_cells:
                    self.log.info("All cells finished suite run.")
                    self.done_event.set()


async def main():
    logger.info("Initializing prototype actor-based orchestrator...")
    
    port_manager = PortManager()
    port_manager.start()
    
    supervisor = OrchestratorSupervisor(port_manager)
    supervisor.start()
    
    # Run a test suite with 6 configurations
    # (Since port pool has 4 ports, this will also test resource waiting/throttling)
    await supervisor.send({"action": "run_suite", "configs": ["L0a", "L0b", "L1", "L2", "L3", "L4"]})
    
    # Wait until supervisor reports all complete
    await supervisor.done_event.wait()
    
    # Stop actors
    port_manager.stop()
    supervisor.stop()
    logger.info("Evaluation simulation complete.")

if __name__ == "__main__":
    asyncio.run(main())
