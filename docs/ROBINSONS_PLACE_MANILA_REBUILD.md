# Robinsons Place Manila — native game reconstruction

## Target identity and placement
- Target: Robinsons Place Manila / Robinsons Manila, Malate–Ermita, Manila.
- OSM mall boundary reference: way `48894714`.
- Runtime anchor: 14.57644165, 120.98379392 (centre of the saved OSM mall boundary).
- Native implementation: `app/src/main/java/com/explorer/world3d/Landmarks.java`, method `robinsonsPlace()`.
- This is part of the active Android Java/OpenGL ES 2.0 renderer; it is not a legacy HTML/Three.js page or a detached billboard.

## Public photo references reviewed
- [Wikimedia Commons — Robinsons Place Manila category](https://commons.wikimedia.org/wiki/Category:Robinsons_Place_Manila)
- [Daytime curved facade and roof logo reference](https://phi.kamometour.co.jp/commons/images/up/2020/09/1601265486999.jpeg)
- [Entrance and facade panels](https://stat.ameba.jp/user_images/20251001/16/nankuru71/70/cb/j/o1080144015687421075.jpg)
- [Curved upper levels, glass rails and columns](https://4.bp.blogspot.com/-h9IGkt0NIjY/VeayFAxM2YI/AAAAAAAAACg/qsL8txqqFeE/s1600/11948096_10200917565859178_959644676_n.jpg)
- [Night illumination and rooftop sign](https://media-cdn.tripadvisor.com/media/photo-s/10/ce/6f/ce/photo0jpg.jpg)
- [Philippine Star — Midtown wing description](https://qa.philstar.com/lifestyle/food-and-leisure/2008/01/31/41745/robinsons-place-manilarsquos-midtown-wing-breathes-life-malate)

## Visual features modelled
- Broad elliptical/curved multi-level shopping-mall mass with four facade bands.
- Light warm-grey concrete/stone surfaces, cream horizontal floor slabs and parapets.
- Blue-grey glazed storefronts and upper ribbon windows.
- Repeated white cylindrical columns along the curved frontage.
- Curved balcony slabs with glass balustrades and metal top rails.
- Recessed entrance glazing, mullions, handles and a broad entrance canopy.
- Large red rooftop signboard, raised white ribbon emblem and raised Robinsons / Place Manila lettering.
- Retail fascia modules with visible photo-reference shop names (UNIQLO, HUAWEI, The Coffee Bean & Tea Leaf, YAKIMIX, SUSHI TEI and GUMBO).
- Roof service housings and skylight strips.
- Forecourt planters, low benches, chairs and tables.
- A matching collision footprint; generic building centroids inside the mall model are suppressed to prevent duplicate masses.

## Runtime behavior
- The landmark is generated when the Manila local render tile containing the anchor is built.
- Geometry is added to the native tile buffers and uploaded through the existing GPU mesh path.
- It uses the local Manila map origin and terrain height at the anchor; it does not move the city or alter surrounding road coordinates.
- No remote runtime image or model dependency is introduced.

## Accuracy and validation status
This is a photo-guided procedural reconstruction based on public exterior views and the mapped mall location. It is not a photogrammetric scan, a surveyed architectural BIM, or a UV projection of the source photographs. Shop names are reproduced as readable facade labels; the available photos do not establish a complete, current tenant-by-tenant sign schedule. Device-side visual validation is still required for exact orientation, dimensions, sign readability, collision and performance.

## Attribution
Map identity/boundary reference: OpenStreetMap contributors, ODbL 1.0. Photo references remain subject to the licence and reuse terms of their respective source pages; the runtime model does not embed or redistribute those source photographs.
