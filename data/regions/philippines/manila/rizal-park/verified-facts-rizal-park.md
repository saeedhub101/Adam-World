# Verified research notes: Rizal Park and Ermita

**Status:** Initial fact sheet; not a complete geographic inventory.  
**Last checked:** 2026-10-18  
**Purpose:** Capture concrete, source-linked facts before building the larger Ermita dataset.

## Rizal Park operating and management facts

- The National Parks Development Committee (NPDC) identifies itself as the agency responsible for developing, preserving, and managing Rizal Park and Paco Park.
- NPDC lists Rizal Park's address as Ermita, Manila, 1000 Metro Manila, Philippines.
- NPDC lists park operating hours as daily, including public holidays, 5:00 AM–10:00 PM. Confirm directly before relying on hours for a specific visit because schedules can change.
- NPDC lists its offices at the Old Planetarium, Rizal Park, Luneta, P. Burgos Street, Manila 1000.
- NPDC's published contact page lists the general email as info@npdc.gov.ph and Park Operations Division telephone as (02) 8880-4895.

Sources:
- https://npdc.gov.ph/contact-us/
- https://npdc.gov.ph/contact-us-2/
- https://npdc.gov.ph/

## Officially documented tree information

NPDC's tree inventory page reports **679** under its overall “Total No. of Inventory of Trees in Rizal Park” counter. The same page also shows category counters, including 71 indigenous forest trees, 25 exotic forest trees, 18 Ficus species, 17 fruit-bearing trees, and 4 palm trees. These appear to be counts associated with the site's inventory categories; do not add the category counters together or treat them as mutually exclusive without checking the underlying records.

A separate NPDC PDF, *2024 Distribution Map of Native Trees at Western and Central Sections of Rizal Park*, reports **1,084 native trees and palms** for the mapped sections. Its map labels Narra (413), Manila Palm (159), and Molave (151) as dominant species, and gives an estimated mapped area of 23.07 hectares. This PDF covers the western and central sections and is not necessarily the same population or counting scope as the website's overall inventory counter. Preserve that distinction in any later analysis.

Sources:
- NPDC tree inventory portal: https://npdc.gov.ph/inventory-of-trees/
- NPDC 2024 tree distribution map PDF: https://npdc.gov.ph/wp-content/uploads/2025/02/Inventory-Of-Trees.pdf
- NPDC inventory archive and downloadable inventory references: https://npdc.gov.ph/category/inventory-of-trees/

## Park landmark names to record as separate features

The following names are included as starter records from the NPDC Rizal Park information collected for this project. Each should eventually receive its own record with feature type, coordinates or geometry, source URL, source date, and verification status:

### Central Section
1. Rizal Monument
2. The Independence Flagpole
3. Noli Me Tangere Garden
4. The Martyrdom of Dr. Jose Rizal
5. Gomburza Marker
6. Chinese Garden
7. Musical Dancing Fountain
8. Gallery of Heroes
9. Rizal Park Open-Air Auditorium
10. Japanese Garden
11. Flower Clock
12. Bust of President Diosdado P. Macapagal
13. Senior Citizen's Garden
14. Kanlungan ng Sining Artist's Haven

### Western Section
15. Quirino Grandstand
16. Parade Ground
17. Burnham Green
18. San Lorenzo Ruiz Plaza

**Important:** This is a starter list, not a claim that these are all park features. Verify names and exact positions against current NPDC maps and on-site signage.

Sources to check for authoritative names and maps:
- https://npdc.gov.ph/rizal-park/
- https://npdc.gov.ph/downloads/

## Geography and street-map sources

- OpenStreetMap's Ermita relation page is a starting point for the neighborhood boundary and community-maintained mapped features: https://www.openstreetmap.org/relation/103706
- OpenStreetMap data is available under the Open Database License (ODbL); provide attribution and review share-alike requirements before redistributing derived databases: https://www.openstreetmap.org/copyright
- Overpass API can query mapped features by area and tag, but the resulting extract must be dated and documented: https://overpass-api.de/

For every extracted feature, retain the source identifier, feature type, name as mapped, coordinates/geometry, tags, retrieval date, and any uncertainty. An absent name in the source does not mean a real-world feature has no name.

## Known gaps to fill

- Download and parse NPDC's source tree inventory spreadsheets; preserve tree IDs, local/common and scientific names, family, measurements, health/defect/risk fields, and coordinates where present.
- Capture park paths, entrances, gates, facilities, monuments, and individual trees as georeferenced features.
- Extract Ermita streets, intersections, building footprints, named buildings, and amenities from OSM or another properly licensed source.
- Independently verify hospitals, clinics, police/fire stations, schools, public offices, transit stops, and other service locations.
- Add source URLs and verification dates to each individual feature, not only to this overview.
- Check coverage and duplicates; do not describe the result as exhaustive until those checks are documented.

## Data and rights note

Facts and short factual descriptions are recorded with links to their sources. Do not copy Google Maps tiles, Street View imagery, or bulk Google map data into this repository. Use permitted links as references only, and use openly licensed or official downloadable datasets for redistributable geometry. Review the terms for each dataset before committing bulk data.
