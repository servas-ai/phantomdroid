# Plan item — render-heatmap.py test coverage (weekly baseline heatmap) — DONE

`scripts/render-heatmap.py` (invoked by the Paperclip 'Weekly heatmap cell-sweep + render' routine; also
the matrix heatmap renderer) had no tests. Added `tests/test_render_heatmap.py` (3 tests):
- `cell_color` thresholds: None→grey, ≤0.3→green, ≤0.65→amber, >0.65→red (boundary-inclusive verified)
- `cell_label`: None→"n/a", numeric→2-dp
- `render_svg`: output is well-formed XML (parsed via minidom), labelled "ISO W21", contains all
  3 OS-version column headers + 3 device row labels (3×3 baseline matrix)
