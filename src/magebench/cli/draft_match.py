#!/usr/bin/env python3
"""Run repeated draft-then-play matches between two named model presets.

For each of N games: both presets draft a fresh 40-card deck from a real booster set
(an all-bot XMage tournament, headless — see orchestration/game_processes.py's
start_draft_client/wait_for_draft_completion and TablesPanel.
createConfiguredAiPuppeteerTournament() on the Java side), then play exactly one game
against each other with the resulting decks, through the normal pilot/orchestrator
machinery unchanged. Prints a final win/cost summary.

Usage:
    python -m magebench.cli.draft_match --preset-a dsv4pro-low --preset-b gpt56terra-medium --set TLA --games 10
"""

import argparse
import json
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

from magebench.common.log import get_logger, setup_logging
from magebench.common.port import find_available_port, wait_for_port
from magebench.common.process_manager import ProcessManager, jvm_oom_preexec_fn
from magebench.game.export_game import read_game_winner
from magebench.orchestration.config import Config, load_presets
from magebench.orchestration.game_processes import (
    start_draft_client,
    start_server,
    wait_for_draft_completion,
)
from magebench.orchestration.orchestrator import (
    clean_stale_h2_locks,
    compile_project,
    run_orchestrator,
)
from magebench.orchestration.xml_config import modify_server_config

logger = get_logger(__name__)

_ROOT = Path(__file__).resolve().parents[3]
_LOGS_DIR = Path.home() / ".mage-bench" / "logs"
_DRAFT_BOT_MODULE = "Mage.Server.Plugins/Mage.Player.AI.DraftBot"

# The play phase's pilot bridge logs in as a real XMage "user" (unlike the draft phase's
# LlmDraftPlayer seats, which are Player objects the tournament instantiates directly and
# never go through session login at all) — and the server enforces a 3-14 character
# username length. Draft seat names (f"{preset}-A"/"{preset}-B", used for per-seat JVM
# properties and drafted-deck filenames) have no such bound and can easily run past 14
# chars, so the play phase gets its own fixed, always-in-range login names instead of
# reusing them. Confirmed by hitting both ends of this range: a bare "A"/"B" (too short)
# and "gpt56luna-low-B" (16 chars, too long) both got silently rejected at login with no
# server-side log line at all — just the bridge's own "Logging: FAIL" after a fixed timeout.
_PILOT_A_NAME = "PilotA"
_PILOT_B_NAME = "PilotB"


def _compile_draft_bot(project_root: Path) -> bool:
    """Compile+install the LlmDraftPlayer module.

    compile_project() doesn't cover this module (Mage.Server doesn't declare a Maven
    build-time dependency on it — it's only loaded via config.xml's runtime classloading),
    so it would otherwise silently run a stale jar.
    """
    result = subprocess.run(
        ["mvn", "-q", "-DskipTests", "-pl", _DRAFT_BOT_MODULE, "-am", "install"],
        cwd=project_root,
        preexec_fn=jvm_oom_preexec_fn(),
    )
    return result.returncode == 0


def _model_for_preset(preset_name: str) -> str:
    presets = load_presets(None)["presets"]
    pdata = presets.get(preset_name)
    if pdata is None:
        raise ValueError(f"Unknown preset: {preset_name!r}. Available: {sorted(presets.keys())}")
    model = pdata.get("model")
    assert model, f"Preset {preset_name!r} has no model configured"
    return str(model)


def _timestamp() -> str:
    return datetime.now(ZoneInfo("America/Los_Angeles")).strftime("%Y%m%d_%H%M%S")


def run_draft(
    preset_a: str,
    preset_b: str,
    set_code: str,
    packs_per_player: int,
    project_root: Path,
) -> tuple[Path, Path, str, str]:
    """Run one headless all-bot draft tournament; return (deck_a, deck_b, seat_a_name, seat_b_name)."""
    draft_dir = _LOGS_DIR / f"draft_{_timestamp()}"
    draft_dir.mkdir(parents=True, exist_ok=True)

    config = Config()
    pm = ProcessManager()
    try:
        port_reservation = find_available_port(config.start_port)
        config.port = port_reservation.port
        server_config_path = draft_dir / "server_config.xml"
        modify_server_config(
            source=project_root / "Mage.Server" / "config" / "config.xml",
            destination=server_config_path,
            port=config.port,
        )
        server_log = draft_dir / "server.log"
        logger.info("Starting draft server on port %d...", config.port)
        start_server(pm, project_root, config, server_config_path, server_log)
        if not wait_for_port(config.server, config.port, config.server_wait):
            raise RuntimeError(f"Draft server failed to start within {config.server_wait}s — check {server_log}")
        port_reservation.release()

        seat_a_name = f"{preset_a}-A"
        seat_b_name = f"{preset_b}-B"
        model_a = _model_for_preset(preset_a)
        model_b = _model_for_preset(preset_b)

        client_log = draft_dir / "client.log"
        since = time.time()
        logger.info(
            "Starting draft: %s (%s) vs %s (%s) from set %s (%d packs each)...",
            seat_a_name,
            model_a,
            seat_b_name,
            model_b,
            set_code,
            packs_per_player,
        )
        proc = start_draft_client(
            pm,
            project_root,
            config,
            seat_a_name=seat_a_name,
            seat_a_model=model_a,
            seat_b_name=seat_b_name,
            seat_b_model=model_b,
            set_code=set_code,
            log_path=client_log,
            packs_per_player=packs_per_player,
        )
        deck_a, deck_b = wait_for_draft_completion(project_root, seat_a_name, seat_b_name, since, proc)
        logger.info("Draft complete: %s, %s", deck_a.name, deck_b.name)
        return deck_a, deck_b, seat_a_name, seat_b_name
    finally:
        pm.cleanup()


def build_game_config(
    preset_a: str,
    preset_b: str,
    deck_a: Path,
    deck_b: Path,
    project_root: Path,
    index: int,
) -> Path:
    """Build a game config JSON for one draft-match game. Mirrors the shape every other
    configs/*.json file in this repo uses — see cli/tournament_game.py's
    build_game_config() for the reference this was modeled on.

    Pilot names are the fixed _PILOT_A_NAME/_PILOT_B_NAME, not the (potentially too-long)
    draft seat names — see the module docstring comment above those constants for why.
    """
    config = {
        "skipPostGamePrompts": True,
        "gameType": "Two Player Duel",
        "deckType": "Limited",
        "players": [
            {"type": "pilot", "name": _PILOT_A_NAME, "preset": preset_a, "deck": str(deck_a)},
            {"type": "pilot", "name": _PILOT_B_NAME, "preset": preset_b, "deck": str(deck_b)},
        ],
    }
    config_dir = project_root / "tmp" / "draft-match-configs"
    config_dir.mkdir(parents=True, exist_ok=True)
    config_path = config_dir / f"game{index}.json"
    config_path.write_text(json.dumps(config, indent=2) + "\n")
    return config_path


def main() -> int:
    setup_logging()
    parser = argparse.ArgumentParser(description="Run repeated draft-then-play matches between two model presets")
    parser.add_argument("--preset-a", required=True, help="Preset name from puppeteer/presets.json")
    parser.add_argument("--preset-b", required=True, help="Preset name from puppeteer/presets.json")
    parser.add_argument("--set", required=True, dest="set_code", help="Set code to draft from, e.g. TLA")
    parser.add_argument("--games", type=int, default=1)
    parser.add_argument("--packs-per-player", type=int, default=3)
    args = parser.parse_args()

    logger.info("Compiling project...")
    if not compile_project(_ROOT, observer=True) or not _compile_draft_bot(_ROOT):
        logger.error("Compilation failed")
        return 1

    wins = {args.preset_a: 0, args.preset_b: 0}
    total_cost = 0.0
    games_completed = 0

    for i in range(1, args.games + 1):
        print(f"\n{'=' * 60}\nGame {i}/{args.games}: {args.preset_a} vs {args.preset_b}\n{'=' * 60}")
        try:
            # Draft seat names (3rd/4th values) only matter to the draft phase itself
            # (per-seat JVM properties, drafted-deck filenames) - the play phase logs
            # in under its own fixed _PILOT_A_NAME/_PILOT_B_NAME, so they're discarded here.
            deck_a, deck_b, _, _ = run_draft(
                args.preset_a, args.preset_b, args.set_code, args.packs_per_player, _ROOT
            )
        except (RuntimeError, TimeoutError) as exc:
            logger.error("Game %d: draft failed: %s", i, exc)
            continue

        clean_stale_h2_locks(_ROOT)
        config_path = build_game_config(args.preset_a, args.preset_b, deck_a, deck_b, _ROOT, i)
        result = run_orchestrator(
            Config(config_file=config_path, observer=True, record=False, skip_compile=True),
            project_root=_ROOT,
        )
        if result.exit_code != 0 or not result.sessions:
            logger.error("Game %d: play phase failed (exit code %d)", i, result.exit_code)
            continue

        session = result.sessions[0]
        winner_name = read_game_winner(session.game_dir)
        cost = sum(result.pilot_costs.values())
        total_cost += cost
        games_completed += 1
        if winner_name == _PILOT_A_NAME:
            wins[args.preset_a] += 1
        elif winner_name == _PILOT_B_NAME:
            wins[args.preset_b] += 1
        else:
            logger.warning("Game %d: no clear winner recorded (%r)", i, winner_name)
        print(f"Game {i} winner: {winner_name}  cost: ${cost:.4f}")

    print(f"\n{'=' * 60}\nFINAL RESULTS ({games_completed}/{args.games} games completed)\n{'=' * 60}")
    print(f"  {args.preset_a}: {wins[args.preset_a]} wins")
    print(f"  {args.preset_b}: {wins[args.preset_b]} wins")
    print(f"  Total cost: ${total_cost:.4f}")
    print(f"{'=' * 60}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
