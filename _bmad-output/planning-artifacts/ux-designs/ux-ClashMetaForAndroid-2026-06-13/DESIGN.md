---
status: final
updated: 2026-06-13
tokens:
  colors:
    surface: "?colorSurface"
    surfaceVariant: "?colorSurfaceVariant"
    primary: "?colorPrimary"
    textPrimary: "?textAppearanceListItem"
    textSecondary: "?textAppearanceListItemSecondary"
    success: "#4CAF50" # Used for active network speeds
  typography:
    family: "sans-serif"
    headingWeight: "bold"
    bodySize: "14sp"
    captionSize: "12sp"
  rounded:
    card: "12dp"
    chip: "16dp"
  spacing:
    screenPadding: "16dp"
    cardPadding: "8dp" # Reduced from 12dp to make cards more compact
    itemGap: "4dp"     # Reduced gap
  components:
    AppCard: "Elevated, compact card holding the App Icon, App Name, active connection count, and total aggregated speed."
    ConnectionCard: "Flat or lightly elevated compact card containing specific connection details. No vertical line on the left."
---

# Connections View Visual Design

## Brand & Style
The connections view adopts a modern, premium Android Material 3 aesthetic. It emphasizes dark mode compatibility, glassmorphism-like elevations, and clean separation of data points in a compact layout.

## Layout & Spacing
- The main screen is a `RecyclerView` listing `AppCard` items.
- A horizontal scroll view at the top holds filter chips.
- Padding inside cards is `{spacing.cardPadding}` to ensure a high-density, compact view.

## Elevation & Depth
- Group cards (`AppCard`) have a base elevation and rounded corners to separate them from the background.
- Child items (`ConnectionCard`) share the background color of the expanded accordion area or are indented (e.g. `72dp`) to visually align with the parent's text. **Crucially, there must be NO vertical lines connecting parent to child.**

## Typography
- Real-time speeds use a monospaced or bolded font to stand out.
- Unimportant details (like UUIDs) are omitted or relegated to the bottom sheet.
