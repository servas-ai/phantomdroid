"""Unit tests for scripts/render-heatmap.py pure rendering logic (weekly baseline heatmap)."""
import importlib.util
import xml.dom.minidom
from pathlib import Path

_ROOT = Path(__file__).resolve().parents[1]
_spec = importlib.util.spec_from_file_location("render_heatmap", _ROOT / "scripts/render-heatmap.py")
rh = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(rh)


def test_cell_color_thresholds():
    assert rh.cell_color(None) == rh.COLORS["none"]
    assert rh.cell_color(0.0) == rh.COLORS["green"]
    assert rh.cell_color(rh.GREEN_THRESH) == rh.COLORS["green"]          # 0.3 inclusive green
    assert rh.cell_color(rh.GREEN_THRESH + 0.01) == rh.COLORS["amber"]
    assert rh.cell_color(rh.AMBER_THRESH) == rh.COLORS["amber"]          # 0.65 inclusive amber
    assert rh.cell_color(rh.AMBER_THRESH + 0.01) == rh.COLORS["red"]
    assert rh.cell_color(1.0) == rh.COLORS["red"]


def test_cell_label():
    assert rh.cell_label(None) == "n/a"
    assert rh.cell_label(0.3462) == "0.35"
    assert rh.cell_label(0.0) == "0.00"


def test_render_svg_is_well_formed_and_labelled():
    svg = rh.render_svg({}, iso_week=21, rendered_at="2026-05-31T00:00:00Z")
    assert svg.startswith("<svg") and svg.rstrip().endswith("</svg>")
    assert "ISO W21" in svg
    # parses as XML (well-formed)
    xml.dom.minidom.parseString(svg)
    # one column header per OS version, one row label per device
    for os_ver in rh.OS_VERSIONS:
        assert os_ver in svg
    for dev in rh.DEVICES:
        assert dev in svg
