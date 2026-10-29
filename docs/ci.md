# Continuous integration

The GitHub Actions workflow at `.github/workflows/ci.yml` runs on every push, pull request, and manual dispatch.

It uses `ubuntu-latest` and performs the complete verification path:

1. Installs CMake, Clang, FreeType, nlohmann JSON, DejaVu fonts, Mesa, X11, and Xvfb.
2. Downloads Jolt v0.8.23 and verifies its published SHA-256 before installation.
3. Clones Slughorn recursively at `704ef1d96751a5a5a38f3cff0f0ded4e7b6893dc`, including the msdfgen and JSON submodules.
4. Resolves Jolt dependencies and runs `jolt native`.
5. Runs the full Jolt test suite and C++/rendered-frame parity under an Xvfb virtual display with software OpenGL.

The job has a 45-minute timeout and read-only repository permissions. It does not publish artifacts, deploy releases, or apply FPS thresholds. Performance-profile results remain hardware-specific diagnostics.

Equivalent local commands are:

```sh
jolt -P
jolt native
SLIPSTREAM_TEST_FONT=/path/to/font.ttf jolt test
SLIPSTREAM_TEST_FONT=/path/to/font.ttf jolt parity
```

GUI tests require a working display; use Xvfb on a headless Linux runner. A green build alone is not visual verification.
