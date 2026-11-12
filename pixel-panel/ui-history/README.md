# UI version record

`index.json` lists sealed design versions. Each `versions/<id>/version.json`
indexes the actual saved files by SHA-256, including screenshots, executable
APKs, source snapshots and evaluation evidence where available. These records
are outside the disposable `.build` directory and are committed with the project.

Historical imports retain original evidence without pretending that missing
APKs or source files were captured. Some folders include explicitly named
`before-*` files and working notes; their capture/assessment manifests describe
the intended candidate. Rejected candidates remain available for comparison.

Before changing an evaluated design, seal its exact built APK, current app
source, native captures, task traces and evaluation snapshot:

```powershell
python pixel-panel/scripts/record_ui.py seal pass-14 --label "Contextual numeric controls" --decision promote --evidence pixel-panel/.build/actuation-review/candidate-14
python pixel-panel/scripts/record_ui.py verify
```

Sealed IDs are never overwritten. New rankings or observations belong in a new
evaluation record referencing version IDs and image hashes. Later comparisons
should match state, fixture data, display scale and task targets. Keep the fixed
Bambu reference separate from this candidate history.

Evaluation folders are snapshots of the working evaluation documents. A legacy
scorecard inside an early record is not a new score for that candidate. Use the
version-specific assessment or the later comparison that names its ID/APK hash.
Never rewrite a sealed version to add a later judgment.

Normal and reduced-motion MP4s retain their action telemetry and renderer
measurements. Raw frame records separate scheduling delay from UI/render work.
Static Bambu references have no invented animation score. New capture runs pin
material temperature and manual distances/speeds through native controls; older
records show their actual captured values and may retain prior review context.

To revisit an executable, install its preserved APK on the isolated review
emulator, then relaunch using the bound Device command and the debug
`test_endpoint=http://127.0.0.1:18725` launch extra. Keep the loopback fixture
running. Installing an APK restores its code/assets, not its old runtime state;
use its saved screenshots for exact historical ranking and initialize the same
review parameters for a new comparison. Do not change the saved printer endpoint.
