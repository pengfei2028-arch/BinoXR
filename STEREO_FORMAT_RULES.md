# Stereo Format Rules

Date: 2026-09-14

## Detection Precedence

1. A persisted user override always wins.
2. A persisted folder default wins when no image override exists.
3. Explicit imported metadata wins when no user or folder override exists.
4. Content analysis evaluates stereo-pair similarity and fisheye structure.
5. Aspect ratio provides a fallback candidate when stronger evidence is absent.
6. An unresolved image remains `Unknown` and opens in a safe 2D preview.

Aspect ratio alone is not sufficient to distinguish stereo, mono panorama, and flat images.

## Smart Detection

The initial relative tolerance is 8%.

| Evidence | Default format | Confidence |
| --- | --- | --- |
| Approximately 1:1 with structurally similar top and bottom halves | Equirectangular 360, Top/Bottom stereo | High |
| Approximately 1:1 without a stereo-pair match | Flat 2D | Medium |
| Approximately 2:1 with two fisheye discs and a stereo-pair match | Dual-fisheye 180, Side-by-side stereo | High |
| Approximately 2:1 without dual-fisheye structure | Equirectangular 360, mono | Medium |
| GPano/XMP projection metadata | Metadata-defined panorama | High |
| Other or conflicting evidence | Unknown, safe 2D preview | Low |

A 4:1 image is not automatically treated as side-by-side equirectangular stereo.
Content analysis must use a downscaled thumbnail and must not decode the full 8K source.

## Detection Modes

- `Smart` is the default and supports stereo panoramas, mono panoramas, and flat 2D images.
- `Strict VR` preserves the original aspect-ratio rule:
  - Approximately 1:1 defaults to Equirectangular 360 Top/Bottom.
  - Approximately 2:1 defaults to dual-fisheye 180 side-by-side.
  - Other ratios remain unknown.

The mode is a reversible product setting. Smart detection must not remove the strict rule.

## Manual Formats

The user can explicitly select:

- Equirectangular 360 mono
- Equirectangular 360 Top/Bottom stereo
- Dual-fisheye 180 mono
- Dual-fisheye 180 side-by-side stereo
- Flat 2D
- Automatic

The gallery displays a format badge and confidence state for every image.

## Eye Swap

`swapEyes` is an independent setting available for every stereo image.

- Top/Bottom with `swapEyes = false`: top = left eye, bottom = right eye.
- Top/Bottom with `swapEyes = true`: top = right eye, bottom = left eye.
- Side-by-side with `swapEyes = false`: left = left eye, right = right eye.
- Side-by-side with `swapEyes = true`: left = right eye, right = left eye.

The user override and `swapEyes` choice must persist per image. `swapEyes` is only shown for
stereoscopic formats.

## Milestone 1 Test Asset

- File: `幻彩油漆笔3.png`
- Source size: `8000 x 8000`
- Source format: 32-bit PNG
- Detected format: Equirectangular 360, Top/Bottom
- Default eye order: top = left, bottom = right
