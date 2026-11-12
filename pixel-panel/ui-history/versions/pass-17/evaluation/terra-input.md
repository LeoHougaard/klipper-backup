# Terra input on 0.3.8

Requested by Leo. Read-only audit by gpt-6.1-terra of the native source, fixed
Bambu images and pass 12/13 captures. No device actions or edits by Terra.

Terra prioritized contextual numeric entry, the input cost of common motion
changes, task grouping on Move/Printing, and truthful completion states. Its
proposed task ledger records exact commands, all inputs including digits,
transitions, corrections, feedback and interruption. Human discovery/time
must remain separate from label-guided automation.

Verification matters: Terra's reported missing idle Unload is not supported by
the actual native capture, which shows Load and Unload. Source creates both.
Cold Unload and cancel are nevertheless included in the actuation benchmark.
The keypad has a visible Close button; the verified issue is missing heater/unit
context and Close losing the parent editor, rather than absence of an escape.

The new actuation probe found an additional shared defect: replacing selected
numeric text with successive keypad digits leaves the selection active, so
entering 2 then 5 can produce 5 instead of 25. Earlier tests' unconditional
Clear hid this. The captured baseline proof is
`.build/actuation-review/baseline/selected-input-overwrite.png`.

Keep the direct print-speed access Leo requested; improve its relationship to
progress rather than removing it as duplication. Common-value palettes should
retain exact entry and motor/heater/state safeguards. No physical printer
testing is authorized by this audit.
