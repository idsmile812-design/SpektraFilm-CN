# -*- coding: utf-8 -*-
"""Catalog tests: real 27 presets / 28 stocks plus explicit failure modes. GPLv3."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

DESKTOP = Path(__file__).resolve().parents[1]
if str(DESKTOP) not in sys.path:
    sys.path.insert(0, str(DESKTOP))

import catalog  # noqa: E402

XML_TEMPLATE = """<?xml version="1.0" encoding="utf-8"?>
<resources>
  <string name="stock_group_g">测试分组</string>
  <string name="stock_summary_s1">中文说明</string>
  <string name="stock_balance_daylight">日光</string>
  <string name="preset_name_p1">中文预设</string>
  <string name="preset_desc_p1">中文描述</string>
  <string name="preset_group_portrait">人像</string>
  <string name="editor_preset_scan_positive">按正像扫描</string>
</resources>
"""


def _write_minimal(tmp: Path, *, drop_preset_name: bool = False,
                   drop_summary: bool = False) -> tuple[Path, Path]:
    assets = tmp / "assets"
    assets.mkdir()
    (assets / "catalog.json").write_text(
        json.dumps(
            {
                "version": 1,
                "groups": [{"id": "g", "title": "Group", "order": 1}],
                "stocks": {
                    "s1": {
                        "name": "Model One",
                        "manufacturer": "Maker",
                        "group": "g",
                        "kind": "film",
                        "iso": 100,
                        "balance": "daylight",
                        "order": 5,
                        "summary": "English summary",
                    },
                    "p1": {
                        "name": "Print One",
                        "manufacturer": "Maker",
                        "group": "g",
                        "kind": "print_paper",
                        "order": 6,
                    },
                },
            }
        ),
        encoding="utf-8",
    )
    (assets / "presets.json").write_text(
        json.dumps(
            {
                "version": 2,
                "presets": [
                    {
                        "id": "p1",
                        "name": "English Preset",
                        "group": "Portrait",
                        "description": "English description",
                        "params": {
                            "filmProfile": "s1",
                            "printProfile": "p1",
                            "io": {"scanFilm": False},
                        },
                    }
                ],
            }
        ),
        encoding="utf-8",
    )
    xml = XML_TEMPLATE
    if drop_preset_name:
        xml = xml.replace('  <string name="preset_name_p1">中文预设</string>\n', "")
    if drop_summary:
        xml = xml.replace('  <string name="stock_summary_s1">中文说明</string>\n', "")
    strings = tmp / "strings_editor.xml"
    strings.write_text(xml, encoding="utf-8")
    return assets, strings


class RealCatalogTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.data = catalog.load_catalog()
        cls.by_preset = {p["id"]: p for p in cls.data["presets"]}
        cls.by_stock = {s["id"]: s for s in cls.data["stocks"]}
        cls.xml_keys = set(
            e.get("name") for e in ET.parse(catalog.DEFAULT_ZH_STRINGS).getroot()
            if e.tag == "string"
        )

    def test_counts_are_the_real_catalog(self) -> None:
        self.assertEqual(27, len(self.data["presets"]))
        self.assertEqual(28, len(self.data["stocks"]))
        self.assertEqual(20, len(self.data["films"]))
        self.assertEqual(8, len(self.data["prints"]))
        self.assertEqual(
            {"presets": 27, "stocks": 28, "films": 20, "prints": 8},
            self.data["counts"],
        )

    def test_film_and_print_lists_filter_by_kind(self) -> None:
        self.assertTrue(self.data["films"])
        self.assertTrue(self.data["prints"])
        self.assertTrue(all(stock["kind"] == "film" for stock in self.data["films"]))
        self.assertTrue(
            all(stock["kind"] in ("print_paper", "print_film")
                for stock in self.data["prints"])
        )
        # Every stock appears exactly once on exactly one side.
        ids = [stock["id"] for stock in self.data["films"] + self.data["prints"]]
        self.assertEqual(28, len(ids))
        self.assertEqual(28, len(set(ids)))

    def test_all_27_preset_chinese_keys_are_complete(self) -> None:
        for preset in self.data["presets"]:
            preset_id = preset["id"]
            self.assertIn(f"preset_name_{preset_id}", self.xml_keys)
            self.assertIn(f"preset_desc_{preset_id}", self.xml_keys)
            self.assertTrue(preset["name"].strip(), preset_id)
            self.assertTrue(preset["description"].strip(), preset_id)
            self.assertTrue(preset["group"].strip(), preset_id)
            self.assertTrue(preset["spec"].strip(), preset_id)
            # Chinese translation must actually be present for every preset.
            self.assertNotEqual(preset["name"], preset["nameEn"], preset_id)
            self.assertNotEqual(
                preset["description"], preset["descriptionEn"], preset_id
            )
        self.assertEqual(27, len(self.by_preset))

    def test_all_28_stock_translations_and_fields(self) -> None:
        for stock in self.data["stocks"]:
            stock_id = stock["id"]
            self.assertIn(f"stock_summary_{stock_id}", self.xml_keys, stock_id)
            self.assertIn(f"stock_group_{stock['group']}", self.xml_keys, stock_id)
            self.assertTrue(stock["name"].strip(), stock_id)
            self.assertIn(stock["kind"], ("film", "print_paper", "print_film"))
            self.assertTrue(stock["groupTitle"].strip(), stock_id)
            self.assertTrue(stock["summary"].strip(), stock_id)
            self.assertNotEqual(stock["summary"], stock["summaryEn"], stock_id)
            self.assertIsInstance(stock["order"], int)
            if stock["balance"]:
                self.assertIn(f"stock_balance_{stock['balance']}", self.xml_keys)
                self.assertTrue(stock["balanceLabel"])
            if stock["iso"] is not None or stock["balance"]:
                self.assertTrue(stock["spec"], stock_id)
            else:
                # Some print papers carry neither an ISO nor a balance.
                self.assertEqual("", stock["spec"], stock_id)
        self.assertEqual(28, len(self.by_stock))

    def test_preset_groups_are_localized_and_ordered(self) -> None:
        slugs = [group["slug"] for group in self.data["presetGroups"]]
        self.assertEqual(8, len(slugs))
        for slug in slugs:
            self.assertIn(f"preset_group_{slug}", self.xml_keys)
        self.assertIn("portrait", slugs)
        self.assertIn("low_light", slugs)
        orders = [group["order"] for group in self.data["presetGroups"]]
        self.assertEqual(sorted(orders), orders)

    def test_stock_sort_is_group_order_then_stock_order(self) -> None:
        keys = [
            (stock["groupOrder"], stock["order"], stock["id"])
            for stock in self.data["stocks"]
        ]
        self.assertEqual(sorted(keys), keys)
        # The first film really is a colour-negative Portra, not an alphabetic id.
        self.assertEqual("kodak_portra_160", self.data["films"][0]["id"])
        self.assertEqual(
            ["print_film", "print_film", "print_paper", "print_paper"],
            [stock["group"] for stock in self.data["prints"][:4]],
        )

    def test_reversal_preset_spec_says_scanned_as_positive(self) -> None:
        reversal = self.by_preset["velvia100_chrome_landscape"]
        self.assertTrue(reversal["scanFilm"])
        self.assertTrue(reversal["spec"].endswith("按正像扫描"))
        print_preset = self.by_preset["portra160_endura_softlight"]
        self.assertFalse(print_preset["scanFilm"])
        self.assertIn("Endura", print_preset["spec"])

    def test_preset_exposure_ev_is_the_authored_camera_default(self) -> None:
        for preset in self.data["presets"]:
            self.assertIsInstance(preset["exposureEv"], (int, float))
            self.assertNotIsInstance(preset["exposureEv"], bool)
            self.assertTrue(-12.0 <= float(preset["exposureEv"]) <= 12.0)
        self.assertAlmostEqual(0.3, self.by_preset["portra160_endura_softlight"]["exposureEv"])
        self.assertAlmostEqual(
            0.3, self.by_preset["portra400_endura_wedding"]["exposureEv"]
        )
        self.assertAlmostEqual(0.0, self.by_preset["portra800_endura_natural"]["exposureEv"])
        self.assertAlmostEqual(-0.2, self.by_preset["neutral_adobe_like"]["exposureEv"])
        self.assertAlmostEqual(1.0, self.by_preset["pro400h_crystalarchive_pastel"]["exposureEv"])

    def test_slug_rule_matches_android_resource_keys(self) -> None:
        self.assertEqual("low_light", catalog.slugify("Low Light"))
        self.assertEqual("portrait", catalog.slugify("Portrait"))
        self.assertEqual("", catalog.slugify("   "))
        self.assertEqual("a_b_c", catalog.slugify("a / b--c"))


class BreakageTests(unittest.TestCase):
    def test_missing_catalog_is_an_explicit_error(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            assets, strings = _write_minimal(Path(tmp))
            (assets / "catalog.json").unlink()
            with self.assertRaises(catalog.CatalogError) as caught:
                catalog.load_catalog(assets, strings)
            self.assertIn("缺少胶片目录资源", str(caught.exception))

    def test_missing_presets_is_an_explicit_error(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            assets, strings = _write_minimal(Path(tmp))
            (assets / "presets.json").unlink()
            with self.assertRaises(catalog.CatalogError):
                catalog.load_catalog(assets, strings)

    def test_missing_chinese_resource_is_an_explicit_error(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            assets, strings = _write_minimal(Path(tmp))
            strings.unlink()
            with self.assertRaises(catalog.CatalogError) as caught:
                catalog.load_catalog(assets, strings)
            self.assertIn("缺少中文资源文件", str(caught.exception))

    def test_malformed_json_is_not_silently_empty(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            assets, strings = _write_minimal(Path(tmp))
            (assets / "catalog.json").write_text("{not json", encoding="utf-8")
            with self.assertRaises(catalog.CatalogError):
                catalog.load_catalog(assets, strings)

    def test_malformed_xml_is_not_silently_empty(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            assets, strings = _write_minimal(Path(tmp))
            strings.write_text("<resources><string", encoding="utf-8")
            with self.assertRaises(catalog.CatalogError):
                catalog.load_catalog(assets, strings)

    def test_unknown_stock_group_is_an_explicit_error(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            assets, strings = _write_minimal(Path(tmp))
            raw = json.loads((assets / "catalog.json").read_text(encoding="utf-8"))
            raw["stocks"]["s1"]["group"] = "nowhere"
            (assets / "catalog.json").write_text(json.dumps(raw), encoding="utf-8")
            with self.assertRaises(catalog.CatalogError):
                catalog.load_catalog(assets, strings)

    def test_empty_presets_is_an_explicit_error(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            assets, strings = _write_minimal(Path(tmp))
            raw = json.loads((assets / "presets.json").read_text(encoding="utf-8"))
            raw["presets"] = []
            (assets / "presets.json").write_text(json.dumps(raw), encoding="utf-8")
            with self.assertRaises(catalog.CatalogError):
                catalog.load_catalog(assets, strings)

    def test_missing_preset_camera_exposure_defaults_to_zero(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            assets, strings = _write_minimal(Path(tmp))
            data = catalog.load_catalog(assets, strings)
            self.assertEqual(0.0, data["presets"][0]["exposureEv"])

    def test_preset_camera_exposure_is_read_and_invalid_values_are_neutral(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            assets, strings = _write_minimal(Path(tmp))
            presets_path = assets / "presets.json"
            raw = json.loads(presets_path.read_text(encoding="utf-8"))
            raw["presets"][0]["params"]["camera"] = {
                "exposureCompensationEv": 0.65,
                "autoExposure": False,
            }
            presets_path.write_text(json.dumps(raw), encoding="utf-8")
            data = catalog.load_catalog(assets, strings)
            self.assertAlmostEqual(0.65, data["presets"][0]["exposureEv"])

            raw["presets"][0]["params"]["camera"]["exposureCompensationEv"] = "0.4"
            presets_path.write_text(json.dumps(raw), encoding="utf-8")
            data = catalog.load_catalog(assets, strings)
            self.assertEqual(0.0, data["presets"][0]["exposureEv"])

    def test_missing_translation_falls_back_to_english(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            assets, strings = _write_minimal(
                Path(tmp), drop_preset_name=True, drop_summary=True
            )
            data = catalog.load_catalog(assets, strings)
            preset = data["presets"][0]
            self.assertEqual("English Preset", preset["name"])
            self.assertEqual("中文描述", preset["description"])
            stock = [s for s in data["stocks"] if s["id"] == "s1"][0]
            self.assertEqual("English summary", stock["summary"])
            self.assertEqual("测试分组", stock["groupTitle"])

    def test_duplicate_translation_key_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            assets, strings = _write_minimal(Path(tmp))
            strings.write_text(
                XML_TEMPLATE.replace(
                    "</resources>",
                    '  <string name="preset_name_p1">重复</string>\n</resources>',
                ),
                encoding="utf-8",
            )
            with self.assertRaises(catalog.CatalogError):
                catalog.load_catalog(assets, strings)


if __name__ == "__main__":
    unittest.main()
