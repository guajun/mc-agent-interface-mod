#!/usr/bin/env python3
"""Unit tests for the issue #7 spike driver's save-folder safety.

Run from the repository root:

    python -m unittest discover -s spike -p "test_*.py"

They cover review item 4: a world name may not escape the experiment game
directory, an existing save is never deleted to "make room", and a failed copy
cleans up only its own partial target.
"""

from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent


def load_driver():
    spec = importlib.util.spec_from_file_location("same_port_spike_under_test", HERE / "same_port_spike.py")
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


driver = load_driver()


def make_lab_world(root: Path) -> Path:
    lab = root / "lab"
    world = lab / "world"
    world.mkdir(parents=True)
    (world / "level.dat").write_bytes(b"level")
    (world / "session.lock").write_bytes(b"lock")
    (world / "region").mkdir()
    (world / "region" / "r.0.0.mca").write_bytes(b"region")
    return lab


class WorldNameTests(unittest.TestCase):
    def test_accepts_single_segment_names(self):
        for name in ("issue7-lan", "world.copy_1", "A", "a" * 64):
            self.assertEqual(driver.validate_world_name(name), name)

    def test_rejects_escape_and_separator_names(self):
        for name in ("", "..", "../escape", "a/b", "a\\b", ".hidden", "a" * 65, "/abs", "C:name"):
            with self.assertRaises(SystemExit, msg=f"{name!r} must be rejected"):
                driver.validate_world_name(name)


class PrepareWorldTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.lab = make_lab_world(self.root)
        self.game = self.root / "game"
        (self.game / "saves").mkdir(parents=True)

    def tearDown(self):
        self.tmp.cleanup()

    def test_copies_a_fresh_world_and_strips_the_lock(self):
        target = driver.prepare_lan_world(self.lab, self.game, "issue7-lan")
        self.assertTrue((target / "level.dat").is_file())
        self.assertTrue((target / "region" / "r.0.0.mca").is_file())
        self.assertFalse((target / "session.lock").exists())

    def test_refuses_to_replace_an_existing_save(self):
        existing = self.game / "saves" / "issue7-lan"
        existing.mkdir(parents=True)
        (existing / "level.dat").write_bytes(b"player save")
        with self.assertRaises(SystemExit):
            driver.prepare_lan_world(self.lab, self.game, "issue7-lan")
        self.assertEqual((existing / "level.dat").read_bytes(), b"player save")

    def test_refuses_to_replace_an_existing_non_world_directory(self):
        existing = self.game / "saves" / "issue7-lan"
        existing.mkdir(parents=True)
        (existing / "notes.txt").write_text("mine", encoding="utf-8")
        with self.assertRaises(SystemExit):
            driver.prepare_lan_world(self.lab, self.game, "issue7-lan")
        self.assertTrue((existing / "notes.txt").is_file())

    def test_force_replaces_only_the_named_target(self):
        existing = self.game / "saves" / "issue7-lan"
        existing.mkdir(parents=True)
        (existing / "level.dat").write_bytes(b"old")
        other = self.game / "saves" / "keep-me"
        other.mkdir(parents=True)
        (other / "level.dat").write_bytes(b"keep")
        target = driver.prepare_lan_world(self.lab, self.game, "issue7-lan", force=True)
        self.assertEqual((target / "level.dat").read_bytes(), b"level")
        self.assertEqual((other / "level.dat").read_bytes(), b"keep")

    def test_rejects_a_path_escape_before_touching_anything(self):
        with self.assertRaises(SystemExit):
            driver.prepare_lan_world(self.lab, self.game, "../escape")
        self.assertFalse((self.root / "escape").exists())
        self.assertEqual(list((self.game / "saves").iterdir()), [])

    def test_requires_a_generated_lab_world(self):
        empty_lab = self.root / "empty-lab"
        (empty_lab / "world").mkdir(parents=True)
        with self.assertRaises(SystemExit):
            driver.prepare_lan_world(empty_lab, self.game, "issue7-lan")
        self.assertFalse((self.game / "saves" / "issue7-lan").exists())


if __name__ == "__main__":
    unittest.main()
