# PaintCraft

A NeoForge mod for Minecraft 1.21.1 that lets you paint directly onto block surfaces
pixel-art decals with a neat color-palette generated to fit the theme of the game,
wrapped around blocks and even recessed geometry (stairs, slabs, fences).

## Tools

Three items, found in the Tools & Utilities creative tab:

- **Paint Brush** — right-click a block face to open the painting editor. Shift+right-click
  two corners to select a multi-block canvas before opening the editor. Right-clicking an
  existing decal reopens it for editing.
- **Stamp** — right-click an existing decal to copy it right-click any face to place the copy there
- **Eraser** — right-click a decal to remove a decal; shift+right-click
  to clear every decal on that face at once, no confirmation.

### The editor

A canvas painting UI with:

- **Pencil, Brush, Eraser, Fill, Line** tools, each with size/opacity, per-mouse-button
  bindings, and full undo/redo.
- A live background underlay sampled from the actual block texture behind the canvas
  (including other decals already stacked there).
- Color picker with an eyedropper that can sample the canvas or the underlying block.
- Clipboard image paste, where you must pay for the dyes.
- the color palette is dynamically generated from blocks you pick to be added to the UI.
  what this means, is that you can pick a diamond block, and it will sample all of the colors found here,
  and show you it blended with each other block you picked.

## How it works

Decals are stored per-chunk on the server and synced to nearby clients, then composited
client-side into a shared texture atlas and rendered as extra geometry over the block
face — no block replacement, no block entities per decal. Recessed faces (stair treads,
slab tops, fence posts) are projected correctly using the same depth-tested volume the
editor uses to capture its background, so paintings wrap around non-full-block shapes
instead of just floating over the bounding box.

With **3D relief** enabled, stacked layers of paint are extruded
outward by their stack height — nothing extra is stored, the geometry is derived purely
from how many opaque layers cover each texel.

## Compatibility

- **Create** — decals painted on a contraption's blocks move and rotate with it.
- **Iris** — detected automatically; rendering falls back to a compatible path when a
  shader pack is active. Not all shaders work. BSL is tested.

## Cost & permissions (server config)

Turning a painted canvas into a placed decal costs dye: each pixel votes for its
perceptually nearest vanilla dye color, and the total is charged in whole dye counts
(configurable via `pixelsPerDye`). Server admins can also tune max canvas size, max
decals per chunk, placement range, projection depth, and the permission level required
to edit or erase someone else's decal — see `ModServerConfig` / the generated
`paintcraft-server.toml`.

Client-side rendering, editor, and relief options live in `paintcraft-client.toml`
(`ModConfig`) — render distance, atlas texture size, relief resolution/thickness, undo
depth, and soft vs. hard erase.
