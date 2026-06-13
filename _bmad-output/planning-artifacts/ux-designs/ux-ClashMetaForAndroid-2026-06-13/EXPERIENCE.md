---
status: final
updated: 2026-06-13
system: native
---

# Connections View Experience

## Foundation
The design is intended for the Android mobile form-factor, leveraging the existing native view system (`RecyclerView`, `ConstraintLayout`).

## Information Architecture
1. **Top Bar**: Toolbar with title "Connections" and a back button.
2. **Filters/Actions Bar**: Contains toggles for "Active" and "Closed", alongside a button/icon to "Close All" connections.
3. **App Groups**: Connections are grouped by the originating application package.
4. **App Details (Accordion Parent)**:
   - App Icon
   - App Name
   - Total active connections count
   - Aggregated download/upload speed
5. **Connection Item (Accordion Child)**:
   - Destination Host / IP
   - Proxy Rule (e.g., Match)
   - Proxy Chain (e.g., [Proxy -> Node])
   - Traffic speeds (DL/UL)
   - Click to open Bottom Sheet Details or perform "Close Connection" action.

## State Patterns
- **Accordion State**: App groups can be `Collapsed` (default) or `Expanded`. Clicking the group card toggles this state.
- **Empty State**: Displays an empty illustration if no connections match the active filters.

## Interaction Primitives
- **Group Click**: Expands the group inline (Accordion), revealing children.
- **Child Click**: Opens a Bottom Sheet with exhaustive connection details (GeoIP, raw rule payload, start time, UID, network protocol).
- **Long Press on Child (Optional)**: Quick action context menu (e.g., Close connection).

## Key Flows
**Flow 1: Checking App Traffic**
1. User opens Connections page.
2. User scrolls to find "Telegram" grouped card.
3. User clicks the "Telegram" card.
4. The card expands downwards, revealing 5 active connection items, showing exactly which server nodes are routing Telegram traffic and their real-time speed.
5. User clicks a specific connection item to view the bottom sheet details and confirm the proxy chain.
