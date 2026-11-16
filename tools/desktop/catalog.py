#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Spektrafilm desktop editor catalog builder. GPLv3.

Builds the editor's film/paper/preset catalog at runtime from the real engine
assets plus the app's Chinese string resources.  Nothing is copied into this
file: ``catalog.json``, ``presets.json`` and ``strings_editor.xml`` stay the
single sources of truth, so a profile or a translation added to the app shows
up in the desktop editor without a second edit.

The loader deliberately fails loudly.  A missing asset, malformed JSON/XML or
a structurally invalid catalog raises :class:`CatalogError` instead of
returning an empty menu; an individual missing translation is not fatal and
falls back to the English asset text (the stock's official model name is never
translated).

Public API:
    load_catalog(assets_dir=None, zh_strings_path=None) -> dict
    Catalog.load(...).to_dict()

The returned dictionary is JSON-serialisable and contains:

    version, presets (each with the authored ``exposureEv`` default),
    stocks, films, prints, groups, presetGroups, counts
"""

from __future__ import annotations

import json
import math
import re
import unicodedata
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any, Dict, List, Mapping, Optional

__all__ = [
    "CatalogError",
    "Catalog",
    "load_catalog",
    "slugify",
    "find_project_root",
    "DEFAULT_ASSETS_DIR",
    "DEFAULT_ZH_STRINGS",
]

# tools/desktop/catalog.py -> repository root
_REPO_ROOT = Path(__file__).resolve().parents[2]

DEFAULT_ASSETS_DIR = (
    _REPO_ROOT / "engine" / "spektra-core" / "src" / "main" / "assets" / "spektra"
)
DEFAULT_ZH_STRINGS = (
    _REPO_ROOT / "app" / "src" / "main" / "res" / "values-zh-rCN" / "strings_editor.xml"
)

# Key for the small "scanned as a positive" label used in reversal preset specs.
_SCAN_POSITIVE_KEY = "editor_preset_scan_positive"
_SCAN_POSITIVE_FALLBACK = "按正像扫描"

# Printed stocks are the two print media kinds.  Capture film is kind=film only.
_PRINT_KINDS = frozenset(("print_paper", "print_film"))


class CatalogError(RuntimeError):
    """The catalog cannot be built from the on-disk resources."""


def find_project_root(start: Optional[Path] = None) -> Path:
    """Return the repository root (the directory holding ``engine/`` and ``app/``)."""
    here = Path(start) if start is not None else Path(__file__).resolve()
    if here.is_file():
        here = here.parent
    for candidate in (here, *here.parents):
        if (candidate / "engine" / "spektra-core").is_dir() and (candidate / "app").is_dir():
            return candidate
    # Keep the computed path for the error message; callers get a clear failure
    # later when an asset path does not exist.
    return _REPO_ROOT


def slugify(value: str) -> str:
    """Match the app's resource-key slug rule: lowercase, words joined by ``_``.

    The Android side maps ``"Low Light"`` to ``preset_group_low_light`` and
    ``"Portrait"`` to ``preset_group_portrait``.  Keep letters/digits (Unicode
    included), turn any other run into a single underscore, and trim edges.
    """
    if not isinstance(value, str) or not value.strip():
        return ""
    text = unicodedata.normalize("NFKC", value).strip().lower()
    out: List[str] = []
    for ch in text:
        if ch.isalnum():
            out.append(ch)
        elif ch in (" ", "_", "-"):
            out.append("_")
        else:
            out.append("_")
    slug = re.sub(r"_+", "_", "".join(out)).strip("_")
    return slug


def _read_json(path: Path, label: str) -> Any:
    if not path.is_file():
        raise CatalogError(f"缺少{label}资源：{path}")
    try:
        with path.open("r", encoding="utf-8") as handle:
            return json.load(handle)
    except OSError as exc:
        raise CatalogError(f"无法读取{label}资源 {path}：{exc}") from exc
    except json.JSONDecodeError as exc:
        raise CatalogError(f"{label}资源不是合法 JSON（{path}）：{exc}") from exc


def load_string_resources(path: Path) -> Dict[str, str]:
    """Parse an Android ``strings*.xml`` file into a name -> text mapping.

    A missing or malformed resource file is an explicit error.  Duplicate
    string names are rejected as malformed rather than silently letting the
    last one win.
    """
    path = Path(path)
    if not path.is_file():
        raise CatalogError(f"缺少中文资源文件：{path}")
    try:
        root = ET.parse(path).getroot()
    except OSError as exc:
        raise CatalogError(f"无法读取中文资源文件 {path}：{exc}") from exc
    except ET.ParseError as exc:
        raise CatalogError(f"中文资源文件不是合法 XML（{path}）：{exc}") from exc
    if root.tag != "resources":
        raise CatalogError(f"中文资源文件根元素应为 <resources>（{path}）")

    strings: Dict[str, str] = {}
    for element in root:
        if element.tag != "string":
            continue
        name = element.get("name")
        if not name:
            raise CatalogError(f"中文资源文件存在没有 name 的 <string>（{path}）")
        if name in strings:
            raise CatalogError(f"中文资源文件存在重复字符串 {name}（{path}）")
        text = element.text if element.text is not None else ""
        # Android's aapt unescapes a few entities beyond XML.  These files use
        # plain text and XML-escaped apostrophes; ET already decoded those.
        strings[name] = text
    if not strings:
        raise CatalogError(f"中文资源文件没有任何 <string> 条目（{path}）")
    return strings


def _require_object(value: Any, label: str) -> Mapping[str, Any]:
    if not isinstance(value, dict):
        raise CatalogError(f"{label} 必须是 JSON 对象")
    return value


def _require_list(value: Any, label: str) -> List[Any]:
    if not isinstance(value, list):
        raise CatalogError(f"{label} 必须是 JSON 数组")
    return value


def _require_text(obj: Mapping[str, Any], key: str, label: str) -> str:
    value = obj.get(key)
    if not isinstance(value, str) or not value.strip():
        raise CatalogError(f"{label} 缺少非空字符串字段 {key!r}")
    return value


def _optional_int(obj: Mapping[str, Any], key: str) -> Optional[int]:
    value = obj.get(key)
    if value is None:
        return None
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value
    if isinstance(value, float) and value.is_integer():
        return int(value)
    if isinstance(value, str) and value.strip().isdigit():
        return int(value.strip())
    return None


def _preset_exposure_ev(params: Mapping[str, Any]) -> float:
    """Return the authored camera exposure compensation, defaulting to 0 EV.

    The desktop UI shows ``exposureEv * strength`` but does not send an explicit
    ``exposureEv`` override unless the user moves the control, so this value is
    the only authored default the page can display.  A missing/invalid camera
    block is neutral, not a catalog failure: the native preset reader still owns
    validation when the preset is actually applied.
    """
    camera = params.get("camera")
    if not isinstance(camera, dict):
        return 0.0
    value = camera.get("exposureCompensationEv")
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return 0.0
    number = float(value)
    if not math.isfinite(number):
        return 0.0
    return number


class Catalog:
    """The merged preset + stock model, plus its JSON projection."""

    def __init__(
        self,
        *,
        presets: List[Dict[str, Any]],
        stocks: List[Dict[str, Any]],
        groups: List[Dict[str, Any]],
        preset_groups: List[Dict[str, Any]],
        version: int = 1,
    ) -> None:
        self.presets = presets
        self.stocks = stocks
        self.groups = groups
        self.preset_groups = preset_groups
        self.version = version

    @property
    def films(self) -> List[Dict[str, Any]]:
        return [stock for stock in self.stocks if stock["kind"] == "film"]

    @property
    def prints(self) -> List[Dict[str, Any]]:
        return [stock for stock in self.stocks if stock["kind"] in _PRINT_KINDS]

    @classmethod
    def load(
        cls,
        assets_dir: Optional[Path] = None,
        zh_strings_path: Optional[Path] = None,
    ) -> "Catalog":
        assets = Path(assets_dir) if assets_dir is not None else DEFAULT_ASSETS_DIR
        strings_path = (
            Path(zh_strings_path) if zh_strings_path is not None else DEFAULT_ZH_STRINGS
        )

        catalog_path = assets / "catalog.json"
        presets_path = assets / "presets.json"
        catalog_raw = _require_object(_read_json(catalog_path, "胶片目录"), str(catalog_path))
        presets_raw = _require_object(_read_json(presets_path, "内置预设"), str(presets_path))
        strings = load_string_resources(strings_path)

        raw_groups = _require_list(catalog_raw.get("groups"), f"{catalog_path}: groups")
        raw_stocks = _require_object(catalog_raw.get("stocks"), f"{catalog_path}: stocks")
        if not raw_stocks:
            raise CatalogError(f"胶片目录没有 stock（{catalog_path}）")

        group_by_id: Dict[str, Dict[str, Any]] = {}
        groups: List[Dict[str, Any]] = []
        for index, raw in enumerate(raw_groups):
            group = _require_object(raw, f"{catalog_path}: groups[{index}]")
            group_id = _require_text(group, "id", f"groups[{index}]")
            if group_id in group_by_id:
                raise CatalogError(f"胶片目录存在重复分组 id {group_id!r}")
            title_en = str(group.get("title") or group_id)
            order = _optional_int(group, "order")
            entry = {
                "id": group_id,
                "title": strings.get(f"stock_group_{group_id}", title_en),
                "titleEn": title_en,
                "order": order if order is not None else index,
            }
            group_by_id[group_id] = entry
            groups.append(entry)
        groups.sort(key=lambda item: (item["order"], item["id"]))

        stocks: List[Dict[str, Any]] = []
        seen_stock_ids = set()
        for stock_id, raw in raw_stocks.items():
            if not isinstance(stock_id, str) or not stock_id.strip():
                raise CatalogError(f"胶片目录存在空 stock id（{catalog_path}）")
            if stock_id in seen_stock_ids:
                raise CatalogError(f"胶片目录存在重复 stock id {stock_id!r}")
            seen_stock_ids.add(stock_id)
            stock = _require_object(raw, f"stocks.{stock_id}")
            name = _require_text(stock, "name", f"stocks.{stock_id}")
            kind = _require_text(stock, "kind", f"stocks.{stock_id}")
            group_id = str(stock.get("group") or "")
            if not group_id:
                raise CatalogError(f"stocks.{stock_id} 缺少 group")
            if group_id not in group_by_id:
                raise CatalogError(
                    f"stocks.{stock_id} 引用了不存在的分组 {group_id!r}（{catalog_path}）"
                )
            iso = _optional_int(stock, "iso")
            balance = stock.get("balance")
            balance = balance.strip() if isinstance(balance, str) and balance.strip() else None
            balance_label = (
                strings.get(f"stock_balance_{balance}", balance) if balance else None
            )
            summary_en = str(stock.get("summary") or "")
            summary = strings.get(f"stock_summary_{stock_id}", summary_en)
            order = _optional_int(stock, "order")
            if order is None:
                order = 0
            group = group_by_id[group_id]
            spec_parts: List[str] = []
            if iso is not None:
                spec_parts.append(f"ISO {iso}")
            if balance_label:
                spec_parts.append(balance_label)
            stocks.append(
                {
                    "id": stock_id,
                    "name": name,
                    "manufacturer": str(stock.get("manufacturer") or ""),
                    "kind": kind,
                    "iso": iso,
                    "balance": balance,
                    "balanceLabel": balance_label,
                    "group": group_id,
                    "groupSlug": slugify(group_id),
                    "groupTitle": group["title"],
                    "groupTitleEn": group["titleEn"],
                    "groupOrder": group["order"],
                    "order": order,
                    "summary": summary,
                    "summaryEn": summary_en,
                    "spec": " · ".join(spec_parts),
                }
            )

        stocks.sort(key=lambda item: (item["groupOrder"], item["order"], item["id"]))
        stock_by_id = {stock["id"]: stock for stock in stocks}

        raw_presets = _require_list(presets_raw.get("presets"), f"{presets_path}: presets")
        if not raw_presets:
            raise CatalogError(f"内置预设为空（{presets_path}）")

        presets: List[Dict[str, Any]] = []
        preset_groups: List[Dict[str, Any]] = []
        preset_group_seen: Dict[str, Dict[str, Any]] = {}
        seen_preset_ids = set()
        for index, raw in enumerate(raw_presets):
            preset = _require_object(raw, f"presets[{index}]")
            preset_id = _require_text(preset, "id", f"presets[{index}]")
            if preset_id in seen_preset_ids:
                raise CatalogError(f"内置预设存在重复 id {preset_id!r}")
            seen_preset_ids.add(preset_id)
            name_en = _require_text(preset, "name", f"presets[{preset_id}]")
            description_en = str(preset.get("description") or "")
            group_en = str(preset.get("group") or "").strip() or "Presets"
            group_slug = slugify(group_en)
            params = preset.get("params")
            if not isinstance(params, dict):
                raise CatalogError(f"预设 {preset_id} 缺少 params 对象")

            film_profile = str(params.get("filmProfile") or "")
            print_profile = str(params.get("printProfile") or "")
            io_block = params.get("io") if isinstance(params.get("io"), dict) else {}
            scan_film = bool(io_block.get("scanFilm", False))
            film = stock_by_id.get(film_profile)
            print_stock = stock_by_id.get(print_profile)
            film_name = film["name"] if film else film_profile
            print_name = print_stock["name"] if print_stock else print_profile
            if not film_name:
                spec = ""
            elif scan_film:
                positive = strings.get(_SCAN_POSITIVE_KEY, _SCAN_POSITIVE_FALLBACK)
                spec = f"{film_name} · {positive}"
            elif print_name:
                spec = f"{film_name} · {print_name}"
            else:
                spec = film_name

            group_title = strings.get(f"preset_group_{group_slug}", group_en)
            preset_entry = {
                "id": preset_id,
                "name": strings.get(f"preset_name_{preset_id}", name_en),
                "nameEn": name_en,
                "description": strings.get(f"preset_desc_{preset_id}", description_en),
                "descriptionEn": description_en,
                "group": group_title,
                "groupEn": group_en,
                "groupSlug": group_slug,
                "spec": spec,
                "exposureEv": _preset_exposure_ev(params),
                "filmProfile": film_profile,
                "printProfile": print_profile,
                "scanFilm": scan_film,
            }
            presets.append(preset_entry)
            if group_slug not in preset_group_seen:
                entry = {
                    "slug": group_slug,
                    "title": group_title,
                    "titleEn": group_en,
                    "order": len(preset_group_seen),
                }
                preset_group_seen[group_slug] = entry
                preset_groups.append(entry)

        # Presets keep the engine's authored order; that is the curated order the
        # Android editor shows, not an alphabetical accident.  Preset groups use
        # first-seen order, which is the order their first preset appears in.
        preset_groups.sort(key=lambda item: item["order"])

        return cls(
            presets=presets,
            stocks=stocks,
            groups=groups,
            preset_groups=preset_groups,
            version=_optional_int(catalog_raw, "version") or 1,
        )

    def to_dict(self) -> Dict[str, Any]:
        films = self.films
        prints = self.prints
        return {
            "version": self.version,
            "presets": self.presets,
            "stocks": self.stocks,
            "films": films,
            "prints": prints,
            "groups": self.groups,
            "presetGroups": self.preset_groups,
            "counts": {
                "presets": len(self.presets),
                "stocks": len(self.stocks),
                "films": len(films),
                "prints": len(prints),
            },
        }


def load_catalog(
    assets_dir: Optional[Path] = None,
    zh_strings_path: Optional[Path] = None,
) -> Dict[str, Any]:
    """Load and merge the real catalog/presets/Chinese resource files."""
    return Catalog.load(assets_dir=assets_dir, zh_strings_path=zh_strings_path).to_dict()


if __name__ == "__main__":  # pragma: no cover - handy manual inspection
    print(json.dumps(load_catalog(), ensure_ascii=False, indent=2))
