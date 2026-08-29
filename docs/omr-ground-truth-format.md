# SheetSight OMR ground-truth format v1

The evaluation corpus uses one JSON document per source page. Source images remain outside Git; a fixture identifies the private page by a descriptive ID, pixel dimensions, and SHA-256. Coordinates are recorded in source-image pixels and are converted deterministically to canonical coordinates with `canonical = source * canonicalSize / sourceSize`.

## Required page fields

- `formatVersion`: exactly `1`.
- `pageId`: stable descriptive identifier, never a private filesystem path.
- `source`: width, height, SHA-256, and orientation after decode.
- `annotationStatus`: `COMPLETE` or a precise incomplete status. Only `COMPLETE` fixtures are eligible for accuracy metrics.
- `systems`: top-to-bottom source systems. Each system records its y-band, ordered staffs, and all left/internal/right barline x coordinates.
- `staffs`: `track=0` is the upper piano staff and `track=1` the lower piano staff. Each staff records five x-local line samples, clef, active key signature, and visible time signature.
- `events`: noteheads and rests. Every event has a stable ID, system, measure, staff, voice, source coordinate, and evidence fields.
- `timeSignature`: visible page meter as `beats`/`beatType`; pickup measures are declared explicitly.

## Event rules

A notehead contains:

- `pitch`: step `A`–`G` and integer octave;
- `head`: `FILLED` or `OPEN`;
- `chordId`: shared only by vertically simultaneous noteheads on the same staff/voice;
- `stem`: `UP`, `DOWN`, or `NONE`;
- `beams`, `flags`, `dots`, and rational `duration` (`numerator`/`denominator` of a whole note);
- `accidental`: a local printed accidental or `null`; key-signature alterations stay in staff metadata.

A rest contains `restType`, staff-relative source coordinate, rational duration, and dots. An unresolved visual is annotated explicitly with `resolution="UNRESOLVED"` and a reason; it is never replaced by a rest or omitted.

## Matching

Detection matching is one-to-one within the annotated source system and staff. Source coordinates are scaled to the diagnostic canonical size, then matched with Euclidean distance at a declared tolerance in local staff spaces. The initial corpus uses `0.50` staff spaces. Barline matching uses x distance only, within the same system. Precision, recall, F1, pitch, octave, duration, chord exact match, and rest type accuracy must not be reported from a fixture whose `annotationStatus` is not `COMPLETE`.

The simplified and Happy Birthday fixtures are `COMPLETE` and eligible for
event metrics. Canon is `STRUCTURE_COMPLETE_EVENTS_PENDING`; its event metrics
remain unavailable. The other corpus entries stay `PENDING` and cannot
accidentally produce misleading percentages.
