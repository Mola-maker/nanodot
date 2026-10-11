# Crystal orb provenance

`android/src/android/app/src/main/res/drawable-nodpi/nm_crystal_orb_atlas.png`
is a new image generated with the built-in imagegen tool for Nanodot. The user's
reference image supplied a color mood only; no reference character or original
artwork is bundled. The generated PNG is copied byte-for-byte, without repainting,
resampling or pixel edits. SHA-256:
`c5985abbf2fc39ee7cdf905b9d6e8f1c8eda300cdc0687811e4ca8727788d4e7`.

The 1254 × 1254 RGBA atlas has 16 row-major frames in a 4 × 4 grid. Runtime cell
edges use floor(index × dimension / 4), including the final row/column pixels.
A constant inset crops transparent padding within each cell; no neighboring cell
is sampled. The 200 ms frame interval gives a 3200 ms breathing cycle. Runtime
uses only the packaged resource, never a generator output directory.

`prompt.txt` records the generation prompt. `manifest.json` records generation
provenance; its preview status is historical and is not native device acceptance.
