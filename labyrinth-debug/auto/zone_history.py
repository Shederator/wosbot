"""
Persistent per-stage knowledge base for the Labyrinth zones.

matt's request (2026-08-12): "why did we lose 3-8 ... that should be stored somewhere
so we could review it next time ... so the bot doesn't just waste another attempt."

This is separate from labyrinth_log.json (which is a per-run activity summary). This
file (labyrinth_stage_history.json) is a durable knowledge base keyed by
zone -> stage label, so a future run -- even a fresh Python process tomorrow after
attempts refresh -- knows BEFORE spending an attempt whether a stage is already a
confirmed power wall, and can skip it (or flag it for a human) instead of re-losing an
attempt to rediscover the same thing.

Schema:
{
  "cave_of_monsters": {
    "3-8": {
      "status": "power_wall" | "cleared" | "in_progress",
      "last_checked": "2026-08-12",
      "losses_recorded": 2,
      "enemy_comp": {"inf": 33, "lan": 33, "mrk": 33},
      "best_ratio_tried": {"inf": 33, "lan": 33, "mrk": 33},
      "note": "..."
    }
  }
}
"""

import json
from pathlib import Path

HISTORY_PATH = Path(r"C:\Bearguard\labyrinth-debug\auto\labyrinth_stage_history.json")


def load_history():
    if HISTORY_PATH.exists():
        return json.loads(HISTORY_PATH.read_text())
    return {}


def save_history(history):
    HISTORY_PATH.write_text(json.dumps(history, indent=2))


def is_known_power_wall(zone, stage_label):
    """True if this exact stage is already recorded as a confirmed power wall --
    caller should skip challenging it rather than burn a real attempt re-losing to
    something we already know. A human can clear the flag (delete the entry, or bump
    status) once pets/gear have actually improved."""
    history = load_history()
    entry = history.get(zone, {}).get(stage_label)
    return entry is not None and entry.get("status") == "power_wall"


def get_stage_record(zone, stage_label):
    return load_history().get(zone, {}).get(stage_label)


def record_stage_result(zone, stage_label, status, date, enemy_comp=None,
                         best_ratio_tried=None, note=""):
    """Upserts a stage's history entry. Called on both a power-wall stop (status=
    'power_wall') and a clean clear (status='cleared', mostly for completeness/audit)."""
    history = load_history()
    zone_hist = history.setdefault(zone, {})
    existing = zone_hist.get(stage_label, {})
    losses_recorded = existing.get("losses_recorded", 0)
    if status == "power_wall":
        losses_recorded += 1  # each power-wall record represents at least one real loss
    zone_hist[stage_label] = {
        "status": status,
        "last_checked": date,
        "losses_recorded": losses_recorded,
        "enemy_comp": enemy_comp if enemy_comp is not None else existing.get("enemy_comp"),
        "best_ratio_tried": best_ratio_tried if best_ratio_tried is not None
                            else existing.get("best_ratio_tried"),
        "note": note or existing.get("note", ""),
    }
    save_history(history)


def summarize_known_walls(zone=None):
    """Human-readable summary of every confirmed power wall on record -- what matt
    asked for: a reviewable record of why we stopped where we stopped."""
    history = load_history()
    zones = {zone: history[zone]} if zone and zone in history else history
    lines = []
    for z, stages in zones.items():
        for stage, rec in stages.items():
            if rec.get("status") == "power_wall":
                lines.append(
                    f"{z} {stage}: power wall (checked {rec.get('last_checked')}, "
                    f"{rec.get('losses_recorded')} loss(es) recorded) -- "
                    f"enemy comp {rec.get('enemy_comp')}, best ratio tried "
                    f"{rec.get('best_ratio_tried')}. {rec.get('note', '')}"
                )
    return "\n".join(lines) if lines else "No confirmed power walls on record."


if __name__ == "__main__":
    print(summarize_known_walls())
